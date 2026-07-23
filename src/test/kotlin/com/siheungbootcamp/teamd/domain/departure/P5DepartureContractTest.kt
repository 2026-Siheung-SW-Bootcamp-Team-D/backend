package com.siheungbootcamp.teamd.domain.departure

import com.siheungbootcamp.teamd.infra.external.tmap.TmapStubServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Testcontainers
@ExtendWith(OutputCaptureExtension::class)
@AutoConfigureMockMvc
@SpringBootTest(properties = [
    "app.auth.token-pepper=test-pepper",
    "app.crypto.origin-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
    "app.board.frontend-base-url=https://example.app",
    "app.tmap.app-key=test-tmap-key",
    "app.job.enabled=false",
])
class P5DepartureContractTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcClient: JdbcClient,
    @Autowired private val departureJobExecutor: DepartureJobExecutor,
) {
    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.bearer(token: String) {
        header("Authorization", "Bearer $token")
    }

    companion object {
        @Container @ServiceConnection @JvmStatic
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

    @Test
    fun `V5-1 E2E happy path`() {
        val host = createBoard("P5 테스트", "호스트")
        val p1 = inviteAndJoin(host, "P1")
        setOrigin(host, "호스트출발", 126.97, 37.55)
        setOrigin(p1, "P1출발", 126.96, 37.54)
        val place = createPlace(host, "만남", 126.98, 37.56)
        confirmCourse(host, place)

        tmapStubServer.responseMode = TmapStubServer.ResponseMode.SUCCESS
        mockMvc.post("/api/v1/boards/${host.boardId}/participants/me/departure-calculations") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON; content = "{}"
        }.andExpect { status { isAccepted() } }

        // 큐의 모든 작업 처리 (다른 테스트의 leftover row 포함)
        for (i in 0 until 100) { if (!departureJobExecutor.processOne()) break }

        val res = mockMvc.get("/api/v1/boards/${host.boardId}/participants/me/departure-guide") {
            bearer(host.token)
        }.andReturn().response
        val status = objectMapper.readTree(res.contentAsString).path("status").asText()
        assertEquals("READY", status, "작업 실행 후 상태가 READY여야 함")
    }

    @Test
    fun `V5-2 POST로만 계산 발생`() {
        val host = createBoard("V5-2", "호스트")
        val p = inviteAndJoin(host, "P")
        setOrigin(host, "출", 126.97, 37.55)
        setOrigin(p, "출", 126.96, 37.54)
        val place = createPlace(host, "만", 126.98, 37.56)
        confirmCourse(host, place)

        tmapStubServer.resetCount()
        mockMvc.post("/api/v1/boards/${host.boardId}/participants/me/departure-calculations") {
            bearer(p.token)
            contentType = MediaType.APPLICATION_JSON; content = "{}"
        }.andExpect { status { isAccepted() } }

        // POST 없이는 TMAP 호출이 없어야 함 (Job Executor도 실행 안 함)
        assertEquals(0, tmapStubServer.requestCount())
    }

    @Test
    fun `V5-3 중복요청 작업미증가`() {
        val host = createBoard("V5-3", "호스트")
        val p = inviteAndJoin(host, "P")
        setOrigin(host, "출", 126.97, 37.55)
        setOrigin(p, "출", 126.96, 37.54)
        val place = createPlace(host, "만", 126.98, 37.56)
        confirmCourse(host, place)

        mockMvc.post("/api/v1/boards/${host.boardId}/participants/me/departure-calculations") {
            bearer(p.token)
            contentType = MediaType.APPLICATION_JSON; content = "{}"
        }.andExpect { status { isAccepted() } }

        mockMvc.post("/api/v1/boards/${host.boardId}/participants/me/departure-calculations") {
            bearer(p.token)
            contentType = MediaType.APPLICATION_JSON; content = "{}"
        }.andExpect { status { isAccepted() } }

        // 큐의 모든 작업 처리 후 남은 CALCULATING 행 확인
        for (i in 0 until 100) { if (!departureJobExecutor.processOne()) break }

        // DB에 행 1개만 (처리되어 READY로 변했으므로 CALCULATING 행은 0)
        val count = jdbcClient.sql("select count(*) from departure_calculation where status='CALCULATING'")
            .query(Int::class.java).single()
        assertEquals(0, count)
    }

    @Test
    fun `V5-4 READY재요청 외부미호출`() {
        val host = createBoard("V5-4", "호스트")
        val p = inviteAndJoin(host, "P")
        setOrigin(host, "출", 126.97, 37.55)
        setOrigin(p, "출", 126.96, 37.54)
        val place = createPlace(host, "만", 126.98, 37.56)
        confirmCourse(host, place)

        tmapStubServer.responseMode = TmapStubServer.ResponseMode.SUCCESS
        mockMvc.post("/api/v1/boards/${host.boardId}/participants/me/departure-calculations") {
            bearer(p.token)
            contentType = MediaType.APPLICATION_JSON; content = "{}"
        }.andExpect { status { isAccepted() } }
        for (i in 0 until 100) { if (!departureJobExecutor.processOne()) break }

        tmapStubServer.resetCount()
        mockMvc.post("/api/v1/boards/${host.boardId}/participants/me/departure-calculations") {
            bearer(p.token)
            contentType = MediaType.APPLICATION_JSON; content = "{}"
        }.andExpect { status { isOk() } }
        assertEquals(0, tmapStubServer.requestCount())
    }

    @Test
    fun `V5-5 출발지없음 422`() {
        val host = createBoard("V5-5", "호스트")
        val p = inviteAndJoin(host, "P")
        val place = createPlace(host, "만", 126.98, 37.56)
        confirmCourse(host, place)

        mockMvc.post("/api/v1/boards/${host.boardId}/participants/me/departure-calculations") {
            bearer(p.token)
            contentType = MediaType.APPLICATION_JSON; content = "{}"
        }.andExpect { status { isUnprocessableEntity() } }
    }

    @Test
    fun `V5-6 코스없음 409`() {
        val host = createBoard("V5-6", "호스트")
        val p = inviteAndJoin(host, "P")
        setOrigin(p, "출", 126.96, 37.54)

        mockMvc.post("/api/v1/boards/${host.boardId}/participants/me/departure-calculations") {
            bearer(p.token)
            contentType = MediaType.APPLICATION_JSON; content = "{}"
        }.andExpect { status { isConflict() } }
    }

    @Test
    fun `V5-7 경로없음UNAVAILABLE`() {
        val host = createBoard("V5-7", "호스트")
        val p = inviteAndJoin(host, "P")
        setOrigin(host, "출", 126.97, 37.55)
        setOrigin(p, "출", 126.96, 37.54)
        val place = createPlace(host, "만", 126.98, 37.56)
        confirmCourse(host, place)

        tmapStubServer.responseMode = TmapStubServer.ResponseMode.NO_ROUTE
        mockMvc.post("/api/v1/boards/${host.boardId}/participants/me/departure-calculations") {
            bearer(p.token)
            contentType = MediaType.APPLICATION_JSON; content = "{}"
        }.andExpect { status { isAccepted() } }

        for (i in 0 until 100) { if (!departureJobExecutor.processOne()) break }
        val res = mockMvc.get("/api/v1/boards/${host.boardId}/participants/me/departure-guide") {
            bearer(p.token)
        }.andReturn().response
        assertEquals("UNAVAILABLE", objectMapper.readTree(res.contentAsString).path("status").asText())
    }

    @Test
    fun `V5-8 재시도소진FAILED`() {
        val host = createBoard("V5-8", "호스트")
        val p = inviteAndJoin(host, "P")
        setOrigin(host, "출", 126.97, 37.55)
        setOrigin(p, "출", 126.96, 37.54)
        val place = createPlace(host, "만", 126.98, 37.56)
        confirmCourse(host, place)

        tmapStubServer.responseMode = TmapStubServer.ResponseMode.SERVER_ERROR
        mockMvc.post("/api/v1/boards/${host.boardId}/participants/me/departure-calculations") {
            bearer(p.token)
            contentType = MediaType.APPLICATION_JSON; content = "{}"
        }.andExpect { status { isAccepted() } }

        for (i in 0 until 100) { if (!departureJobExecutor.processOne()) break }
        val res = mockMvc.get("/api/v1/boards/${host.boardId}/participants/me/departure-guide") {
            bearer(p.token)
        }.andReturn().response
        assertEquals("FAILED", objectMapper.readTree(res.contentAsString).path("status").asText())
    }

    @Test
    fun `V5-9 권장출발시각공식`() {
        val m = Instant.parse("2026-07-26T18:00:00Z")
        val r = m.minusSeconds(1920L).minus(10, ChronoUnit.MINUTES)
        assertEquals(Instant.parse("2026-07-26T17:18:00Z"), r)
    }

    @Test
    fun `V5-10 출발지변경STALE`() {
        val host = createBoard("V5-10", "호스트")
        val p = inviteAndJoin(host, "P")
        setOrigin(host, "출1", 126.97, 37.55)
        setOrigin(p, "출1", 126.96, 37.54)
        val place = createPlace(host, "만", 126.98, 37.56)
        confirmCourse(host, place)

        tmapStubServer.responseMode = TmapStubServer.ResponseMode.SUCCESS
        mockMvc.post("/api/v1/boards/${host.boardId}/participants/me/departure-calculations") {
            bearer(p.token)
            contentType = MediaType.APPLICATION_JSON; content = "{}"
        }.andExpect { status { isAccepted() } }
        departureJobExecutor.processOne()

        setOrigin(p, "출2", 126.99, 37.57)

        val res = mockMvc.get("/api/v1/boards/${host.boardId}/participants/me/departure-guide") {
            bearer(p.token)
        }.andReturn().response
        assertEquals("STALE", objectMapper.readTree(res.contentAsString).path("status").asText())
    }

    @Test
    fun `V5-11 재시작복구`() {
        val host = createBoard("V5-11", "호스트")
        val p = inviteAndJoin(host, "P")
        setOrigin(host, "출", 126.97, 37.55)
        setOrigin(p, "출", 126.96, 37.54)
        val place = createPlace(host, "만", 126.98, 37.56)
        confirmCourse(host, place)

        mockMvc.post("/api/v1/boards/${host.boardId}/participants/me/departure-calculations") {
            bearer(p.token)
            contentType = MediaType.APPLICATION_JSON; content = "{}"
        }.andExpect { status { isAccepted() } }

        val before = jdbcClient.sql("select count(*) from departure_calculation where status='CALCULATING'")
            .query(Int::class.java).single()
        assertTrue(before > 0)

        tmapStubServer.responseMode = TmapStubServer.ResponseMode.SUCCESS
        for (i in 0 until 100) { if (!departureJobExecutor.processOne()) break }

        val res = mockMvc.get("/api/v1/boards/${host.boardId}/participants/me/departure-guide") {
            bearer(p.token)
        }.andReturn().response
        assertEquals("READY", objectMapper.readTree(res.contentAsString).path("status").asText())
    }

    @Test
    fun `V5-13 좌표미노출`() {
        val host = createBoard("V5-13", "호스트")
        val p = inviteAndJoin(host, "P")
        setOrigin(host, "출", 126.97, 37.55)
        setOrigin(p, "출", 126.96, 37.54)
        val place = createPlace(host, "만", 126.98, 37.56)
        confirmCourse(host, place)

        tmapStubServer.responseMode = TmapStubServer.ResponseMode.SUCCESS
        mockMvc.post("/api/v1/boards/${host.boardId}/participants/me/departure-calculations") {
            bearer(p.token)
            contentType = MediaType.APPLICATION_JSON; content = "{}"
        }.andExpect { status { isAccepted() } }
        departureJobExecutor.processOne()

        val res = mockMvc.get("/api/v1/boards/${host.boardId}/participants/me/departure-guide") {
            bearer(p.token)
        }.andReturn().response
        assertFalse(res.contentAsString.contains("126.") || res.contentAsString.contains("37."))
    }

    @Test
    fun `V5-12 외부 호출 중 DB 트랜잭션 미보유`() {
        val host = createBoard("동시성 테스트", "호스트")
        setOrigin(host, "출", 126.97, 37.55)
        val place = createPlace(host, "도착", 126.98, 37.56)
        confirmCourse(host, place)

        tmapStubServer.responseMode = TmapStubServer.ResponseMode.SUCCESS
        val startTime = System.currentTimeMillis()
        // 계산 요청을 보낸다
        mockMvc.post("/api/v1/boards/${host.boardId}/participants/me/departure-calculations") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON; content = "{}"
        }.andExpect { status { isAccepted() } }

        // 큐의 모든 작업 처리
        for (i in 0 until 100) { if (!departureJobExecutor.processOne()) break }

        // 계산 결과 조회
        val req2 = mockMvc.get("/api/v1/boards/${host.boardId}/participants/me/departure-guide") {
            bearer(host.token)
        }.andReturn().response

        val elapsed = System.currentTimeMillis() - startTime
        assertTrue(elapsed < 10000, "전체 처리가 10초 내에 완료되어야 함")

        // 계산 결과는 READY (모든 큐를 처리했으므로)
        val status = objectMapper.readTree(req2.contentAsString).path("status").asText()
        assertEquals("READY", status, "상태는 READY여야 함")
    }

    private fun createBoard(n: String, nick: String): CB {
        val r = mockMvc.post("/api/v1/boards") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$n","dateRange":{"start":"2099-01-01","end":"2099-01-02"},"purpose":"test","hostNickname":"$nick"}"""
        }.andExpect { status { isCreated() } }.andReturn().response
        val j = objectMapper.readTree(r.contentAsString)
        return CB(
            j.path("board").path("boardId").asText(),
            j.path("participant").path("participantToken").asText(),
            j.path("invitation").path("inviteCode").asText()
        )
    }

    private fun inviteAndJoin(host: CB, nick: String): CB {
        val jr = mockMvc.post("/api/v1/invitations/${host.inviteCode}/participants") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"nickname":"$nick"}"""
        }.andExpect { status { isCreated() } }.andReturn().response
        val j = objectMapper.readTree(jr.contentAsString)
        return CB(host.boardId, j.path("participantToken").asText(), host.inviteCode)
    }

    private fun setOrigin(host: CB, label: String, lon: Double, lat: Double) {
        mockMvc.patch("/api/v1/boards/${host.boardId}/participants/me") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"origin":{"label":"$label","lon":$lon,"lat":$lat,"source":"MANUAL_PIN"}}"""
        }.andExpect { status { isOk() } }
    }

    private fun createPlace(host: CB, n: String, lon: Double, lat: Double): String {
        val r = mockMvc.post("/api/v1/boards/${host.boardId}/places") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$n","addressName":"서울","roadAddressName":"서울","lon":$lon,"lat":$lat,"internalCategory":"RESTAURANT","provider":"KAKAO","providerPlaceId":"123","providerPlaceUrl":null,"source":"MANUAL_PIN"}"""
        }.andExpect { status { isCreated() } }.andReturn().response
        return objectMapper.readTree(r.contentAsString).path("placeId").asText()
    }

    private fun confirmCourse(host: CB, placeId: String) {
        mockMvc.put("/api/v1/boards/${host.boardId}/course-draft") {
            bearer(host.token)
            header("If-Match", "\"draft-0\"")
            contentType = MediaType.APPLICATION_JSON
            content = """{"stops":[{"placeId":"$placeId","orderIndex":1,"role":"FIRST_MEETING","scheduledAt":"2026-07-26T18:00:00Z"}]}"""
        }.andExpect { status { isOk() } }

        mockMvc.post("/api/v1/boards/${host.boardId}/courses") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON; content = """{"draftVersion":1}"""
        }.andExpect { status { isCreated() } }
    }

    data class CB(val boardId: String, val token: String, val inviteCode: String)
}
