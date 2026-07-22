package com.siheungbootcamp.teamd.domain.place

import com.siheungbootcamp.teamd.infra.external.kakao.KakaoStubServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

@Testcontainers
@ExtendWith(OutputCaptureExtension::class)
@AutoConfigureMockMvc
@SpringBootTest(properties = [
    "app.auth.token-pepper=test-pepper",
    "app.crypto.origin-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
    "app.board.frontend-base-url=https://example.app",
    "app.kakao.rest-key=test-kakao-key",
])
class PlaceContractTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcClient: JdbcClient,
) {
    @Test
    fun `V2-1 검색-후보선택-장소등록-목록조회 E2E 흐름이 성공한다`() {
        val host = createBoard("장소 보드", "호스트")

        // 검색
        val searchResult = mockMvc.get("/api/v1/boards/${host.boardId}/place-candidates") {
            bearer(host.token)
            param("query", "테스트 장소")
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val candidate = objectMapper.readTree(searchResult).path("items")[0]
        val placeId = candidate.path("providerPlaceId").asText()

        // 장소 등록
        val createResult = mockMvc.post("/api/v1/boards/${host.boardId}/places") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """
            {
              "name": "${candidate.path("name").asText()}",
              "lon": ${candidate.path("lon").asDouble()},
              "lat": ${candidate.path("lat").asDouble()},
              "addressName": "${candidate.path("addressName").asText()}",
              "roadAddressName": "${candidate.path("roadAddressName").asText()}",
              "internalCategory": "${candidate.path("internalCategory").asText()}",
              "provider": "KAKAO",
              "providerPlaceId": "$placeId",
              "providerPlaceUrl": "${candidate.path("providerPlaceUrl").asText()}",
              "source": "SEARCH_SELECT"
            }
            """.trimIndent()
        }.andExpect { status { isCreated() } }
            .andReturn().response.contentAsString

        val place = objectMapper.readTree(createResult)
        assertEquals(candidate.path("name").asText(), place.path("name").asText())

        // 목록 조회
        val listResult = mockMvc.get("/api/v1/boards/${host.boardId}/places") {
            bearer(host.token)
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val places = objectMapper.readTree(listResult).path("items")
        assertTrue(places.size() > 0, "목록에 등록된 장소가 있어야 함")
    }

    @Test
    fun `V2-2 검색 3회 호출 후 place 테이블 count는 0이다`() {
        val host = createBoard("검색 보드", "호스트")

        repeat(3) {
            mockMvc.get("/api/v1/boards/${host.boardId}/place-candidates") {
                bearer(host.token)
                param("query", "카페")
            }.andExpect { status { isOk() } }
        }

        val count = jdbcClient.sql("select count(*) as cnt from place where board_id=(select id from board where public_id=:boardId)")
            .param("boardId", host.boardId)
            .query(Int::class.java)
            .single()
        assertEquals(0, count, "검색만으로는 place가 생성되지 않아야 함")
    }

    @Test
    fun `V2-4 URL 형식 검색어는 400 URL_QUERY_NOT_ALLOWED를 반환한다`() {
        val host = createBoard("URL 검증 보드", "호스트")

        mockMvc.get("/api/v1/boards/${host.boardId}/place-candidates") {
            bearer(host.token)
            param("query", "https://example.com")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("URL_QUERY_NOT_ALLOWED") }
        }
    }

    @Test
    fun `V2-5 외부 429를 2회 받은 후 200을 받으면 최종 200이고 재시도는 3회 이하다`() {
        val host = createBoard("백오프 보드", "호스트")
        kakaoStubServer.setKeywordResponseMode(KakaoStubServer.ResponseMode.RATE_LIMIT)
        try {
            // stub이 처음 2회는 429(Retry-After: 1)를, 3번째는 200을 반환하도록 되어 있다.
            mockMvc.get("/api/v1/boards/${host.boardId}/place-candidates") {
                bearer(host.token)
                param("query", "테스트")
            }.andExpect { status { isOk() } }

            assertEquals(3, kakaoStubServer.requestCount("keyword"), "429 2회 이후 3번째 요청에서 성공해야 하고, 총 요청은 3회 이하여야 한다")
        } finally {
            kakaoStubServer.setKeywordResponseMode(KakaoStubServer.ResponseMode.SUCCESS)
        }
    }

    @Test
    fun `V2-6 외부 500 고정이면 503 EXTERNAL_UNAVAILABLE을 반환한다`() {
        val host = createBoard("서버 오류 보드", "호스트")
        kakaoStubServer.setKeywordResponseMode(KakaoStubServer.ResponseMode.SERVER_ERROR)
        try {
            mockMvc.get("/api/v1/boards/${host.boardId}/place-candidates") {
                bearer(host.token)
                param("query", "테스트")
            }.andExpect {
                status { isServiceUnavailable() }
                jsonPath("$.error.code") { value("EXTERNAL_UNAVAILABLE") }
            }
        } finally {
            kakaoStubServer.setKeywordResponseMode(KakaoStubServer.ResponseMode.SUCCESS)
        }
    }

    @Test
    fun `V2-7 외부 깨진 JSON이면 502 EXTERNAL_BAD_RESPONSE를 반환한다`() {
        val host = createBoard("JSON 오류 보드", "호스트")
        kakaoStubServer.setKeywordResponseMode(KakaoStubServer.ResponseMode.MALFORMED)
        try {
            mockMvc.get("/api/v1/boards/${host.boardId}/place-candidates") {
                bearer(host.token)
                param("query", "테스트")
            }.andExpect {
                status { isBadGateway() }
                jsonPath("$.error.code") { value("EXTERNAL_BAD_RESPONSE") }
            }
        } finally {
            kakaoStubServer.setKeywordResponseMode(KakaoStubServer.ResponseMode.SUCCESS)
        }
    }

    @Test
    fun `V2-3 검색 흐름의 소스코드에는 TMAP·ODsay 참조가 없다`() {
        // P2는 Kakao Local만 사용한다(03-phase2-place-search.md). TMAP·ODsay 어댑터는 P5·P6 몫이며
        // 지금 그런 코드 자체가 없으므로, place/카카오/외부공통기반 소스 트리에 두 이름이 등장하지
        // 않는 것으로 "검색이 TMAP·ODsay를 호출하지 않는다"는 사실을 고정한다.
        val roots = listOf(
            "src/main/kotlin/com/siheungbootcamp/teamd/domain/place",
            "src/main/kotlin/com/siheungbootcamp/teamd/infra/external",
            "src/main/kotlin/com/siheungbootcamp/teamd/global/external",
        )
        val forbidden = listOf("TMAP", "ODsay", "Odsay", "OdSay")
        for (root in roots) {
            val dir = java.io.File(root)
            assertTrue(dir.exists(), "$root 디렉터리가 있어야 검증이 의미 있음")
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val text = file.readText()
                forbidden.forEach { keyword ->
                    assertFalse(text.contains(keyword), "${file.path}에 $keyword 참조가 있으면 안 됨")
                }
            }
        }
    }

    @Test
    fun `V2-8 검색 요청 로그에 검색어 원문과 KAKAO_REST_KEY가 없다`(output: CapturedOutput) {
        val host = createBoard("로그 검사 보드", "호스트")
        val secretQuery = "민감한-검색어-12345"

        mockMvc.get("/api/v1/boards/${host.boardId}/place-candidates") {
            bearer(host.token)
            param("query", secretQuery)
        }.andExpect { status { isOk() } }

        assertFalse(output.all.contains(secretQuery), "로그에 검색어가 나타나면 안 됨")
    }

    @Test
    fun `V2-9 DELETE 삭제 권한 - 제3자 403, 제안자 204, 재삭제 204`() {
        val host = createBoard("삭제 권한 보드", "호스트")
        val member = join(host.inviteCode, "멤버")

        // 장소 생성
        val createResult = mockMvc.post("/api/v1/boards/${host.boardId}/places") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """
            {
              "name": "테스트 장소",
              "lon": 126.7,
              "lat": 37.3,
              "addressName": "서울시",
              "roadAddressName": "서울시",
              "internalCategory": "RESTAURANT",
              "provider": "KAKAO",
              "providerPlaceId": "test123",
              "providerPlaceUrl": "https://place.map.kakao.com/123",
              "source": "SEARCH_SELECT"
            }
            """.trimIndent()
        }.andExpect { status { isCreated() } }
            .andReturn().response.contentAsString

        val placeId = objectMapper.readTree(createResult).path("placeId").asText()

        // 제3자는 403
        mockMvc.delete("/api/v1/boards/${host.boardId}/places/$placeId") {
            bearer(member)
        }.andExpect { status { isForbidden() } }

        // 제안자는 204
        mockMvc.delete("/api/v1/boards/${host.boardId}/places/$placeId") {
            bearer(host.token)
        }.andExpect { status { isNoContent() } }

        // 재삭제는 204 (멱등성)
        mockMvc.delete("/api/v1/boards/${host.boardId}/places/$placeId") {
            bearer(host.token)
        }.andExpect { status { isNoContent() } }
    }

    @Test
    fun `V2-10 비허용 도메인 URL은 400 INVALID_ARGUMENT를 반환한다`() {
        val host = createBoard("URL 도메인 검증 보드", "호스트")

        mockMvc.post("/api/v1/boards/${host.boardId}/places") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """
            {
              "name": "테스트",
              "lon": 126.7,
              "lat": 37.3,
              "addressName": "서울시",
              "roadAddressName": "서울시",
              "internalCategory": "RESTAURANT",
              "provider": "KAKAO",
              "providerPlaceId": "test123",
              "providerPlaceUrl": "https://malicious.com",
              "source": "MANUAL_PIN"
            }
            """.trimIndent()
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("INVALID_ARGUMENT") }
        }
    }

    @Test
    fun `장소 목록 bbox는 명세대로 단일 파라미터이고 category와 결합된다`() {
        val host = createBoard("bbox 목록 보드", "호스트")

        // bbox 안, category 일치
        createPlace(host, name = "안쪽 식당", lon = 127.0, lat = 37.0, category = "RESTAURANT")
        // bbox 안, category 불일치
        createPlace(host, name = "안쪽 카페", lon = 127.0, lat = 37.0, category = "CAFE")
        // bbox 밖
        createPlace(host, name = "바깥 식당", lon = 130.0, lat = 40.0, category = "RESTAURANT")

        val result = mockMvc.get("/api/v1/boards/${host.boardId}/places") {
            bearer(host.token)
            param("bbox", "126.0,36.0,128.0,38.0")
            param("category", "RESTAURANT")
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val items = objectMapper.readTree(result).path("items")
        assertEquals(1, items.size(), "bbox와 category를 동시에 만족하는 장소만 반환되어야 한다")
        assertEquals("안쪽 식당", items[0].path("name").asText())
    }

    @Test
    fun `bbox 형식이 잘못되면 400 INVALID_ARGUMENT다`() {
        val host = createBoard("bbox 형식 오류 보드", "호스트")
        mockMvc.get("/api/v1/boards/${host.boardId}/places") {
            bearer(host.token)
            param("bbox", "126.0,36.0,128.0")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("INVALID_ARGUMENT") }
        }
    }

    @Test
    fun `허용되지 않은 category·sort 값은 400 INVALID_ARGUMENT다`() {
        val host = createBoard("목록 검증 보드", "호스트")
        mockMvc.get("/api/v1/boards/${host.boardId}/places") {
            bearer(host.token)
            param("category", "NOT_A_CATEGORY")
        }.andExpect { status { isBadRequest() } }

        mockMvc.get("/api/v1/boards/${host.boardId}/places") {
            bearer(host.token)
            param("sort", "POPULARITY")
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `검색 위치 파라미터는 lon lat 짝과 radius 상한을 검증한다`() {
        val host = createBoard("검색 위치 검증 보드", "호스트")

        // lon만 있고 lat이 없음
        mockMvc.get("/api/v1/boards/${host.boardId}/place-candidates") {
            bearer(host.token)
            param("query", "테스트")
            param("lon", "127.0")
        }.andExpect { status { isBadRequest() } }

        // radius가 20000 초과
        mockMvc.get("/api/v1/boards/${host.boardId}/place-candidates") {
            bearer(host.token)
            param("query", "테스트")
            param("lon", "127.0")
            param("lat", "37.0")
            param("radius", "20001")
        }.andExpect { status { isBadRequest() } }
    }

    private fun createPlace(host: CreatedBoard, name: String, lon: Double, lat: Double, category: String) {
        mockMvc.post("/api/v1/boards/${host.boardId}/places") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """
            {
              "name": "$name",
              "lon": $lon,
              "lat": $lat,
              "addressName": null,
              "roadAddressName": null,
              "internalCategory": "$category",
              "provider": null,
              "providerPlaceId": null,
              "providerPlaceUrl": null,
              "source": "MANUAL_PIN"
            }
            """.trimIndent()
        }.andExpect { status { isCreated() } }
    }

    @Test
    fun `place 조회 및 삭제 권한 검증`() {
        val boardA = createBoard("조회 권한 에이", "호스트")
        val boardB = createBoard("조회 권한 비", "호스트")

        // 장소 생성
        val createResult = mockMvc.post("/api/v1/boards/${boardA.boardId}/places") {
            bearer(boardA.token)
            contentType = MediaType.APPLICATION_JSON
            content = """
            {
              "name": "테스트 장소",
              "lon": 126.7,
              "lat": 37.3,
              "addressName": "서울시",
              "roadAddressName": "서울시",
              "internalCategory": "RESTAURANT",
              "provider": "KAKAO",
              "providerPlaceId": "test123",
              "providerPlaceUrl": "https://place.map.kakao.com/123",
              "source": "SEARCH_SELECT"
            }
            """.trimIndent()
        }.andExpect { status { isCreated() } }
            .andReturn().response.contentAsString

        val placeId = objectMapper.readTree(createResult).path("placeId").asText()

        // 다른 보드 토큰으로 조회 시 404
        mockMvc.get("/api/v1/boards/${boardA.boardId}/places/$placeId") {
            bearer(boardB.token)
        }.andExpect { status { isNotFound() } }

        // 인증 없이 조회 시 401
        mockMvc.get("/api/v1/boards/${boardA.boardId}/places/$placeId")
            .andExpect { status { isUnauthorized() } }
    }

    private fun createBoard(name: String, nickname: String): CreatedBoard {
        val body = mockMvc.post("/api/v1/boards") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name","dateRange":{"start":"2099-01-01","end":"2099-01-02"},"purpose":"테스트","hostNickname":"$nickname"}"""
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        val json = objectMapper.readTree(body)
        return CreatedBoard(json["board"]["boardId"].asText(), json["participant"]["participantToken"].asText(), json["invitation"]["inviteCode"].asText())
    }

    private fun join(inviteCode: String, nickname: String): String {
        val body = mockMvc.post("/api/v1/invitations/$inviteCode/participants") {
            contentType = MediaType.APPLICATION_JSON; content = """{"nickname":"$nickname"}"""
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        return objectMapper.readTree(body)["participantToken"].asText()
    }

    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.bearer(token: String) {
        header("Authorization", "Bearer $token")
    }

    private data class CreatedBoard(val boardId: String, val token: String, val inviteCode: String)

    companion object {
        @Container @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        private val kakaoStubServer = KakaoStubServer()

        init {
            kakaoStubServer.start()
        }

        @DynamicPropertySource
        @JvmStatic
        fun dynamicProperties(registry: DynamicPropertyRegistry) {
            registry.add("app.kakao.base-url") { kakaoStubServer.baseUrl }
        }
    }
}
