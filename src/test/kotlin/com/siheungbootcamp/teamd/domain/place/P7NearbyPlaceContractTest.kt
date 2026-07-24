package com.siheungbootcamp.teamd.domain.place

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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P7 주변 장소 검색 계약 테스트 (Task 3)
 *
 * /search/nearby-places 엔드포인트:
 * - 공통 영역 밖 좌표도 검색 가능
 * - 검색 결과는 place 테이블에 저장하지 않음
 * - 입력 검증: lon, lat, radius, category/q 조합
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = [
    "app.auth.token-pepper=test-pepper",
    "app.crypto.origin-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
    "app.board.frontend-base-url=https://example.app",
    "app.kakao.rest-key=test-kakao-key",
])
class P7NearbyPlaceContractTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcClient: JdbcClient,
) {
    @Test
    fun `입력 검증 - lon 또는 lat 누락시 400 INVALID_ARGUMENT`() {
        val host = createBoard("주변검색 보드", "호스트")

        // lon만 있고 lat 없음
        mockMvc.get("/api/v1/boards/${host.boardId}/search/nearby-places") {
            bearer(host.token)
            param("lon", "127.0")
            param("category", "CAFE")
        }.andExpect { status { isBadRequest() } }

        // lat만 있고 lon 없음
        mockMvc.get("/api/v1/boards/${host.boardId}/search/nearby-places") {
            bearer(host.token)
            param("lat", "37.0")
            param("category", "CAFE")
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `입력 검증 - q와 category 동시 전달시 400 INVALID_ARGUMENT`() {
        val host = createBoard("주변검색 보드", "호스트")

        mockMvc.get("/api/v1/boards/${host.boardId}/search/nearby-places") {
            bearer(host.token)
            param("lon", "127.0")
            param("lat", "37.0")
            param("q", "카페")
            param("category", "CAFE")
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `입력 검증 - q와 category 모두 누락시 400 INVALID_ARGUMENT`() {
        val host = createBoard("주변검색 보드", "호스트")

        mockMvc.get("/api/v1/boards/${host.boardId}/search/nearby-places") {
            bearer(host.token)
            param("lon", "127.0")
            param("lat", "37.0")
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `입력 검증 - q 공백 제거 후 2자 미만시 400 INVALID_ARGUMENT`() {
        val host = createBoard("주변검색 보드", "호스트")

        mockMvc.get("/api/v1/boards/${host.boardId}/search/nearby-places") {
            bearer(host.token)
            param("lon", "127.0")
            param("lat", "37.0")
            param("q", "a")
        }.andExpect { status { isBadRequest() } }

        mockMvc.get("/api/v1/boards/${host.boardId}/search/nearby-places") {
            bearer(host.token)
            param("lon", "127.0")
            param("lat", "37.0")
            param("q", "   ")
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `입력 검증 - radius 범위 100-5000 검증`() {
        val host = createBoard("주변검색 보드", "호스트")

        // radius < 100
        mockMvc.get("/api/v1/boards/${host.boardId}/search/nearby-places") {
            bearer(host.token)
            param("lon", "127.0")
            param("lat", "37.0")
            param("category", "CAFE")
            param("radius", "99")
        }.andExpect { status { isBadRequest() } }

        // radius > 5000
        mockMvc.get("/api/v1/boards/${host.boardId}/search/nearby-places") {
            bearer(host.token)
            param("lon", "127.0")
            param("lat", "37.0")
            param("category", "CAFE")
            param("radius", "5001")
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `입력 검증 - 지원하지 않는 category는 400 INVALID_ARGUMENT`() {
        val host = createBoard("주변검색 보드", "호스트")

        mockMvc.get("/api/v1/boards/${host.boardId}/search/nearby-places") {
            bearer(host.token)
            param("lon", "127.0")
            param("lat", "37.0")
            param("category", "INVALID_CATEGORY")
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `공통 영역 밖 좌표로 주변 카페 검색 성공`() {
        val host = createBoard("주변검색 보드", "호스트")

        val result = mockMvc.get("/api/v1/boards/${host.boardId}/search/nearby-places") {
            bearer(host.token)
            param("lon", "129.0")
            param("lat", "35.1")
            param("category", "CAFE")
            param("radius", "1000")
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        // 결과는 배열 형태
        assertTrue(result.contains("\"items\""), "결과에 items 배열 필수")
    }

    @Test
    fun `주변 검색만으로 place 테이블 행 수가 증가하지 않음`() {
        val host = createBoard("주변검색 미저장 보드", "호스트")

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
        return CreatedBoard(json["board"]["boardId"].asText(), json["participant"]["participantToken"].asText())
    }

    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.bearer(token: String) {
        header("Authorization", "Bearer $token")
    }

    private data class CreatedBoard(val boardId: String, val token: String)

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
