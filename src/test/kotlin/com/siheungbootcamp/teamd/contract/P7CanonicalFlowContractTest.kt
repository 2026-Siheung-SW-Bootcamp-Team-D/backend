package com.siheungbootcamp.teamd.contract

import com.siheungbootcamp.teamd.infra.external.kakao.KakaoStubServer
import org.junit.jupiter.api.Test
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
import org.springframework.test.web.servlet.put
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals

/**
 * P7 canonical 사용자 흐름 계약 테스트
 *
 * 신규 MVP에서 모든 활성 참여자가 다음 작업을 수행할 수 있어야 한다:
 * 1. 후보 보관 (제안자 아님)
 * 2. 다중 장소에 좋아요
 * 3. 현재 선택 지정/변경/해제
 * 4. 공통 영역 밖 좌표로 주변 검색
 * 5. 주변 검색 결과만으로는 Place 미생성
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = [
    "app.auth.token-pepper=test-pepper",
    "app.crypto.origin-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
    "app.board.frontend-base-url=https://example.app",
    "app.kakao.rest-key=test-kakao-key",
])
class P7CanonicalFlowContractTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcClient: JdbcClient,
) {
    @Test
    fun `제안자가 아닌 활성 참여자가 후보를 보관하면 204`() {
        val host = createBoard("후보 보관 보드", "호스트")
        val member = join(host.inviteCode, "멤버")

        // 호스트가 장소 생성
        val place = createPlace(host, "테스트 장소")

        // 멤버(제안자 아님)가 장소 삭제 → 204 (보관)
        mockMvc.delete("/api/v1/boards/${host.boardId}/places/${place.placeId}") {
            bearer(member)
        }.andExpect { status { isNoContent() } }
    }

    @Test
    fun `한 참여자가 서로 다른 두 장소에 좋아요 PUT 후 둘 다 likedByMe=true`() {
        val host = createBoard("좋아요 다중 보드", "호스트")

        // 두 장소 생성
        val place1 = createPlace(host, "식당 1")
        val place2 = createPlace(host, "식당 2")

        // 첫 번째 장소에 좋아요
        mockMvc.put("/api/v1/boards/${host.boardId}/places/${place1.placeId}/like") {
            bearer(host.token)
        }.andExpect { status { isOk() } }

        // 두 번째 장소에 좋아요
        mockMvc.put("/api/v1/boards/${host.boardId}/places/${place2.placeId}/like") {
            bearer(host.token)
        }.andExpect { status { isOk() } }

        // 첫 번째 장소 조회 - likedByMe=true
        val place1Json = mockMvc.get("/api/v1/boards/${host.boardId}/places/${place1.placeId}") {
            bearer(host.token)
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertEquals(true, objectMapper.readTree(place1Json).path("likedByMe").asBoolean(), "place1에 좋아요 했으므로 likedByMe=true")

        // 두 번째 장소 조회 - likedByMe=true
        val place2Json = mockMvc.get("/api/v1/boards/${host.boardId}/places/${place2.placeId}") {
            bearer(host.token)
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertEquals(true, objectMapper.readTree(place2Json).path("likedByMe").asBoolean(), "place2에 좋아요 했으므로 likedByMe=true")
    }

    @Test
    fun `임의 참여자가 현재 선택 장소를 지정·변경·해제`() {
        val host = createBoard("선택 변경 보드", "호스트")

        val place1 = createPlace(host, "선택할 장소 1")
        val place2 = createPlace(host, "선택할 장소 2")

        // 첫 번째 장소 선택
        mockMvc.put("/api/v1/boards/${host.boardId}/selected-place") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"placeId": "${place1.placeId}"}"""
        }.andExpect { status { isOk() } }

        var boardJson = mockMvc.get("/api/v1/boards/${host.boardId}") {
            bearer(host.token)
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertEquals(place1.placeId, objectMapper.readTree(boardJson).path("selectedPlaceId").asText(), "place1이 선택되어야 함")

        // 두 번째 장소로 변경
        mockMvc.put("/api/v1/boards/${host.boardId}/selected-place") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"placeId": "${place2.placeId}"}"""
        }.andExpect { status { isOk() } }

        boardJson = mockMvc.get("/api/v1/boards/${host.boardId}") {
            bearer(host.token)
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertEquals(place2.placeId, objectMapper.readTree(boardJson).path("selectedPlaceId").asText(), "place2로 변경되어야 함")

        // 선택 해제
        mockMvc.delete("/api/v1/boards/${host.boardId}/selected-place") {
            bearer(host.token)
        }.andExpect { status { isNoContent() } }

        boardJson = mockMvc.get("/api/v1/boards/${host.boardId}") {
            bearer(host.token)
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        val selectedPlaceId = objectMapper.readTree(boardJson).path("selectedPlaceId").asText()
        assertEquals("", selectedPlaceId, "선택이 해제되어야 함")
    }

    @Test
    fun `공통 영역 밖 좌표로 주변 카페 검색 성공`() {
        val host = createBoard("주변 검색 보드", "호스트")

        // 공통 영역 밖 좌표에서 주변 검색
        val searchResult = mockMvc.get("/api/v1/boards/${host.boardId}/search/nearby-places") {
            bearer(host.token)
            param("lon", "129.0")
            param("lat", "35.1")
            param("category", "CAFE")
            param("radius", "1000")
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val items = objectMapper.readTree(searchResult).path("items")
        assertEquals(true, items.isArray, "주변 검색 결과는 배열이어야 함")
    }

    @Test
    fun `주변 검색만으로 place 테이블 행 수가 증가하지 않음`() {
        val host = createBoard("주변 검색 미저장 보드", "호스트")

        val before = jdbcClient.sql("select count(*) as cnt from place where board_id=(select id from board where public_id=:boardId)")
            .param("boardId", host.boardId)
            .query(Int::class.java)
            .single()

        // 주변 검색 실행
        mockMvc.get("/api/v1/boards/${host.boardId}/search/nearby-places") {
            bearer(host.token)
            param("lon", "129.0")
            param("lat", "35.1")
            param("category", "CAFE")
            param("radius", "1000")
        }.andExpect { status { isOk() } }

        val after = jdbcClient.sql("select count(*) as cnt from place where board_id=(select id from board where public_id=:boardId)")
            .param("boardId", host.boardId)
            .query(Int::class.java)
            .single()

        assertEquals(before, after, "주변 검색만으로는 place가 생성되지 않아야 함")
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
            contentType = MediaType.APPLICATION_JSON
            content = """{"nickname":"$nickname"}"""
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        return objectMapper.readTree(body)["participantToken"].asText()
    }

    private fun createPlace(host: CreatedBoard, name: String): CreatedPlace {
        val result = mockMvc.post("/api/v1/boards/${host.boardId}/places") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """
            {
              "name": "$name",
              "lon": 126.7,
              "lat": 37.3,
              "addressName": null,
              "roadAddressName": null,
              "internalCategory": "RESTAURANT",
              "provider": null,
              "providerPlaceId": null,
              "providerPlaceUrl": null,
              "source": "MANUAL_PIN"
            }
            """.trimIndent()
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        val placeId = objectMapper.readTree(result).path("placeId").asText()
        return CreatedPlace(placeId)
    }

    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.bearer(token: String) {
        header("Authorization", "Bearer $token")
    }

    private data class CreatedBoard(val boardId: String, val token: String, val inviteCode: String)
    private data class CreatedPlace(val placeId: String)

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
