package com.siheungbootcamp.teamd.domain.board

import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.hasKey
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Testcontainers
@ExtendWith(OutputCaptureExtension::class)
@AutoConfigureMockMvc
@SpringBootTest(properties = [
    "app.auth.token-pepper=test-pepper",
    "app.crypto.origin-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
    "app.board.frontend-base-url=https://example.app",
])
class P1ContractTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcClient: JdbcClient,
) {
    @Test
    fun `V1-1 생성 초대 확인 참여 보드 조회 흐름과 V1-2 토큰 접근이 성공한다`() {
        val host = createBoard("흐름 보드", "호스트")
        val invitation = mockMvc.get("/api/v1/invitations/${host.inviteCode}").andExpect {
            status { isOk() }
            jsonPath("$.boardId") { value(host.boardId) }
            jsonPath("$.joinable") { value(true) }
        }.andReturn().response.contentAsString
        assertFalse(invitation.contains("participantToken"))

        mockMvc.post("/api/v1/invitations/${host.inviteCode}/participants") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"nickname":"멤버"}"""
        }.andExpect { status { isCreated() }; jsonPath("$.role") { value("MEMBER") } }

        mockMvc.get("/api/v1/boards/${host.boardId}") { bearer(host.token) }.andExpect {
            status { isOk() }
            jsonPath("$.boardId") { value(host.boardId) }
        }
    }

    @Test
    fun `V1-3 다른 보드 토큰 접근은 존재를 숨겨 404다`() {
        val a = createBoard("보드 에이", "에이")
        val b = createBoard("보드 비", "비")
        mockMvc.get("/api/v1/boards/${b.boardId}") { bearer(a.token) }.andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("RESOURCE_NOT_FOUND") }
        }
    }

    @Test
    fun `V1-4 멤버는 호스트 수정 API를 호출할 수 없다`() {
        val host = createBoard("권한 보드", "호스트")
        val member = join(host.inviteCode, "멤버")
        mockMvc.patch("/api/v1/boards/${host.boardId}") {
            bearer(member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"수정 이름"}"""
        }.andExpect { status { isForbidden() }; jsonPath("$.error.code") { value("FORBIDDEN") } }
    }

    @Test
    fun `V1-5 만료 초대 코드는 존재 여부를 숨겨 404다`() {
        val host = createBoard("만료 보드", "호스트")
        jdbcClient.sql("update board set invite_expires_at=:expired where public_id=:id")
            .param("expired", OffsetDateTime.parse("2000-01-01T00:00:00Z")).param("id", host.boardId).update()
        mockMvc.get("/api/v1/invitations/${host.inviteCode}").andExpect {
            status { isNotFound() }; jsonPath("$.error.code") { value("INVITE_NOT_FOUND") }
        }
    }

    @Test
    fun `T1-4b 활성 지역 작업 대상은 출발지를 바꿀 수 없다`() {
        val host = createBoard("잠금 보드", "호스트")
        val participantId = host.token.substringBefore('.')
        jdbcClient.sql("insert into area_search_job(public_id,board_id,status,duration_min,snapshot) select 'job_01HZZZZZZZZZZZZZZZZZZZZZZZ',id,'QUEUED',30,cast(:snapshot as jsonb) from board where public_id=:boardId")
            .param("snapshot", "[\"$participantId\"]").param("boardId", host.boardId).update()
        mockMvc.patch("/api/v1/boards/${host.boardId}/participants/me") {
            bearer(host.token); contentType = MediaType.APPLICATION_JSON
            content = """{"origin":{"label":"정왕역","lon":126.7,"lat":37.3,"source":"MANUAL_PIN"}}"""
        }.andExpect { status { isConflict() }; jsonPath("$.error.code") { value("RESOURCE_CONFLICT") } }
    }

    @Test
    fun `V1-8 토큰 secret은 발급 응답 외 응답과 로그에 나타나지 않는다`(output: CapturedOutput) {
        val host = createBoard("마스킹 보드", "호스트")
        val secret = host.token.substringAfter('.')
        val bodies = listOf(
            mockMvc.get("/api/v1/boards/${host.boardId}") { bearer(host.token) }.andReturn().response.contentAsString,
            mockMvc.get("/api/v1/boards/${host.boardId}/participants") { bearer(host.token) }.andReturn().response.contentAsString,
            mockMvc.get("/api/v1/boards/${host.boardId}/invitation") { bearer(host.token) }.andReturn().response.contentAsString,
        )
        assertTrue(bodies.none { it.contains("participantToken") || it.contains(secret) })
        assertFalse(output.all.contains(secret))
    }

    @Test
    fun `Swagger 문서는 P1 엔드포인트와 Bearer 참여 토큰 인증을 공개한다`() {
        mockMvc.get("/v3/api-docs").andExpect {
            status { isOk() }
            jsonPath("$.info.title") { value("teamd-backend API") }
            jsonPath("$.components.securitySchemes.participantToken.type") { value("http") }
            jsonPath("$.components.securitySchemes.participantToken.scheme") { value("bearer") }
            jsonPath("$.paths['/api/v1/boards'].post.summary") { value("보드 생성") }
            jsonPath("$.paths['/api/v1/boards/{boardId}/participants/me'].patch.security[0].participantToken") { isArray() }
            jsonPath("$.paths['/api/v1/boards/{boardId}'].get.parameters.length()") { value(1) }
            jsonPath("$.paths['/api/v1/boards/{boardId}'].get.parameters[0].name") { value("boardId") }
        }
    }

    @Test
    fun `오늘은 거부하고 내일은 허용하며 보드 이름 앞뒤 공백을 제거한다`() {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        mockMvc.post("/api/v1/boards") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"오늘 보드","dateRange":{"start":"$today","end":"$today"},"hostNickname":"호스트"}"""
        }.andExpect { status { isBadRequest() }; jsonPath("$.error.code") { value("INVALID_ARGUMENT") } }

        val tomorrow = today.plusDays(1)
        mockMvc.post("/api/v1/boards") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"  내일 보드  ","dateRange":{"start":"$tomorrow","end":"$tomorrow"},"hostNickname":"호스트"}"""
        }.andExpect { status { isCreated() }; jsonPath("$.board.name") { value("내일 보드") } }
    }

    @Test
    fun `초대 코드는 공백과 대소문자를 무시해 확인하고 참여한다`() {
        val host = createBoard("정규화 보드", "호스트")
        val input = "  ${host.inviteCode.lowercase()}  "
        mockMvc.get("/api/v1/invitations/$input").andExpect { status { isOk() }; jsonPath("$.boardId") { value(host.boardId) } }
        mockMvc.post("/api/v1/invitations/$input/participants") {
            contentType = MediaType.APPLICATION_JSON; content = """{"nickname":"멤버"}"""
        }.andExpect { status { isCreated() } }
    }

    @Test
    fun `참여 토큰을 발급하는 두 응답은 캐시를 금지한다`() {
        val created = mockMvc.post("/api/v1/boards") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"캐시 보드","dateRange":{"start":"2099-01-01","end":"2099-01-01"},"hostNickname":"호스트"}"""
        }.andExpect { status { isCreated() }; header { string("Cache-Control", "private, no-store") } }
            .andReturn().response.contentAsString
        val inviteCode = objectMapper.readTree(created)["invitation"]["inviteCode"].asText()
        mockMvc.post("/api/v1/invitations/$inviteCode/participants") {
            contentType = MediaType.APPLICATION_JSON; content = """{"nickname":"멤버"}"""
        }.andExpect { status { isCreated() }; header { string("Cache-Control", "private, no-store") } }
    }

    @Test
    fun `잘못된 날짜와 origin enum은 400 INVALID_ARGUMENT다`() {
        mockMvc.post("/api/v1/boards") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"파싱 보드","dateRange":{"start":"not-a-date","end":"2099-01-01"},"hostNickname":"호스트"}"""
        }.andExpect { status { isBadRequest() }; jsonPath("$.error.code") { value("INVALID_ARGUMENT") } }

        val host = createBoard("enum 보드", "호스트")
        mockMvc.patch("/api/v1/boards/${host.boardId}/participants/me") {
            bearer(host.token); contentType = MediaType.APPLICATION_JSON
            content = """{"origin":{"label":"역","lon":126.7,"lat":37.3,"source":"UNKNOWN"}}"""
        }.andExpect { status { isBadRequest() }; jsonPath("$.error.code") { value("INVALID_ARGUMENT") } }
    }

    @Test
    fun `참여자 전체 60회 제한은 여러 엔드포인트가 같은 버킷을 사용한다`() {
        val host = createBoard("제한 보드", "호스트")
        val rateLimited = (1..70).any { requestNumber ->
            val path = if (requestNumber % 2 == 0) "/api/v1/boards/${host.boardId}" else "/api/v1/boards/${host.boardId}/participants"
            mockMvc.get(path) { bearer(host.token) }.andReturn().response.status == 429
        }
        assertTrue(rateLimited, "서로 다른 보호 API가 참여자 전체 버킷을 공유해야 한다")
    }

    @Test
    fun `보드 수정 응답 updatedAt은 생성 시각보다 뒤다`() {
        val host = createBoard("시간 보드", "호스트")
        val before = mockMvc.get("/api/v1/boards/${host.boardId}") { bearer(host.token) }.andReturn().response.contentAsString
        val after = mockMvc.patch("/api/v1/boards/${host.boardId}") {
            bearer(host.token); contentType = MediaType.APPLICATION_JSON; content = """{"name":"수정 시간 보드"}"""
        }.andReturn().response.contentAsString
        assertTrue(Instant.parse(objectMapper.readTree(after)["updatedAt"].asText()).isAfter(Instant.parse(objectMapper.readTree(before)["updatedAt"].asText())))
    }

    @Test
    fun `V1-6 타인의 출발지는 등록 여부 외 키를 노출하지 않고 V1-7 종료 보드 쓰기는 막는다`() {
        val host = createBoard("개인정보 보드", "호스트")
        val member = join(host.inviteCode, "멤버")
        mockMvc.patch("/api/v1/boards/${host.boardId}/participants/me") {
            bearer(member)
            contentType = MediaType.APPLICATION_JSON
            content = """{"origin":{"label":"정왕역","lon":126.7426,"lat":37.3459,"source":"MANUAL_PIN"}}"""
        }.andExpect { status { isOk() } }
        mockMvc.get("/api/v1/boards/${host.boardId}/participants") { bearer(host.token) }.andExpect {
            status { isOk() }
            jsonPath("$.items[1].origin.registered") { value(true) }
            jsonPath("$.items[1].origin", not(hasKey<String>("label")))
            jsonPath("$.items[1].origin", not(hasKey<String>("lon")))
            jsonPath("$.items[1].origin", not(hasKey<String>("lat")))
        }
        mockMvc.patch("/api/v1/boards/${host.boardId}") {
            bearer(host.token); contentType = MediaType.APPLICATION_JSON; content = """{"status":"CLOSED"}"""
        }.andExpect { status { isOk() } }
        mockMvc.patch("/api/v1/boards/${host.boardId}/participants/me") {
            bearer(member); contentType = MediaType.APPLICATION_JSON; content = """{"nickname":"새 이름"}"""
        }.andExpect { status { isConflict() }; jsonPath("$.error.code") { value("RESOURCE_CONFLICT") } }
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
            contentType = MediaType.APPLICATION_JSON; content = """{"nickname":"$nickname"}"""
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        return objectMapper.readTree(body)["participantToken"].asText()
    }

    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.bearer(token: String) {
        header("Authorization", "Bearer $token")
    }

    private data class CreatedBoard(val boardId: String, val token: String, val inviteCode: String)

    companion object {
        @Container @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }
}
