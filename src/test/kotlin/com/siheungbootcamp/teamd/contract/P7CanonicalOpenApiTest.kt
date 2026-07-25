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
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
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
    @Autowired private val objectMapper: ObjectMapper,
) {
    @Test
    fun `canonical paths are exposed and legacy paths are hidden`() {
        val api = mockMvc.get("/v3/api-docs")
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val paths = objectMapper.readTree(api).path("paths")
        val expectedOperations = mapOf(
            "/api/v1/boards" to setOf("post"),
            "/api/v1/boards/{boardId}" to setOf("get", "patch"),
            "/api/v1/boards/{boardId}/invitation" to setOf("get"),
            "/api/v1/invitations/{inviteCode}" to setOf("get"),
            "/api/v1/invitations/{inviteCode}/participants" to setOf("post"),
            "/api/v1/boards/{boardId}/participants" to setOf("get"),
            "/api/v1/boards/{boardId}/participants/me" to setOf("patch"),
            "/api/v1/boards/{boardId}/search/places" to setOf("get"),
            "/api/v1/boards/{boardId}/search/addresses" to setOf("get"),
            "/api/v1/boards/{boardId}/search/reverse-geocode" to setOf("get"),
            "/api/v1/boards/{boardId}/search/nearby-places" to setOf("get"),
            "/api/v1/boards/{boardId}/places" to setOf("get", "post"),
            "/api/v1/boards/{boardId}/places/{placeId}" to setOf("get", "delete"),
            "/api/v1/boards/{boardId}/places/{placeId}/likes/me" to setOf("put", "delete"),
            "/api/v1/boards/{boardId}/places/{placeId}/comments" to setOf("get", "post"),
            "/api/v1/boards/{boardId}/places/{placeId}/comments/{commentId}" to setOf("patch", "delete"),
            "/api/v1/boards/{boardId}/selected-place" to setOf("put", "delete"),
            "/api/v1/boards/{boardId}/area-search-jobs" to setOf("post"),
            "/api/v1/boards/{boardId}/area-search-jobs/{jobId}" to setOf("get"),
        )
        expectedOperations.forEach { (path, methods) ->
            methods.forEach { method ->
                assertTrue(paths.path(path).has(method), "$method $path 경로 필수")
            }
        }
        assertEquals(26, expectedOperations.values.sumOf { it.size }, "canonical API는 26개 연산이어야 함")

        // Task 2: Legacy place search paths must not exist
        assertFalse(api.contains("/place-candidates"), "레거시 장소 후보 경로 제거됨")
        assertFalse(api.contains("/address-candidates"), "레거시 주소 후보 경로 제거됨")
        assertFalse(api.contains("/coordinate-address"), "레거시 좌표 변환 경로 제거됨")
        // Task 6 검증: Vote/Course/Departure 경로 제거
        assertFalse(api.contains("/votes"), "레거시 투표 경로 제거됨")
        assertFalse(api.contains("/course-draft"), "레거시 코스 경로 제거됨")
        assertFalse(api.contains("/departure-guide"), "레거시 출발 경로 제거됨")
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
