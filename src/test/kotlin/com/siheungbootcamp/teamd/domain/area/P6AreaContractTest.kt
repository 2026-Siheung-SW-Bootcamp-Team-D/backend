package com.siheungbootcamp.teamd.domain.area

import com.siheungbootcamp.teamd.infra.external.kakao.KakaoStubServer
import com.siheungbootcamp.teamd.infra.external.odsay.OdsayStubServer
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
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals

/**
 * P6 지역 탐색 계약 테스트.
 *
 * 3단계 파이프라인: ISOCHRONE (ODsay) → INTERSECTION (JTS) → AREA_ANCHOR_COLLECTION (Kakao)
 * 모든 단계에서 TMAP 호출이 0이어야 함.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = [
    "app.auth.token-pepper=test-pepper",
    "app.crypto.origin-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
    "app.board.frontend-base-url=https://example.app",
    "app.kakao.rest-key=test-kakao-key",
    "app.odsay.api-key=test-odsay-key",
    "app.job.enabled=false",
])
class P6AreaContractTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcClient: JdbcClient,
    @Autowired private val areaJobExecutor: AreaJobExecutor,
) {
    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.bearer(token: String) {
        header("Authorization", "Bearer $token")
    }

    companion object {
        @Container @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        private val kakaoStubServer = KakaoStubServer()
        private val odsayStubServer = OdsayStubServer()

        init {
            kakaoStubServer.start()
            odsayStubServer.start()
        }

        @DynamicPropertySource
        @JvmStatic
        fun dynamicProperties(registry: DynamicPropertyRegistry) {
            registry.add("app.kakao.base-url") { kakaoStubServer.baseUrl }
            registry.add("app.odsay.base-url") { odsayStubServer.baseUrl }
        }
    }

    private fun createBoard(name: String, hostNickname: String): BoardTokenPair {
        val res = mockMvc.post("/api/v1/boards") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name","dateRange":{"start":"2099-01-01","end":"2099-01-02"},"purpose":"test","hostNickname":"$hostNickname"}"""
        }.andReturn().response
        val boardData = objectMapper.readTree(res.contentAsString)
        val boardId = boardData.path("board").path("boardId").asText()
        val token = boardData.path("participant").path("participantToken").asText()
        return BoardTokenPair(boardId, token)
    }

    private fun inviteAndJoin(host: BoardTokenPair, nickname: String): BoardTokenPair {
        val inviteRes = mockMvc.get("/api/v1/boards/${host.boardId}/invitation") {
            bearer(host.token)
        }.andReturn().response
        val inviteCode = objectMapper.readTree(inviteRes.contentAsString).path("inviteCode").asText()

        val joinRes = mockMvc.post("/api/v1/invitations/$inviteCode/participants") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"nickname":"$nickname"}"""
        }.andReturn().response
        val token = objectMapper.readTree(joinRes.contentAsString).path("participantToken").asText()
        return BoardTokenPair(host.boardId, token)
    }

    private fun setOrigin(participant: BoardTokenPair, label: String, lon: Double, lat: Double) {
        mockMvc.patch("/api/v1/boards/${participant.boardId}/participants/me") {
            bearer(participant.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"origin":{"label":"$label","lon":$lon,"lat":$lat,"source":"MANUAL_PIN"}}"""
        }.andExpect { status { isOk() } }
    }

    private data class BoardTokenPair(val boardId: String, val token: String)

    @Test
    fun `일반 참여자가 지역 제안을 시작하고 폴링해 세 개 이하 결과를 받는다`() {
        val host = createBoard("P6 테스트", "호스트")
        val member = inviteAndJoin(host, "일반참여자")
        setOrigin(host, "호스트출발", 126.97, 37.55)
        setOrigin(member, "참여자출발", 126.96, 37.54)

        kakaoStubServer.setKeywordResponseMode(KakaoStubServer.ResponseMode.SUCCESS)
        odsayStubServer.responseMode = OdsayStubServer.ResponseMode.SUCCESS

        val postRes = mockMvc.post("/api/v1/boards/${host.boardId}/area-search-jobs") {
            bearer(member.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"durationMin":45}"""
        }.andReturn().response

        assertEquals(202, postRes.status, "POST는 202 ACCEPTED를 반환")
        val postData = objectMapper.readTree(postRes.contentAsString)
        assertEquals("QUEUED", postData.path("status").asText())
        assertEquals(2, postData.path("estimatedExternalCalls").path("odsay").asInt())
        assertEquals(0, postData.path("estimatedExternalCalls").path("tmapTransit").asInt())
        val jobId = postData.path("jobId").asText()

        // 모든 작업 처리
        for (i in 0 until 100) { if (!areaJobExecutor.processOne()) break }

        val getRes = mockMvc.get("/api/v1/boards/${host.boardId}/area-search-jobs/$jobId") {
            bearer(member.token)
        }.andReturn().response
        val getData = objectMapper.readTree(getRes.contentAsString)
        assertEquals("SUCCEEDED", getData.path("status").asText())
        // Task 5: 새로운 응답 구조 (participantCenter, isochrones, commonArea nullable, anchors)
        val result = getData.path("result")
        assertEquals(true, result.has("participantCenter"))
        assertEquals(true, result.has("isochrones"))
        assertEquals(true, result.has("anchors"))
        assertEquals(true, result.path("anchors").size() <= 3)

        // TMAP 호출 0회 확인
        assertEquals(0, kakaoStubServer.tmapRequestCount)
    }

    @Test
    fun `출발지 누락시 동기 422 ORIGIN_REQUIRED`() {
        val host = createBoard("출발지 없음 테스트", "호스트")
        val member = inviteAndJoin(host, "참여자")
        // 출발지 설정 안 함

        mockMvc.post("/api/v1/boards/${host.boardId}/area-search-jobs") {
            bearer(member.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"durationMin":45}"""
        }.andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.error.code") { value("ORIGIN_REQUIRED") }
        }
    }

    @Test
    fun `참여자 1명이면 400 INVALID_ARGUMENT`() {
        val host = createBoard("참여자 1명 테스트", "호스트")
        setOrigin(host, "호스트출발", 126.97, 37.55)

        mockMvc.post("/api/v1/boards/${host.boardId}/area-search-jobs") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"durationMin":45}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("INVALID_ARGUMENT") }
        }
    }

    @Test
    fun `같은 입력의 활성 작업이 있으면 기존 jobId 재사용`() {
        val host = createBoard("중복 작업 테스트", "호스트")
        val member = inviteAndJoin(host, "참여자")
        setOrigin(host, "호스트출발", 126.97, 37.55)
        setOrigin(member, "참여자출발", 126.96, 37.54)

        odsayStubServer.responseMode = OdsayStubServer.ResponseMode.SUCCESS

        val firstRes = mockMvc.post("/api/v1/boards/${host.boardId}/area-search-jobs") {
            bearer(member.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"durationMin":45}"""
        }.andReturn().response
        val firstJobId = objectMapper.readTree(firstRes.contentAsString).path("jobId").asText()

        val secondRes = mockMvc.post("/api/v1/boards/${host.boardId}/area-search-jobs") {
            bearer(member.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"durationMin":45}"""
        }.andReturn().response
        val secondJobId = objectMapper.readTree(secondRes.contentAsString).path("jobId").asText()

        assertEquals(firstJobId, secondJobId, "같은 입력의 활성 작업은 기존 jobId 반환")
    }

    @Test
    fun `교집합 없으면 job FAILED with NO_INTERSECTION`() {
        val host = createBoard("교집합 없음 테스트", "호스트")
        val member = inviteAndJoin(host, "참여자")
        setOrigin(host, "호스트출발", 126.97, 37.55)
        setOrigin(member, "참여자출발", 126.96, 37.54)

        odsayStubServer.responseMode = OdsayStubServer.ResponseMode.NO_INTERSECTION

        val postRes = mockMvc.post("/api/v1/boards/${host.boardId}/area-search-jobs") {
            bearer(member.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"durationMin":45}"""
        }.andReturn().response
        val jobId = objectMapper.readTree(postRes.contentAsString).path("jobId").asText()

        for (i in 0 until 100) { if (!areaJobExecutor.processOne()) break }

        val getRes = mockMvc.get("/api/v1/boards/${host.boardId}/area-search-jobs/$jobId") {
            bearer(member.token)
        }.andReturn().response
        val getData = objectMapper.readTree(getRes.contentAsString)
        // Task 5: 교집합 없음은 실패가 아니라 성공 (commonArea=null)
        assertEquals("SUCCEEDED", getData.path("status").asText())
        assertEquals(true, getData.path("result").path("commonArea").isNull)
    }

    @Test
    fun `Kakao 기준점 없으면 job FAILED with NO_AREA_ANCHOR`() {
        val host = createBoard("기준점 없음 테스트", "호스트")
        val member = inviteAndJoin(host, "참여자")
        setOrigin(host, "호스트출발", 126.97, 37.55)
        setOrigin(member, "참여자출발", 126.96, 37.54)

        odsayStubServer.responseMode = OdsayStubServer.ResponseMode.SUCCESS
        kakaoStubServer.setKeywordResponseMode(KakaoStubServer.ResponseMode.EMPTY)

        val postRes = mockMvc.post("/api/v1/boards/${host.boardId}/area-search-jobs") {
            bearer(member.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"durationMin":45}"""
        }.andReturn().response
        val jobId = objectMapper.readTree(postRes.contentAsString).path("jobId").asText()

        for (i in 0 until 100) { if (!areaJobExecutor.processOne()) break }

        val getRes = mockMvc.get("/api/v1/boards/${host.boardId}/area-search-jobs/$jobId") {
            bearer(member.token)
        }.andReturn().response
        val getData = objectMapper.readTree(getRes.contentAsString)
        // Task 5: 기준점 없음은 실패가 아니라 성공 (anchors=[])
        assertEquals("SUCCEEDED", getData.path("status").asText())
        val anchors = getData.path("result").path("anchors")
        assertEquals(true, anchors.isArray)
        assertEquals(0, anchors.size())
    }

    @Test
    fun `ODsay 실패하면 job FAILED with EXTERNAL_UNAVAILABLE`() {
        val host = createBoard("ODsay 실패 테스트", "호스트")
        val member = inviteAndJoin(host, "참여자")
        setOrigin(host, "호스트출발", 126.97, 37.55)
        setOrigin(member, "참여자출발", 126.96, 37.54)

        odsayStubServer.responseMode = OdsayStubServer.ResponseMode.SERVER_ERROR

        val postRes = mockMvc.post("/api/v1/boards/${host.boardId}/area-search-jobs") {
            bearer(member.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"durationMin":45}"""
        }.andReturn().response
        val jobId = objectMapper.readTree(postRes.contentAsString).path("jobId").asText()

        for (i in 0 until 100) { if (!areaJobExecutor.processOne()) break }

        val getRes = mockMvc.get("/api/v1/boards/${host.boardId}/area-search-jobs/$jobId") {
            bearer(member.token)
        }.andReturn().response
        val getData = objectMapper.readTree(getRes.contentAsString)
        assertEquals("FAILED", getData.path("status").asText())
        assertEquals("EXTERNAL_UNAVAILABLE", getData.path("errorCode").asText())
    }

    @Test
    fun `모든 단계에서 TMAP 호출 0회`() {
        val host = createBoard("TMAP 0회 테스트", "호스트")
        val member = inviteAndJoin(host, "참여자")
        setOrigin(host, "호스트출발", 126.97, 37.55)
        setOrigin(member, "참여자출발", 126.96, 37.54)

        kakaoStubServer.setKeywordResponseMode(KakaoStubServer.ResponseMode.SUCCESS)
        odsayStubServer.responseMode = OdsayStubServer.ResponseMode.SUCCESS

        mockMvc.post("/api/v1/boards/${host.boardId}/area-search-jobs") {
            bearer(member.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"durationMin":45}"""
        }.andReturn().response

        for (i in 0 until 100) { if (!areaJobExecutor.processOne()) break }

        assertEquals(0, kakaoStubServer.tmapRequestCount, "TMAP 호출이 0이어야 함")
    }
}
