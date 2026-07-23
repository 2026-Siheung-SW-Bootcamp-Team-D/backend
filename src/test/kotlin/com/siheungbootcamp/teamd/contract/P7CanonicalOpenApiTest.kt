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
import kotlin.test.assertTrue

/**
 * P7 canonical OpenAPI 계약 검증
 *
 * 기본 프로필에서 `/v3/api-docs`는 다음을 보장한다:
 * - 신규 canonical 경로들이 존재
 * - 레거시 경로(Vote/Course/Departure)는 없음
 * - 지역 찾기 주변 검색이 존재
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = [
    "app.auth.token-pepper=test-pepper",
    "app.crypto.origin-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
    "app.board.frontend-base-url=https://example.app",
    "app.kakao.rest-key=test-kakao-key",
])
class P7CanonicalOpenApiTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @Test
    fun `canonical paths are exposed and legacy paths are hidden`() {
        val api = mockMvc.get("/v3/api-docs")
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        // Canonical paths must exist (Task 2 paths)
        assertTrue(api.contains("/api/v1/boards/{boardId}/search/places"), "canonical 검색 장소 경로 필수")
        assertTrue(api.contains("/api/v1/boards/{boardId}/search/addresses"), "canonical 검색 주소 경로 필수")
        assertTrue(api.contains("/api/v1/boards/{boardId}/search/reverse-geocode"), "canonical 역지오코딩 경로 필수")
        // Task 3에서 구현: assertTrue(api.contains("/api/v1/boards/{boardId}/search/nearby-places"), "canonical 주변 검색 경로 필수")

        // Task 2: Legacy place search paths must not exist
        assertFalse(api.contains("/place-candidates"), "레거시 장소 후보 경로 제거됨")
        assertFalse(api.contains("/address-candidates"), "레거시 주소 후보 경로 제거됨")
        assertFalse(api.contains("/coordinate-address"), "레거시 좌표 변환 경로 제거됨")
        // Task 6에서 검증: Vote/Course/Departure 경로 제거
        // assertFalse(api.contains("/votes"), "레거시 투표 경로 제거됨")
        // assertFalse(api.contains("/course-draft"), "레거시 코스 경로 제거됨")
        // assertFalse(api.contains("/departure-guide"), "레거시 출발 경로 제거됨")
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
