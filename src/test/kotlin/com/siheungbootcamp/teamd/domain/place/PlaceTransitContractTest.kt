package com.siheungbootcamp.teamd.domain.place

import com.siheungbootcamp.teamd.infra.external.tmap.TmapStubServer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = [
    "app.auth.token-pepper=test-pepper",
    "app.crypto.origin-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
    "app.board.frontend-base-url=https://example.app",
    "app.tmap.app-key=test-tmap-key",
])
class PlaceTransitContractTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
) {
    @BeforeEach
    fun resetStub() {
        tmapStubServer.responseMode = TmapStubServer.ResponseMode.SUCCESS
        tmapStubServer.queueResponses()
        tmapStubServer.resetCount()
    }

    @Test
    fun `장소별 참여자 이동시간은 캐시를 사용하고 출발지 없는 참여자는 ORIGIN_REQUIRED다`() {
        val host = createBoard("이동시간 보드", "호스트")
        val member = join(host, "멤버")
        setOrigin(host, "호스트 출발지", 126.97, 37.55)
        val placeId = createPlace(host, "만남 장소", 126.98, 37.56)

        val first = requestTransitTimes(host, placeId)
        assertEquals(1, tmapStubServer.requestCount(), "출발지가 있는 참여자만 외부 호출해야 한다")

        val firstItems = first.path("items")
        assertEquals(2, firstItems.size())
        assertEquals("READY", firstItems[0].path("status").asText())
        assertEquals(32, firstItems[0].path("totalMinutes").asInt())
        assertEquals(1, firstItems[0].path("transferCount").asInt())
        assertEquals(7, firstItems[0].path("totalWalkMinutes").asInt())
        assertEquals("WALK", firstItems[0].path("route").path("legs")[0].path("mode").asText())
        assertEquals("수도권2호선", firstItems[0].path("route").path("legs")[1].path("routeName").asText())
        assertTrue(firstItems[0].path("route").path("path").size() in 2..300)
        assertEquals("ORIGIN_REQUIRED", firstItems[1].path("status").asText())
        assertFalse(firstItems[0].has("origin"))
        assertFalse(firstItems[0].has("label"))
        assertFalse(firstItems[0].has("lon"))
        assertEquals(member.participantId, firstItems[1].path("participantId").asText())

        val second = requestTransitTimes(host, placeId)
        assertEquals(1, tmapStubServer.requestCount(), "같은 출발지와 도착지는 로컬 캐시를 재사용해야 한다")
        assertEquals("READY", second.path("items")[0].path("status").asText())
        assertEquals("WALK", second.path("items")[0].path("route").path("legs")[0].path("mode").asText())
        assertTrue(second.path("items")[0].path("route").path("path").size() in 2..300)
        assertEquals(127.0, second.path("items")[0].path("route").path("path")[0].path("lon").asDouble())
    }

    @Test
    fun `한 참여자 외부 실패가 전체 응답을 깨뜨리지 않는다`() {
        val host = createBoard("부분 실패 보드", "호스트")
        val memberNoOrigin = join(host, "출발지없음")
        val memberFail = join(host, "실패참여자")
        setOrigin(host, "호스트 출발지", 126.97, 37.55)
        setOrigin(memberFail, "실패 참여자 출발지", 126.99, 37.57)
        val placeId = createPlace(host, "만남 장소", 126.98, 37.56)

        tmapStubServer.queueResponses(
            TmapStubServer.ResponseMode.SUCCESS,
            TmapStubServer.ResponseMode.SERVER_ERROR,
        )

        val response = requestTransitTimes(host, placeId)
        val items = response.path("items")

        assertEquals(3, items.size())
        assertEquals("READY", items[0].path("status").asText())
        assertEquals("ORIGIN_REQUIRED", items[1].path("status").asText())
        assertEquals("FAILED", items[2].path("status").asText())
        assertEquals(2, tmapStubServer.requestCount(), "출발지가 있는 참여자 두 명만 외부 호출해야 한다")
    }

    @Test
    fun `다른 보드 토큰이나 보관된 장소로는 이동시간을 조회할 수 없다`() {
        val boardA = createBoard("보드A", "호스트A")
        val boardB = createBoard("보드B", "호스트B")
        setOrigin(boardA, "호스트A 출발지", 126.97, 37.55)
        val placeId = createPlace(boardA, "A 장소", 126.98, 37.56)

        mockMvc.post("/api/v1/boards/${boardA.boardId}/places/$placeId/transit-times") {
            bearer(boardB.token)
        }.andExpect { status { isNotFound() } }

        mockMvc.delete("/api/v1/boards/${boardA.boardId}/places/$placeId") {
            bearer(boardA.token)
        }.andExpect { status { isNoContent() } }

        mockMvc.post("/api/v1/boards/${boardA.boardId}/places/$placeId/transit-times") {
            bearer(boardA.token)
        }.andExpect { status { isNotFound() } }
    }

    private fun requestTransitTimes(participant: ParticipantSession, placeId: String) =
        objectMapper.readTree(
            mockMvc.post("/api/v1/boards/${participant.boardId}/places/$placeId/transit-times") {
                bearer(participant.token)
                contentType = MediaType.APPLICATION_JSON
                content = "{}"
            }.andExpect { status { isOk() } }
                .andReturn().response.contentAsString
        )

    private fun createBoard(name: String, nickname: String): ParticipantSession {
        val response = mockMvc.post("/api/v1/boards") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name","purpose":"테스트","creatorNickname":"$nickname"}"""
        }.andExpect { status { isCreated() } }
            .andReturn().response.contentAsString
        val json = objectMapper.readTree(response)
        return ParticipantSession(
            boardId = json.path("board").path("boardId").asText(),
            token = json.path("participantToken").asText(),
            inviteCode = json.path("invitation").path("inviteCode").asText(),
            participantId = json.path("creatorParticipant").path("participantId").asText(),
        )
    }

    private fun join(host: ParticipantSession, nickname: String): ParticipantSession {
        val response = mockMvc.post("/api/v1/invitations/${host.inviteCode}/participants") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"nickname":"$nickname"}"""
        }.andExpect { status { isCreated() } }
            .andReturn().response.contentAsString
        val json = objectMapper.readTree(response)
        return ParticipantSession(
            boardId = host.boardId,
            token = json.path("participantToken").asText(),
            inviteCode = host.inviteCode,
            participantId = json.path("participantId").asText(),
        )
    }

    private fun setOrigin(participant: ParticipantSession, label: String, lon: Double, lat: Double) {
        mockMvc.patch("/api/v1/boards/${participant.boardId}/participants/me") {
            bearer(participant.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"origin":{"label":"$label","lon":$lon,"lat":$lat,"source":"MANUAL_PIN"}}"""
        }.andExpect { status { isOk() } }
    }

    private fun createPlace(host: ParticipantSession, name: String, lon: Double, lat: Double): String {
        val response = mockMvc.post("/api/v1/boards/${host.boardId}/places") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name","category":"RESTAURANT","roadAddress":"서울","jibunAddress":"서울","location":{"lon":$lon,"lat":$lat},"source":{"sourceProvider":"MANUAL","providerPlaceId":null,"sourceUrl":null,"inputMethod":"MANUAL_PIN"}}"""
        }.andExpect { status { isCreated() } }
            .andReturn().response.contentAsString
        return objectMapper.readTree(response).path("placeId").asText()
    }

    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.bearer(token: String) {
        header("Authorization", "Bearer $token")
    }

    private data class ParticipantSession(
        val boardId: String,
        val token: String,
        val inviteCode: String,
        val participantId: String,
    )

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        private val tmapStubServer = TmapStubServer()

        init {
            tmapStubServer.start()
        }

        @DynamicPropertySource
        @JvmStatic
        fun dynamicProperties(registry: DynamicPropertyRegistry) {
            registry.add("app.tmap.base-url") { tmapStubServer.baseUrl }
        }
    }
}
