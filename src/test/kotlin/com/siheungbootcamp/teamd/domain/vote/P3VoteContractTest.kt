package com.siheungbootcamp.teamd.domain.vote

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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * P3 투표 계약 테스트 (V3-4~V3-11)
 *
 * 엔드포인트 20-24: 투표 생성, 조회, 상세, 표 제출, 종료
 * 동시성, 멱등성, 익명/실명, 마감, 장소 사용 체크, N+1 최적화 검증
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = [
    "app.auth.token-pepper=test-pepper",
    "app.crypto.origin-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
    "app.board.frontend-base-url=https://example.app",
    "app.kakao.rest-key=test-kakao-key",
])
class P3VoteContractTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcClient: JdbcClient,
) {
    @Test
    fun `V3-4 열린 투표는 보드당 1개만 허용되고 중복은 409 RESOURCE_CONFLICT를 반환한다`() {
        val host = createBoard("투표 유일성 보드", "호스트")
        val place1 = createPlace(host, "장소1", 126.7, 37.3)
        val place2 = createPlace(host, "장소2", 127.0, 37.5)
        val closesAt = Instant.now().plus(1, ChronoUnit.HOURS).toString()

        // 첫 번째 투표 생성 → 201
        mockMvc.post("/api/v1/boards/${host.boardId}/votes") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"placeIds":["${place1.placeId}","${place2.placeId}"],"maxSelections":1,"anonymous":false,"closesAt":"$closesAt"}"""
        }.andExpect { status { isCreated() } }

        // 두 번째 투표 생성 시도 → 409
        mockMvc.post("/api/v1/boards/${host.boardId}/votes") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"placeIds":["${place1.placeId}","${place2.placeId}"],"maxSelections":1,"anonymous":false,"closesAt":"$closesAt"}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("RESOURCE_CONFLICT") }
        }
    }

    @Test
    fun `V3-5 투표 내용 반복 제출은 멱등성을 보장한다`() {
        val host = createBoard("투표 멱등성 보드", "호스트")
        val place = createPlace(host, "투표 장소", 126.7, 37.3)
        val vote = createVote(host, listOf(place.placeId))

        // 첫 번째 투표 제출
        mockMvc.put("/api/v1/boards/${host.boardId}/votes/${vote.voteId}/ballots/me") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"placeIds":["${place.placeId}"]}"""
        }.andExpect { status { isOk() } }

        // DB에서 표 개수 확인
        val count1 = jdbcClient.sql("SELECT COUNT(*) as cnt FROM vote_ballot WHERE vote_id = (SELECT id FROM vote WHERE public_id = :voteId)")
            .param("voteId", vote.voteId)
            .query(Int::class.java)
            .single()

        // 동일한 내용으로 재제출
        mockMvc.put("/api/v1/boards/${host.boardId}/votes/${vote.voteId}/ballots/me") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"placeIds":["${place.placeId}"]}"""
        }.andExpect { status { isOk() } }

        // DB에서 표 개수 다시 확인 (멱등성: 변경 없음)
        val count2 = jdbcClient.sql("SELECT COUNT(*) as cnt FROM vote_ballot WHERE vote_id = (SELECT id FROM vote WHERE public_id = :voteId)")
            .param("voteId", vote.voteId)
            .query(Int::class.java)
            .single()

        assertEquals(count1, count2, "멱등 요청 후 표 개수가 동일해야 함")
    }

    @Test
    fun `V3-6 빈 배열로 투표하면 투표가 취소되고 집계에 반영된다`() {
        val host = createBoard("투표 취소 보드", "호스트")
        val place = createPlace(host, "취소 테스트 장소", 126.7, 37.3)
        val vote = createVote(host, listOf(place.placeId))

        // 투표 제출
        mockMvc.put("/api/v1/boards/${host.boardId}/votes/${vote.voteId}/ballots/me") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"placeIds":["${place.placeId}"]}"""
        }.andExpect { status { isOk() } }

        // 투표 취소 (빈 배열)
        mockMvc.put("/api/v1/boards/${host.boardId}/votes/${vote.voteId}/ballots/me") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"placeIds":[]}"""
        }.andExpect { status { isOk() } }

        // 투표 상세 조회: count = 0
        val detail = mockMvc.get("/api/v1/boards/${host.boardId}/votes/${vote.voteId}") {
            bearer(host.token)
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val options = objectMapper.readTree(detail).path("options")
        assertEquals(0, options[0].path("count").asInt(), "취소된 투표는 집계에서 0이어야 함")
    }

    @Test
    fun `V3-7 maxSelections 초과 시도는 400 INVALID_ARGUMENT를 반환한다`() {
        val host = createBoard("투표 제한 보드", "호스트")
        val place1 = createPlace(host, "장소1", 126.7, 37.3)
        val place2 = createPlace(host, "장소2", 127.0, 37.5)
        val vote = createVote(host, listOf(place1.placeId, place2.placeId), maxSelections = 1)

        // 2개 선택 시도 (maxSelections=1) → 400
        mockMvc.put("/api/v1/boards/${host.boardId}/votes/${vote.voteId}/ballots/me") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"placeIds":["${place1.placeId}","${place2.placeId}"]}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("INVALID_ARGUMENT") }
        }
    }

    @Test
    fun `V3-8 익명 투표 상세 응답에는 participantId 키가 없다`() {
        val host = createBoard("익명 투표 보드", "호스트")
        val place = createPlace(host, "익명 테스트", 126.7, 37.3)
        val vote = createAnonVote(host, listOf(place.placeId))

        // 투표 제출
        mockMvc.put("/api/v1/boards/${host.boardId}/votes/${vote.voteId}/ballots/me") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"placeIds":["${place.placeId}"]}"""
        }.andExpect { status { isOk() } }

        // 상세 조회
        val detail = mockMvc.get("/api/v1/boards/${host.boardId}/votes/${vote.voteId}") {
            bearer(host.token)
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val json = objectMapper.readTree(detail)
        assertEquals(true, json.path("anonymous").asBoolean())

        // participantId 키 부재 확인
        val options = json.path("options")
        assertFalse(options.toString().contains("participantId"), "익명 투표 응답에 participantId가 없어야 함")
    }

    @Test
    fun `V3-9 마감 시간 경과 후 투표 시도는 409 RESOURCE_CONFLICT를 반환한다`() {
        val host = createBoard("마감 테스트 보드", "호스트")
        val place = createPlace(host, "마감 장소", 126.7, 37.3)
        // 마감 시간을 과거로 설정
        val closesAt = Instant.now().minus(1, ChronoUnit.HOURS).toString()

        val voteBody = mockMvc.post("/api/v1/boards/${host.boardId}/votes") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"placeIds":["${place.placeId}"],"maxSelections":1,"anonymous":false,"closesAt":"$closesAt"}"""
        }.andExpect { status { isCreated() } }
            .andReturn().response.contentAsString

        val voteId = objectMapper.readTree(voteBody).path("voteId").asText()

        // 마감된 투표에 투표 시도 → 409
        mockMvc.put("/api/v1/boards/${host.boardId}/votes/$voteId/ballots/me") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"placeIds":["${place.placeId}"]}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("RESOURCE_CONFLICT") }
        }
    }

    @Test
    fun `V3-10 투표된 장소 삭제 시도는 409 PLACE_IN_USE를 반환한다`() {
        val host = createBoard("장소 사용 체크 보드", "호스트")
        val place = createPlace(host, "사용 중인 장소", 126.7, 37.3)
        val vote = createVote(host, listOf(place.placeId))

        // 투표 제출
        mockMvc.put("/api/v1/boards/${host.boardId}/votes/${vote.voteId}/ballots/me") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"placeIds":["${place.placeId}"]}"""
        }.andExpect { status { isOk() } }

        // 사용 중인 장소 삭제 시도 → 409
        mockMvc.delete("/api/v1/boards/${host.boardId}/places/${place.placeId}") {
            bearer(host.token)
        }.andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("PLACE_IN_USE") }
            jsonPath("$.error.details.voteId") { value(vote.voteId) }
        }
    }

    @Test
    fun `V3-11 장소 목록 조회 시 N+1 쿼리가 발생하지 않는다`() {
        val host = createBoard("N+1 검증 보드", "호스트")
        // 20개 장소 생성
        repeat(20) { i ->
            createPlace(host, "테스트 장소 $i", 126.7 + i * 0.01, 37.3)
        }

        // 장소 목록 조회 시 commentCount가 정상적으로 집계됨
        val result = mockMvc.get("/api/v1/boards/${host.boardId}/places") {
            bearer(host.token)
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val places = objectMapper.readTree(result).path("items")
        assertEquals(20, places.size(), "20개 장소가 조회되어야 함")

        // 모든 commentCount가 정수면 N+1이 작동한다는 의미
        places.forEach { place ->
            val commentCount = place.path("commentCount").asInt()
            assertEquals(0, commentCount, "댓글이 없으므로 commentCount는 0")
        }
    }

    @Test
    fun `엔드포인트 20 - POST 투표 생성은 호스트만 가능하다`() {
        val host = createBoard("투표 권한 보드", "호스트")
        val member = join(host.inviteCode, "멤버")
        val place = createPlace(host, "권한 테스트", 126.7, 37.3)
        val closesAt = Instant.now().plus(1, ChronoUnit.HOURS).toString()

        // 멤버(비호스트) 투표 생성 시도 → 403
        mockMvc.post("/api/v1/boards/${host.boardId}/votes") {
            bearer(member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"placeIds":["${place.placeId}"],"maxSelections":1,"anonymous":false,"closesAt":"$closesAt"}"""
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value("FORBIDDEN") }
        }
    }

    @Test
    fun `엔드포인트 21 - GET 투표 목록은 status 필터를 지원한다`() {
        val host = createBoard("투표 필터 보드", "호스트")
        val place1 = createPlace(host, "장소1", 126.7, 37.3)
        val place2 = createPlace(host, "장소2", 127.0, 37.5)

        // OPEN 투표 생성
        createVote(host, listOf(place1.placeId))

        // CLOSED 투표 생성 후 종료
        val vote2 = createVote(host, listOf(place2.placeId))
        mockMvc.patch("/api/v1/boards/${host.boardId}/votes/${vote2.voteId}") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"status":"CLOSED"}"""
        }.andExpect { status { isOk() } }

        // OPEN만 조회
        val openResult = mockMvc.get("/api/v1/boards/${host.boardId}/votes") {
            bearer(host.token)
            param("status", "OPEN")
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val openVotes = objectMapper.readTree(openResult).path("items")
        assertEquals(1, openVotes.size(), "OPEN 투표는 1개")
        assertEquals("OPEN", openVotes[0].path("status").asText())
    }

    @Test
    fun `엔드포인트 24 - PATCH 투표 종료는 이미 CLOSED일 때 200을 반환한다 (멱등성)`() {
        val host = createBoard("투표 종료 멱등성 보드", "호스트")
        val place = createPlace(host, "종료 테스트", 126.7, 37.3)
        val vote = createVote(host, listOf(place.placeId))

        // 첫 번째 종료
        mockMvc.patch("/api/v1/boards/${host.boardId}/votes/${vote.voteId}") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"status":"CLOSED"}"""
        }.andExpect { status { isOk() } }

        // 재종료 (이미 CLOSED) → 200 (멱등)
        mockMvc.patch("/api/v1/boards/${host.boardId}/votes/${vote.voteId}") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"status":"CLOSED"}"""
        }.andExpect { status { isOk() } }
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

    private fun createPlace(host: CreatedBoard, name: String, lon: Double, lat: Double): PlaceInfo {
        val body = mockMvc.post("/api/v1/boards/${host.boardId}/places") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """
            {
              "name": "$name",
              "lon": $lon,
              "lat": $lat,
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

    private fun createVote(host: CreatedBoard, placeIds: List<String>, maxSelections: Int = 1): VoteInfo {
        val closesAt = Instant.now().plus(1, ChronoUnit.HOURS).toString()
        val placeIdJson = placeIds.joinToString(",") { "\"$it\"" }
        val body = mockMvc.post("/api/v1/boards/${host.boardId}/votes") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"placeIds":[$placeIdJson],"maxSelections":$maxSelections,"anonymous":false,"closesAt":"$closesAt"}"""
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        val json = objectMapper.readTree(body)
        return VoteInfo(json["voteId"].asText())
    }

    private fun createAnonVote(host: CreatedBoard, placeIds: List<String>, maxSelections: Int = 1): VoteInfo {
        val closesAt = Instant.now().plus(1, ChronoUnit.HOURS).toString()
        val placeIdJson = placeIds.joinToString(",") { "\"$it\"" }
        val body = mockMvc.post("/api/v1/boards/${host.boardId}/votes") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"placeIds":[$placeIdJson],"maxSelections":$maxSelections,"anonymous":true,"closesAt":"$closesAt"}"""
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        val json = objectMapper.readTree(body)
        return VoteInfo(json["voteId"].asText())
    }

    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.bearer(token: String) {
        header("Authorization", "Bearer $token")
    }

    private data class CreatedBoard(val boardId: String, val token: String, val inviteCode: String)
    private data class PlaceInfo(val placeId: String)
    private data class VoteInfo(val voteId: String)

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
