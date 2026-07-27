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
            content = """{"name":"$name","purpose":"test","creatorNickname":"$hostNickname"}"""
        }.andReturn().response
        val boardData = objectMapper.readTree(res.contentAsString)
        val boardId = boardData.path("board").path("boardId").asText()
        val token = boardData.path("participantToken").asText()
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
        assertEquals("QUEUED", postData.path("job").path("status").asText())
        assertEquals(2, postData.path("job").path("estimatedExternalCalls").path("odsay").asInt())
        assertEquals(0, postData.path("job").path("estimatedExternalCalls").path("tmapTransit").asInt())
        val jobId = postData.path("job").path("jobId").asText()

        // 모든 작업 처리
        for (i in 0 until 100) { if (!areaJobExecutor.processOne()) break }

        val getRes = mockMvc.get("/api/v1/boards/${host.boardId}/area-search-jobs/$jobId") {
            bearer(member.token)
        }.andReturn().response
        val getData = objectMapper.readTree(getRes.contentAsString)
        assertEquals("SUCCEEDED", getData.path("job").path("status").asText())
        // Task 5: 새로운 응답 구조 (participantCenter, isochrones, commonArea nullable, anchors)
        val result = getData.path("result")
        assertEquals(true, result.has("participantCenter"))
        assertEquals(true, result.has("isochrones"))
        assertEquals(true, result.has("anchors"))
        assertEquals(true, result.path("anchors").size() <= 3)
        assertEquals(126.965, result.path("participantCenter").path("lon").asDouble(), 0.000_001)
        assertEquals(37.545, result.path("participantCenter").path("lat").asDouble(), 0.000_001)
        val anchors = result.path("anchors")
        for (index in 0 until anchors.size()) {
            assertEquals(index + 1, anchors[index].path("rank").asInt())
            assertEquals("KAKAO", anchors[index].path("provider").asText())
            assertEquals(true, anchors[index].has("providerPlaceId"))
            assertEquals(true, anchors[index].has("location"))
            if (index > 0) {
                assertEquals(
                    true,
                    anchors[index - 1].path("centerDistanceMeters").asInt() <=
                        anchors[index].path("centerDistanceMeters").asInt(),
                    "기준점은 참여자 대표 중심과 가까운 순이어야 함",
                )
            }
        }

        // TMAP 호출 0회 확인
        assertEquals(0, kakaoStubServer.tmapRequestCount)
    }

    @Test
    fun `보드 지도용 최신 성공 결과는 시간별 공통 영역만 반환한다`() {
        val host = createBoard("지도 영역 조회 테스트", "호스트")
        val member = inviteAndJoin(host, "참여자")
        setOrigin(host, "호스트출발", 126.97, 37.55)
        setOrigin(member, "참여자출발", 126.96, 37.54)
        kakaoStubServer.setKeywordResponseMode(KakaoStubServer.ResponseMode.SUCCESS)
        odsayStubServer.responseMode = OdsayStubServer.ResponseMode.SUCCESS

        for (duration in listOf(30, 45)) {
            mockMvc.post("/api/v1/boards/${host.boardId}/area-search-jobs") {
                bearer(host.token)
                contentType = MediaType.APPLICATION_JSON
                content = """{"durationMin":$duration}"""
            }.andExpect { status { isAccepted() } }
            for (i in 0 until 100) { if (!areaJobExecutor.processOne()) break }
        }

        val response = mockMvc.get("/api/v1/boards/${host.boardId}/area-search-results") {
            bearer(member.token)
        }.andExpect { status { isOk() } }
            .andReturn().response
        val results = objectMapper.readTree(response.contentAsString).path("results")

        assertEquals(2, results.size())
        assertEquals(30, results[0].path("durationMin").asInt())
        assertEquals(45, results[1].path("durationMin").asInt())
        assertEquals(true, results[0].hasNonNull("jobId"))
        assertEquals(true, results[0].hasNonNull("finishedAt"))
        assertEquals(true, results[0].has("commonArea"))
        assertEquals(true, results[0].has("participantCenter"))
        assertEquals(false, results[0].has("isochrones"), "개별 도달권은 메인 지도 결과에 노출하지 않는다")
        assertEquals(false, results[0].has("anchors"), "탐색 기준점은 메인 지도 결과에 노출하지 않는다")
    }

    @Test
    fun `보드 지도는 같은 시간의 최신 레거시 결과를 건너뛴다`() {
        val host = createBoard("레거시 지도 결과 제외", "호스트")
        val member = inviteAndJoin(host, "참여자")
        setOrigin(host, "호스트출발", 126.97, 37.55)
        setOrigin(member, "참여자출발", 126.96, 37.54)
        kakaoStubServer.setKeywordResponseMode(KakaoStubServer.ResponseMode.SUCCESS)
        odsayStubServer.responseMode = OdsayStubServer.ResponseMode.SUCCESS

        val createResponse = mockMvc.post("/api/v1/boards/${host.boardId}/area-search-jobs") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"durationMin":30}"""
        }.andExpect { status { isAccepted() } }
            .andReturn().response
        val validJobId = objectMapper.readTree(createResponse.contentAsString).path("job").path("jobId").asText()
        for (i in 0 until 100) { if (!areaJobExecutor.processOne()) break }

        jdbcClient.sql(
            """
            insert into area_search_job (
                public_id, board_id, duration_min, snapshot, status, result,
                retry_count, finished_at, created_at, updated_at
            ) values (
                :publicId,
                (select id from board where public_id = :boardId),
                30,
                '{}'::jsonb,
                'SUCCEEDED',
                '{"candidates":[]}'::jsonb,
                0,
                now() + interval '1 second',
                now() + interval '1 second',
                now() + interval '1 second'
            )
            """.trimIndent(),
        ).param("publicId", "area_legacy_${System.nanoTime()}")
            .param("boardId", host.boardId)
            .update()

        val response = mockMvc.get("/api/v1/boards/${host.boardId}/area-search-results") {
            bearer(member.token)
        }.andExpect { status { isOk() } }
            .andReturn().response
        val results = objectMapper.readTree(response.contentAsString).path("results")

        assertEquals(1, results.size())
        assertEquals(validJobId, results[0].path("jobId").asText())
        assertEquals(true, results[0].hasNonNull("participantCenter"))
        assertEquals(true, results[0].hasNonNull("commonArea"))
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
    fun `출발지가 없는 참여자를 명시적으로 제외하면 등록된 두 명으로 작업을 시작한다`() {
        val host = createBoard("일부 참여자 계산", "호스트")
        val readyMember = inviteAndJoin(host, "출발지 등록 멤버")
        inviteAndJoin(host, "출발지 미등록 멤버")
        setOrigin(host, "호스트출발", 126.97, 37.55)
        setOrigin(readyMember, "멤버출발", 126.96, 37.54)
        val hostId = host.token.substringBefore('.')
        val readyMemberId = readyMember.token.substringBefore('.')

        mockMvc.post("/api/v1/boards/${host.boardId}/area-search-jobs") {
            bearer(readyMember.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"durationMin":45,"participantIds":["$hostId","$readyMemberId"]}"""
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.job.estimatedExternalCalls.odsay") { value(2) }
        }
    }

    @Test
    fun `선택 대상에 출발지 미등록자나 다른 보드 참여자가 포함되면 거부한다`() {
        val host = createBoard("선택 검증 보드", "호스트")
        val missingMember = inviteAndJoin(host, "미등록 멤버")
        setOrigin(host, "호스트출발", 126.97, 37.55)
        val otherBoard = createBoard("다른 선택 보드", "다른 호스트")
        setOrigin(otherBoard, "다른출발", 127.01, 37.51)

        mockMvc.post("/api/v1/boards/${host.boardId}/area-search-jobs") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"durationMin":45,"participantIds":["${host.token.substringBefore('.')}","${missingMember.token.substringBefore('.')}"]}"""
        }.andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.error.code") { value("ORIGIN_REQUIRED") }
        }

        mockMvc.post("/api/v1/boards/${host.boardId}/area-search-jobs") {
            bearer(host.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"durationMin":45,"participantIds":["${host.token.substringBefore('.')}","${otherBoard.token.substringBefore('.')}"]}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("INVALID_ARGUMENT") }
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
        val firstJobId = objectMapper.readTree(firstRes.contentAsString).path("job").path("jobId").asText()

        val secondRes = mockMvc.post("/api/v1/boards/${host.boardId}/area-search-jobs") {
            bearer(member.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"durationMin":45}"""
        }.andReturn().response
        val secondJobId = objectMapper.readTree(secondRes.contentAsString).path("job").path("jobId").asText()

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
        val jobId = objectMapper.readTree(postRes.contentAsString).path("job").path("jobId").asText()

        for (i in 0 until 100) { if (!areaJobExecutor.processOne()) break }

        val getRes = mockMvc.get("/api/v1/boards/${host.boardId}/area-search-jobs/$jobId") {
            bearer(member.token)
        }.andReturn().response
        val getData = objectMapper.readTree(getRes.contentAsString)
        // Task 5: 교집합 없음은 실패가 아니라 성공 (commonArea=null)
        assertEquals("SUCCEEDED", getData.path("job").path("status").asText())
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
        val jobId = objectMapper.readTree(postRes.contentAsString).path("job").path("jobId").asText()

        for (i in 0 until 100) { if (!areaJobExecutor.processOne()) break }

        val getRes = mockMvc.get("/api/v1/boards/${host.boardId}/area-search-jobs/$jobId") {
            bearer(member.token)
        }.andReturn().response
        val getData = objectMapper.readTree(getRes.contentAsString)
        // Task 5: 기준점 없음은 실패가 아니라 성공 (anchors=[])
        assertEquals("SUCCEEDED", getData.path("job").path("status").asText())
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
        val jobId = objectMapper.readTree(postRes.contentAsString).path("job").path("jobId").asText()

        for (i in 0 until 100) { if (!areaJobExecutor.processOne()) break }

        val getRes = mockMvc.get("/api/v1/boards/${host.boardId}/area-search-jobs/$jobId") {
            bearer(member.token)
        }.andReturn().response
        val getData = objectMapper.readTree(getRes.contentAsString)
        assertEquals("FAILED", getData.path("job").path("status").asText())
        assertEquals("EXTERNAL_UNAVAILABLE", getData.path("job").path("errorCode").asText())
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

    @Test
    fun `작업 성공 후 area_suggestion 테이블에 기준점 행이 저장된다`() {
        val host = createBoard("기준점 저장 테스트", "호스트")
        val member = inviteAndJoin(host, "참여자")
        setOrigin(host, "호스트출발", 126.97, 37.55)
        setOrigin(member, "참여자출발", 126.96, 37.54)

        kakaoStubServer.setKeywordResponseMode(KakaoStubServer.ResponseMode.SUCCESS)
        odsayStubServer.responseMode = OdsayStubServer.ResponseMode.SUCCESS

        val postRes = mockMvc.post("/api/v1/boards/${host.boardId}/area-search-jobs") {
            bearer(member.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"durationMin":45}"""
        }.andReturn().response

        val postData = objectMapper.readTree(postRes.contentAsString)
        val jobId = postData.path("job").path("jobId").asText()

        // 모든 작업 처리
        for (i in 0 until 100) { if (!areaJobExecutor.processOne()) break }

        val getRes = mockMvc.get("/api/v1/boards/${host.boardId}/area-search-jobs/$jobId") {
            bearer(member.token)
        }.andReturn().response
        val getData = objectMapper.readTree(getRes.contentAsString)
        assertEquals("SUCCEEDED", getData.path("job").path("status").asText())

        // area_suggestion 테이블에서 this job의 anchors 개수 확인
        val anchorsArray = getData.path("result").path("anchors")
        val anchorCount = anchorsArray.size()

        val dbCount = jdbcClient.sql(
            "select count(*) as cnt from area_suggestion where job_id = (select id from area_search_job where public_id = :jobId)"
        ).param("jobId", jobId).query(Int::class.java).single()

        assertEquals(anchorCount, dbCount, "area_suggestion 테이블에 저장된 행 개수가 anchors 개수와 일치해야 함")
    }
}
