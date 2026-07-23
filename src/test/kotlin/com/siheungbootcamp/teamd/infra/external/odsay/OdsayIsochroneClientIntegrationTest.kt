package com.siheungbootcamp.teamd.infra.external.odsay

import com.siheungbootcamp.teamd.global.error.BusinessException
import com.siheungbootcamp.teamd.global.error.ErrorCode
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * OdsayIsochroneClient와 OdsayStubServer의 통합 테스트.
 *
 * OdsayStubServer의 5가지 응답 모드를 모두 검증한다:
 * - SUCCESS_POLYGON: 200 단순 폴리곤 응답 -> 성공
 * - SUCCESS_MULTIPOLYGON: 200 MultiPolygon 응답 -> 성공
 * - TOO_MANY_REQUESTS: 429 1회 이후 재시도 성공 -> 재시도 동작 확인
 * - SERVER_ERROR: 500 고정 -> 재시도 소진 후 EXTERNAL_UNAVAILABLE 예외
 * - MALFORMED: 200이지만 계약 위반(feature 없음) -> EXTERNAL_BAD_RESPONSE 예외
 */
@Testcontainers
@SpringBootTest(
    properties = [
        "app.auth.token-pepper=test-pepper",
        "app.crypto.origin-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "app.odsay.api-key=test-odsay-key",
    ]
)
class OdsayIsochroneClientIntegrationTest(
    @Autowired private val client: OdsayIsochroneClient,
) {
    @Test
    fun `SUCCESS_POLYGON - 단순 폴리곤 응답을 파싱한다`() {
        odsayStubServer.responseMode = OdsayStubServer.ResponseMode.SUCCESS_POLYGON
        odsayStubServer.resetCount()

        val result = client.fetch(lon = 127.0, lat = 37.3, durationMin = 30)

        assertEquals("Polygon", result.geometryType)
        assertTrue(result.isValid, "폴리곤이 유효해야 함")
        assertTrue(!result.isEmpty, "폴리곤이 비어있지 않아야 함")
    }

    @Test
    fun `SUCCESS_MULTIPOLYGON - MultiPolygon 응답을 파싱한다`() {
        odsayStubServer.responseMode = OdsayStubServer.ResponseMode.SUCCESS_MULTIPOLYGON
        odsayStubServer.resetCount()

        val result = client.fetch(lon = 127.0, lat = 37.3, durationMin = 30)

        assertEquals("MultiPolygon", result.geometryType)
        assertTrue(result.isValid, "MultiPolygon이 유효해야 함")
        assertTrue(!result.isEmpty, "MultiPolygon이 비어있지 않아야 함")
    }

    @Test
    fun `TOO_MANY_REQUESTS - 429 응답 후 재시도가 성공한다`() {
        odsayStubServer.responseMode = OdsayStubServer.ResponseMode.TOO_MANY_REQUESTS
        odsayStubServer.resetCount()

        val result = client.fetch(lon = 127.0, lat = 37.3, durationMin = 30)

        assertEquals("Polygon", result.geometryType)
        assertTrue(result.isValid)
        // ExternalApiClient의 재시도 로직으로 인해 여러 번 요청됨
        assertTrue(odsayStubServer.requestCount() > 1, "429 이후 재시도가 일어나야 함")
    }

    @Test
    fun `SERVER_ERROR - 500 고정이면 EXTERNAL_UNAVAILABLE 예외가 발생한다`() {
        odsayStubServer.responseMode = OdsayStubServer.ResponseMode.SERVER_ERROR
        odsayStubServer.resetCount()

        val exception = assertFails {
            client.fetch(lon = 127.0, lat = 37.3, durationMin = 30)
        } as BusinessException

        assertEquals(ErrorCode.EXTERNAL_UNAVAILABLE, exception.errorCode)
    }

    @Test
    fun `MALFORMED - 계약 위반(feature 없음) 응답은 EXTERNAL_BAD_RESPONSE 예외가 발생한다`() {
        odsayStubServer.responseMode = OdsayStubServer.ResponseMode.MALFORMED
        odsayStubServer.resetCount()

        val exception = assertFails {
            client.fetch(lon = 127.0, lat = 37.3, durationMin = 30)
        } as BusinessException

        assertEquals(ErrorCode.EXTERNAL_BAD_RESPONSE, exception.errorCode)
    }

    companion object {
        @Container @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        private val odsayStubServer = OdsayStubServer()

        init {
            odsayStubServer.start()
        }

        @DynamicPropertySource
        @JvmStatic
        fun dynamicProperties(registry: DynamicPropertyRegistry) {
            registry.add("app.odsay.base-url") { odsayStubServer.baseUrl }
        }
    }
}
