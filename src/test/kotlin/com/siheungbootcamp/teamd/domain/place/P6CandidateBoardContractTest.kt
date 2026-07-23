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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = [
    "app.auth.token-pepper=test-pepper",
    "app.crypto.origin-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
    "app.board.frontend-base-url=https://example.app",
    "app.kakao.rest-key=test-kakao-key",
])
class P6CandidateBoardContractTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcClient: JdbcClient,
) {
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

    private fun createBoard(name: String, hostNickname: String): TestBoardContext {
        val createResult = mockMvc.post("/api/v1/boards") {
            contentType = MediaType.APPLICATION_JSON
            content = """
            {
              "name": "$name",
              "dateRange": {
                "start": "2026-08-01",
                "end": "2026-08-31"
              },
              "purpose": "테스트",
              "hostNickname": "$hostNickname"
            }
            """.trimIndent()
        }.andExpect { status { isCreated() } }
            .andReturn().response.contentAsString

        val response = objectMapper.readTree(createResult)
        val boardId = response.path("board").path("boardId").asText()
        val participantId = response.path("participant").path("participantId").asText()
        val token = response.path("participant").path("participantToken").asText()
        val inviteCode = response.path("invitation").path("inviteCode").asText()

        return TestBoardContext(boardId, participantId, token, inviteCode)
    }

    private fun joinBoard(inviteCode: String, nickname: String): TestBoardContext {
        val createResult = mockMvc.post("/api/v1/invitations/$inviteCode/participants") {
            contentType = MediaType.APPLICATION_JSON
            content = """
            {
              "nickname": "$nickname"
            }
            """.trimIndent()
        }.andExpect { status { isCreated() } }
            .andReturn().response.contentAsString

        val response = objectMapper.readTree(createResult)
        val boardId = response.path("boardId").asText()
        val participantId = response.path("participantId").asText()
        val token = response.path("participantToken").asText()

        return TestBoardContext(boardId, participantId, token, "")
    }

    private fun createPlace(boardId: String, token: String, name: String): String {
        val createResult = mockMvc.post("/api/v1/boards/$boardId/places") {
            bearer(token)
            contentType = MediaType.APPLICATION_JSON
            content = """
            {
              "name": "$name",
              "lon": 126.5,
              "lat": 37.5,
              "addressName": "서울시 강남구",
              "roadAddressName": "서울시 강남구 테헤란로",
              "internalCategory": "RESTAURANT",
              "provider": "KAKAO",
              "providerPlaceId": "12345",
              "providerPlaceUrl": "https://place.map.kakao.com/12345",
              "source": "MANUAL_PIN"
            }
            """.trimIndent()
        }.andExpect { status { isCreated() } }
            .andReturn().response.contentAsString

        val response = objectMapper.readTree(createResult)
        return response.path("placeId").asText()
    }

    private fun getPlace(boardId: String, placeId: String, token: String): String {
        return mockMvc.get("/api/v1/boards/$boardId/places/$placeId") {
            bearer(token)
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
    }

    @Test
    fun `한 참여자는 서로 다른 두 장소에 좋아요할 수 있고 같은 요청은 멱등적이다`() {
        val host = createBoard("좋아요 보드", "호스트")
        val placeA = createPlace(host.boardId, host.token, "장소 A")
        val placeB = createPlace(host.boardId, host.token, "장소 B")

        mockMvc.put("/api/v1/boards/${host.boardId}/places/$placeA/likes/me") {
            bearer(host.token)
        }.andExpect { status { isNoContent() } }

        mockMvc.put("/api/v1/boards/${host.boardId}/places/$placeA/likes/me") {
            bearer(host.token)
        }.andExpect { status { isNoContent() } }

        mockMvc.put("/api/v1/boards/${host.boardId}/places/$placeB/likes/me") {
            bearer(host.token)
        }.andExpect { status { isNoContent() } }

        val placeAResult = getPlace(host.boardId, placeA, host.token)
        val placeAJson = objectMapper.readTree(placeAResult)
        assertEquals(1, placeAJson.path("likeCount").asInt(), "장소 A 좋아요 수")
        assertTrue(placeAJson.path("likedByMe").asBoolean(), "장소 A 내가 좋아요함")

        val placeBResult = getPlace(host.boardId, placeB, host.token)
        val placeBJson = objectMapper.readTree(placeBResult)
        assertEquals(1, placeBJson.path("likeCount").asInt(), "장소 B 좋아요 수")
        assertTrue(placeBJson.path("likedByMe").asBoolean(), "장소 B 내가 좋아요함")
    }

    @Test
    fun `일반 참여자가 선택 장소를 바꾸고 해제할 수 있다`() {
        val host = createBoard("선택 보드", "호스트")
        val member = joinBoard(host.inviteCode, "멤버1")
        val otherMember = joinBoard(host.inviteCode, "멤버2")
        val placeA = createPlace(host.boardId, host.token, "장소 A")
        val placeB = createPlace(host.boardId, host.token, "장소 B")

        mockMvc.put("/api/v1/boards/${host.boardId}/selected-place") {
            bearer(member.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"placeId": "$placeA"}"""
        }.andExpect { status { isOk() } }

        mockMvc.put("/api/v1/boards/${host.boardId}/selected-place") {
            bearer(otherMember.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"placeId": "$placeB"}"""
        }.andExpect { status { isOk() } }

        val boardResult = mockMvc.get("/api/v1/boards/${host.boardId}") {
            bearer(otherMember.token)
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val boardJson = objectMapper.readTree(boardResult)
        assertEquals(placeB, boardJson.path("selectedPlaceId").asText(), "선택된 장소")
        assertEquals(otherMember.participantId, boardJson.path("selectedByParticipantId").asText(), "선택한 참여자")

        mockMvc.delete("/api/v1/boards/${host.boardId}/selected-place") {
            bearer(member.token)
        }.andExpect { status { isNoContent() } }

        val clearedResult = mockMvc.get("/api/v1/boards/${host.boardId}") {
            bearer(member.token)
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val clearedJson = objectMapper.readTree(clearedResult)
        assertTrue(clearedJson.path("selectedPlaceId").asText().isEmpty(),
            "선택된 장소가 없어야 함")
    }

    @Test
    fun `일반 참여자가 초대 코드를 다시 조회할 수 있다`() {
        val host = createBoard("초대 보드", "호스트")
        val member = joinBoard(host.inviteCode, "멤버")

        mockMvc.get("/api/v1/boards/${host.boardId}/invitation") {
            bearer(member.token)
        }.andExpect { status { isOk() } }
            .andExpect { jsonPath("$.inviteCode") { value(host.inviteCode) } }
    }

    data class TestBoardContext(
        val boardId: String,
        val participantId: String,
        val token: String,
        val inviteCode: String,
    )

    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.bearer(token: String) {
        header("Authorization", "Bearer $token")
    }
}
