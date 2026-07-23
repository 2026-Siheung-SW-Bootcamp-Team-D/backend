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

    @Test
    fun `장소 목록은 여러 참여자의 좋아요 수와 내 좋아요 여부를 정확히 표시한다`() {
        val host = createBoard("목록 좋아요 보드", "호스트")
        val member1 = joinBoard(host.inviteCode, "멤버1")
        val member2 = joinBoard(host.inviteCode, "멤버2")
        val placeA = createPlace(host.boardId, host.token, "장소 A")
        val placeB = createPlace(host.boardId, host.token, "장소 B")

        // 호스트와 멤버1이 장소 A를 좋아함
        mockMvc.put("/api/v1/boards/${host.boardId}/places/$placeA/likes/me") {
            bearer(host.token)
        }.andExpect { status { isNoContent() } }

        mockMvc.put("/api/v1/boards/${host.boardId}/places/$placeA/likes/me") {
            bearer(member1.token)
        }.andExpect { status { isNoContent() } }

        // 호스트만 장소 B를 좋아함
        mockMvc.put("/api/v1/boards/${host.boardId}/places/$placeB/likes/me") {
            bearer(host.token)
        }.andExpect { status { isNoContent() } }

        // 호스트 목록 조회: placeA 2개, placeB 1개
        val hostListResult = mockMvc.get("/api/v1/boards/${host.boardId}/places") {
            bearer(host.token)
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val hostListJson = objectMapper.readTree(hostListResult)
        val hostItems = hostListJson.path("items")
        val hostPlaceA = hostItems.find { it.path("placeId").asText() == placeA }!!
        val hostPlaceB = hostItems.find { it.path("placeId").asText() == placeB }!!

        assertEquals(2, hostPlaceA.path("likeCount").asInt(), "호스트 보기: 장소 A 좋아요 2개")
        assertTrue(hostPlaceA.path("likedByMe").asBoolean(), "호스트 보기: 장소 A 내가 좋아요")
        assertEquals(1, hostPlaceB.path("likeCount").asInt(), "호스트 보기: 장소 B 좋아요 1개")
        assertTrue(hostPlaceB.path("likedByMe").asBoolean(), "호스트 보기: 장소 B 내가 좋아요")

        // 멤버1 목록 조회: placeA 2개, placeB 0개
        val member1ListResult = mockMvc.get("/api/v1/boards/${host.boardId}/places") {
            bearer(member1.token)
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val member1ListJson = objectMapper.readTree(member1ListResult)
        val member1Items = member1ListJson.path("items")
        val member1PlaceA = member1Items.find { it.path("placeId").asText() == placeA }!!
        val member1PlaceB = member1Items.find { it.path("placeId").asText() == placeB }!!

        assertEquals(2, member1PlaceA.path("likeCount").asInt(), "멤버1 보기: 장소 A 좋아요 2개")
        assertTrue(member1PlaceA.path("likedByMe").asBoolean(), "멤버1 보기: 장소 A 내가 좋아요")
        assertEquals(1, member1PlaceB.path("likeCount").asInt(), "멤버1 보기: 장소 B 좋아요 1개")
        assertEquals(false, member1PlaceB.path("likedByMe").asBoolean(), "멤버1 보기: 장소 B 내가 좋아요 안 함")

        // 멤버2 목록 조회: placeA 0개, placeB 0개
        val member2ListResult = mockMvc.get("/api/v1/boards/${host.boardId}/places") {
            bearer(member2.token)
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val member2ListJson = objectMapper.readTree(member2ListResult)
        val member2Items = member2ListJson.path("items")
        val member2PlaceA = member2Items.find { it.path("placeId").asText() == placeA }!!
        val member2PlaceB = member2Items.find { it.path("placeId").asText() == placeB }!!

        assertEquals(2, member2PlaceA.path("likeCount").asInt(), "멤버2 보기: 장소 A 좋아요 2개")
        assertEquals(false, member2PlaceA.path("likedByMe").asBoolean(), "멤버2 보기: 장소 A 내가 좋아요 안 함")
        assertEquals(1, member2PlaceB.path("likeCount").asInt(), "멤버2 보기: 장소 B 좋아요 1개")
        assertEquals(false, member2PlaceB.path("likedByMe").asBoolean(), "멤버2 보기: 장소 B 내가 좋아요 안 함")
    }

    @Test
    fun `장소 목록에서 selected 필드는 보드의 현재 선택된 장소에만 true다`() {
        val host = createBoard("목록 선택 보드", "호스트")
        val member = joinBoard(host.inviteCode, "멤버")
        val placeA = createPlace(host.boardId, host.token, "장소 A")
        val placeB = createPlace(host.boardId, host.token, "장소 B")

        // 초기 상태: 아무도 선택하지 않음
        var listResult = mockMvc.get("/api/v1/boards/${host.boardId}/places") {
            bearer(host.token)
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        var listJson = objectMapper.readTree(listResult)
        var items = listJson.path("items")
        items.forEach { item ->
            assertEquals(false, item.path("selected").asBoolean(), "선택 전: 모든 장소 selected=false")
        }

        // 멤버가 장소 A를 선택
        mockMvc.put("/api/v1/boards/${host.boardId}/selected-place") {
            bearer(member.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"placeId": "$placeA"}"""
        }.andExpect { status { isOk() } }

        // 호스트 목록 조회: placeA만 selected=true
        listResult = mockMvc.get("/api/v1/boards/${host.boardId}/places") {
            bearer(host.token)
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        listJson = objectMapper.readTree(listResult)
        items = listJson.path("items")
        val selectedPlaceA = items.find { it.path("placeId").asText() == placeA }!!
        val selectedPlaceB = items.find { it.path("placeId").asText() == placeB }!!

        assertTrue(selectedPlaceA.path("selected").asBoolean(), "선택된 장소 A는 selected=true")
        assertEquals(false, selectedPlaceB.path("selected").asBoolean(), "선택되지 않은 장소 B는 selected=false")

        // 멤버가 장소 B로 선택 변경
        mockMvc.put("/api/v1/boards/${host.boardId}/selected-place") {
            bearer(member.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"placeId": "$placeB"}"""
        }.andExpect { status { isOk() } }

        // 호스트 목록 조회: placeB만 selected=true
        listResult = mockMvc.get("/api/v1/boards/${host.boardId}/places") {
            bearer(host.token)
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        listJson = objectMapper.readTree(listResult)
        items = listJson.path("items")
        val rePlaceA = items.find { it.path("placeId").asText() == placeA }!!
        val rePlaceB = items.find { it.path("placeId").asText() == placeB }!!

        assertEquals(false, rePlaceA.path("selected").asBoolean(), "선택 해제된 장소 A는 selected=false")
        assertTrue(rePlaceB.path("selected").asBoolean(), "새로 선택된 장소 B는 selected=true")
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
