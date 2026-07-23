package com.siheungbootcamp.teamd.domain.comment

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
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P3 댓글 계약 테스트 (V3-1, V3-2, V3-3)
 *
 * 엔드포인트 16-19: 댓글 조회, 작성, 수정, 삭제
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = [
    "app.auth.token-pepper=test-pepper",
    "app.crypto.origin-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
    "app.board.frontend-base-url=https://example.app",
    "app.kakao.rest-key=test-kakao-key",
])
class P3CommentContractTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcClient: JdbcClient,
) {
    @Test
    fun `V3-1 댓글 작성·조회·수정·삭제 E2E 흐름이 성공한다`() {
        val host = createBoard("댓글 E2E 보드", "호스트")
        val member = join(host.inviteCode, "멤버")
        val place = createPlace(host, "테스트 장소")

        // 댓글 작성
        val commentBody = mockMvc.post("/api/v1/boards/${host.boardId}/places/${place.placeId}/comments") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":"여기 웨이팅이 길 수 있어요."}"""
        }.andExpect { status { isCreated() } }
            .andReturn().response.contentAsString
        val commentId = objectMapper.readTree(commentBody).path("commentId").asText()
        assertEquals("여기 웨이팅이 길 수 있어요.", objectMapper.readTree(commentBody).path("content").asText())

        // 댓글 목록 조회
        val listBody = mockMvc.get("/api/v1/boards/${host.boardId}/places/${place.placeId}/comments") {
            bearer(host.token)
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertEquals(1, objectMapper.readTree(listBody).path("items").size(), "방금 작성한 댓글이 목록에 있어야 함")

        // 다른 참여자의 댓글
        mockMvc.post("/api/v1/boards/${host.boardId}/places/${place.placeId}/comments") {
            bearer(member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":"멤버의 댓글"}"""
        }.andExpect { status { isCreated() } }

        // 호스트가 자신의 댓글 수정
        mockMvc.patch("/api/v1/boards/${host.boardId}/places/${place.placeId}/comments/$commentId") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":"수정된 댓글"}"""
        }.andExpect { status { isOk() } }

        // 수정 확인
        val afterUpdateBody = mockMvc.get("/api/v1/boards/${host.boardId}/places/${place.placeId}/comments") {
            bearer(host.token)
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        val updatedComment = objectMapper.readTree(afterUpdateBody).path("items").find { it.path("commentId").asText() == commentId }
        assertEquals("수정된 댓글", updatedComment?.path("content")?.asText())

        // 호스트가 자신의 댓글 삭제
        mockMvc.delete("/api/v1/boards/${host.boardId}/places/${place.placeId}/comments/$commentId") {
            bearer(host.token)
        }.andExpect { status { isNoContent() } }

        // 삭제 확인
        val afterDeleteBody = mockMvc.get("/api/v1/boards/${host.boardId}/places/${place.placeId}/comments") {
            bearer(host.token)
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertEquals(1, objectMapper.readTree(afterDeleteBody).path("items").size(), "호스트의 댓글 삭제 후 멤버의 댓글만 남아야 함")
    }

    @Test
    fun `V3-2 댓글 권한 - 타인 수정은 403, 타인 삭제는 403, 작성자 삭제는 204`() {
        val host = createBoard("댓글 권한 보드", "호스트")
        val member = join(host.inviteCode, "멤버")
        val place = createPlace(host, "테스트 장소")

        // 호스트가 댓글 작성
        val commentBody = mockMvc.post("/api/v1/boards/${host.boardId}/places/${place.placeId}/comments") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":"호스트의 댓글"}"""
        }.andExpect { status { isCreated() } }
            .andReturn().response.contentAsString

        val commentId = objectMapper.readTree(commentBody).path("commentId").asText()

        // 멤버(타인)가 수정 시도 → 403
        mockMvc.patch("/api/v1/boards/${host.boardId}/places/${place.placeId}/comments/$commentId") {
            bearer(member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":"수정 시도"}"""
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value("FORBIDDEN") }
        }

        // 멤버(타인)가 삭제 시도 → 403 (작성자만 삭제 가능)
        mockMvc.delete("/api/v1/boards/${host.boardId}/places/${place.placeId}/comments/$commentId") {
            bearer(member)
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value("FORBIDDEN") }
        }

        // 호스트(작성자)가 자신의 댓글 삭제 → 204
        mockMvc.delete("/api/v1/boards/${host.boardId}/places/${place.placeId}/comments/$commentId") {
            bearer(host.token)
        }.andExpect { status { isNoContent() } }

        // 작성자 삭제 테스트
        val comment2Body = mockMvc.post("/api/v1/boards/${host.boardId}/places/${place.placeId}/comments") {
            bearer(member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":"멤버의 댓글"}"""
        }.andExpect { status { isCreated() } }
            .andReturn().response.contentAsString

        val comment2Id = objectMapper.readTree(comment2Body).path("commentId").asText()

        // 멤버(작성자)가 삭제 → 204
        mockMvc.delete("/api/v1/boards/${host.boardId}/places/${place.placeId}/comments/$comment2Id") {
            bearer(member)
        }.andExpect { status { isNoContent() } }
    }

    @Test
    fun `V3-3 삭제된 댓글이 목록에서 제외되고 count가 감소한다`() {
        val host = createBoard("댓글 soft delete 보드", "호스트")
        val place = createPlace(host, "테스트 장소")

        // 3개 댓글 작성
        repeat(3) {
            mockMvc.post("/api/v1/boards/${host.boardId}/places/${place.placeId}/comments") {
                bearer(host.token)
                contentType = MediaType.APPLICATION_JSON
                content = """{"content":"댓글 $it"}"""
            }.andExpect { status { isCreated() } }
        }

        // 초기 목록: 3개
        val initialList = mockMvc.get("/api/v1/boards/${host.boardId}/places/${place.placeId}/comments") {
            bearer(host.token)
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val initialCount = objectMapper.readTree(initialList).path("items").size()
        assertEquals(3, initialCount, "초기에 3개의 댓글이 있어야 함")

        // 첫 번째 댓글 ID
        val commentId = objectMapper.readTree(initialList).path("items")[0].path("commentId").asText()

        // 삭제
        mockMvc.delete("/api/v1/boards/${host.boardId}/places/${place.placeId}/comments/$commentId") {
            bearer(host.token)
        }.andExpect { status { isNoContent() } }

        // 삭제 후 목록: 2개
        val afterDeleteList = mockMvc.get("/api/v1/boards/${host.boardId}/places/${place.placeId}/comments") {
            bearer(host.token)
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val afterDeleteCount = objectMapper.readTree(afterDeleteList).path("items").size()
        assertEquals(2, afterDeleteCount, "삭제 후 2개의 댓글만 있어야 함 (soft delete)")
    }

    @Test
    fun `엔드포인트 16 - GET 댓글 목록은 페이지네이션을 지원한다`() {
        val host = createBoard("댓글 페이지네이션 보드", "호스트")
        val place = createPlace(host, "테스트 장소")

        // 25개 댓글 작성. 참여자당 20회/분 rate limit(V3-1 문서, RateLimitInterceptor)에
        // 걸리지 않도록 여러 참여자로 나눠서 작성한다.
        val authors = listOf(host.token, join(host.inviteCode, "멤버1"), join(host.inviteCode, "멤버2"))
        repeat(25) {
            mockMvc.post("/api/v1/boards/${host.boardId}/places/${place.placeId}/comments") {
                bearer(authors[it % authors.size])
                contentType = MediaType.APPLICATION_JSON
                content = """{"content":"댓글 $it"}"""
            }.andExpect { status { isCreated() } }
        }

        // 첫 페이지 (기본 20개)
        val page1 = mockMvc.get("/api/v1/boards/${host.boardId}/places/${place.placeId}/comments") {
            bearer(host.token)
            param("page", "1")
            param("size", "20")
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val page1Data = objectMapper.readTree(page1)
        assertEquals(20, page1Data.path("items").size())
        assertEquals(1, page1Data.path("page").path("number").asInt())
        assertEquals(2, page1Data.path("page").path("totalPages").asInt())

        // 두 번째 페이지 (5개)
        val page2 = mockMvc.get("/api/v1/boards/${host.boardId}/places/${place.placeId}/comments") {
            bearer(host.token)
            param("page", "2")
            param("size", "20")
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val page2Data = objectMapper.readTree(page2)
        assertEquals(5, page2Data.path("items").size())
        assertEquals(2, page2Data.path("page").path("number").asInt())
    }

    @Test
    fun `엔드포인트 17 - POST 댓글 생성은 201을 반환한다`() {
        val host = createBoard("댓글 생성 보드", "호스트")
        val place = createPlace(host, "테스트 장소")

        // 정상 작성 → 201
        mockMvc.post("/api/v1/boards/${host.boardId}/places/${place.placeId}/comments") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":"첫 번째 댓글"}"""
        }.andExpect { status { isCreated() } }
    }

    @Test
    fun `엔드포인트 18 - PATCH 댓글 수정은 작성자만 가능하다`() {
        val host = createBoard("댓글 수정 보드", "호스트")
        val member = join(host.inviteCode, "멤버")
        val place = createPlace(host, "테스트 장소")

        // 멤버가 댓글 작성
        val commentBody = mockMvc.post("/api/v1/boards/${host.boardId}/places/${place.placeId}/comments") {
            bearer(member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":"원본 댓글"}"""
        }.andExpect { status { isCreated() } }
            .andReturn().response.contentAsString

        val commentId = objectMapper.readTree(commentBody).path("commentId").asText()

        // 작성자가 수정 → 200
        mockMvc.patch("/api/v1/boards/${host.boardId}/places/${place.placeId}/comments/$commentId") {
            bearer(member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":"수정된 댓글"}"""
        }.andExpect { status { isOk() } }

        // 수정 확인
        val listBody = mockMvc.get("/api/v1/boards/${host.boardId}/places/${place.placeId}/comments") {
            bearer(member)
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val updatedComment = objectMapper.readTree(listBody).path("items")[0]
        assertEquals("수정된 댓글", updatedComment.path("content").asText())
    }

    @Test
    fun `엔드포인트 19 - DELETE 댓글 삭제는 멱등성을 보장한다`() {
        val host = createBoard("댓글 삭제 멱등성 보드", "호스트")
        val place = createPlace(host, "테스트 장소")

        val commentBody = mockMvc.post("/api/v1/boards/${host.boardId}/places/${place.placeId}/comments") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":"삭제할 댓글"}"""
        }.andExpect { status { isCreated() } }
            .andReturn().response.contentAsString

        val commentId = objectMapper.readTree(commentBody).path("commentId").asText()

        // 첫 번째 삭제 → 204
        mockMvc.delete("/api/v1/boards/${host.boardId}/places/${place.placeId}/comments/$commentId") {
            bearer(host.token)
        }.andExpect { status { isNoContent() } }

        // 재삭제 → 204 (멱등성)
        mockMvc.delete("/api/v1/boards/${host.boardId}/places/${place.placeId}/comments/$commentId") {
            bearer(host.token)
        }.andExpect { status { isNoContent() } }
    }

    // Helper methods

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

    private fun createPlace(host: CreatedBoard, name: String): PlaceInfo {
        val body = mockMvc.post("/api/v1/boards/${host.boardId}/places") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """
            {
              "name": "$name",
              "lon": 126.7,
              "lat": 37.3,
              "addressName": "서울시",
              "roadAddressName": "서울시",
              "internalCategory": "RESTAURANT",
              "provider": null,
              "providerPlaceId": null,
              "providerPlaceUrl": null,
              "source": "MANUAL_PIN"
            }
            """.trimIndent()
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        val json = objectMapper.readTree(body)
        return PlaceInfo(json["placeId"].asText())
    }

    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.bearer(token: String) {
        header("Authorization", "Bearer $token")
    }

    private data class CreatedBoard(val boardId: String, val token: String, val inviteCode: String)
    private data class PlaceInfo(val placeId: String)

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun dynamicProperties(registry: DynamicPropertyRegistry) {
            // Additional properties if needed
        }
    }
}
