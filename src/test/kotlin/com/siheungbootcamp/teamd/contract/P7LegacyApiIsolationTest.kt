package com.siheungbootcamp.teamd.contract

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.assertFalse

/**
 * P7 레거시 API 격리 검증
 *
 * 기본 프로필(app.legacy-api-enabled=false)에서 Vote/Course/Departure 경로는
 * OpenAPI에 노출되지 않고, 컴포넌트도 빈으로 등록되지 않는다.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = [
    "app.auth.token-pepper=test-pepper",
    "app.crypto.origin-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
    "app.board.frontend-base-url=https://example.app",
    "app.kakao.rest-key=test-kakao-key",
])
class P7LegacyApiIsolationTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @Test
    fun `default profile hides legacy paths and controllers from OpenAPI`() {
        val api = mockMvc.get("/v3/api-docs")
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        // Legacy Vote paths must not exist
        assertFalse(api.contains("/votes"), "레거시 투표 경로 제거됨")
        assertFalse(api.contains("VoteController"), "레거시 투표 컨트롤러 제거됨")

        // Legacy Course paths must not exist
        assertFalse(api.contains("/course-draft"), "레거시 코스 경로 제거됨")
        assertFalse(api.contains("CourseController"), "레거시 코스 컨트롤러 제거됨")

        // Legacy Departure paths must not exist
        assertFalse(api.contains("/departure-guide"), "레거시 출발 경로 제거됨")
        assertFalse(api.contains("DepartureController"), "레거시 출발 컨트롤러 제거됨")

        // PublicSchedule must not exist in default profile
        assertFalse(api.contains("/public/schedules"), "공개 일정 경로는 기본 비활성화")
        assertFalse(api.contains("PublicScheduleController"), "공개 일정 컨트롤러는 기본 비활성화")
    }

    companion object {
        @Container @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun dynamicProperties(registry: DynamicPropertyRegistry) {
            // Kakao stub not needed for this test
        }
    }
}
