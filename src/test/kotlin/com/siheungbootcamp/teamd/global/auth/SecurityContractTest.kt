package com.siheungbootcamp.teamd.global.auth

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import com.siheungbootcamp.teamd.global.web.RequestIdFilter
import com.siheungbootcamp.teamd.global.config.WebConfig
import org.springframework.test.web.servlet.options
import org.springframework.test.context.TestPropertySource

@WebMvcTest(ProtectedTestController::class)
@AutoConfigureMockMvc
@Import(SecurityConfig::class, ParticipantAuthFilter::class, RequestIdFilter::class, WebConfig::class)
@TestPropertySource(properties = ["app.cors.allowed-origin-patterns=https://team-d-*.vercel.app"])
class SecurityContractTest(@Autowired private val mockMvc: MockMvc) {
    @MockitoBean
    private lateinit var participantAuthenticator: ParticipantAuthenticator

    @Test
    fun `보호 경로의 토큰 없음과 잘못된 토큰은 같은 401 계약으로 수렴한다`() {
        listOf(null, "Bearer malformed").forEach { authorization ->
            mockMvc.get("/api/v1/protected") {
                accept = MediaType.APPLICATION_JSON
                if (authorization != null) header("Authorization", authorization)
            }.andExpect {
                status { isUnauthorized() }
                header { exists("X-Request-Id") }
                jsonPath("$.error.code") { value("AUTHENTICATION_REQUIRED") }
                jsonPath("$.error.message") { value("인증이 필요합니다.") }
                jsonPath("$.error.details") { isMap() }
                jsonPath("$.error.requestId") { isNotEmpty() }
            }
        }
    }

    @Test
    fun `허용 origin의 보호 API preflight는 인증 없이 성공한다`() {
        mockMvc.options("/api/v1/protected") {
            header("Origin", "http://localhost:5173")
            header("Access-Control-Request-Method", "GET")
            header("Access-Control-Request-Headers", "Authorization")
        }.andExpect {
            status { isOk() }
            header { string("Access-Control-Allow-Origin", "http://localhost:5173") }
            header { string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("GET")) }
            header { string("Access-Control-Allow-Headers", org.hamcrest.Matchers.containsStringIgnoringCase("Authorization")) }
        }
    }

    @Test
    fun `미허용 origin의 보호 API preflight는 거부한다`() {
        mockMvc.options("/api/v1/protected") {
            header("Origin", "https://attacker.example")
            header("Access-Control-Request-Method", "GET")
        }.andExpect {
            status { isForbidden() }
            header { doesNotExist("Access-Control-Allow-Origin") }
        }
    }

    @Test
    fun `설정된 Vercel Preview 패턴의 preflight는 성공한다`() {
        mockMvc.options("/api/v1/protected") {
            header("Origin", "https://team-d-git-feature-example.vercel.app")
            header("Access-Control-Request-Method", "GET")
        }.andExpect {
            status { isOk() }
            header { string("Access-Control-Allow-Origin", "https://team-d-git-feature-example.vercel.app") }
        }
    }
}

@RestController
class ProtectedTestController {
    @GetMapping("/api/v1/protected")
    fun protected(): String = "ok"
}
