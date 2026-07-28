package com.siheungbootcamp.teamd.global.ratelimit

import com.siheungbootcamp.teamd.global.error.BusinessException
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.method.HandlerMethod
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RateLimitInterceptorTest {
    private val interceptor = RateLimitInterceptor(Clock.fixed(Instant.parse("2026-07-28T00:00:00Z"), ZoneOffset.UTC))
    private val controller = FixtureController()

    @Test
    fun `일반 버킷은 1200회를 허용하고 외부 버킷과 분리된다`() {
        val generalHandler = HandlerMethod(controller, FixtureController::class.java.getMethod("general"))
        val externalHandler = HandlerMethod(controller, FixtureController::class.java.getMethod("external"))

        repeat(1200) {
            assertTrue(interceptor.preHandle(request(), MockHttpServletResponse(), generalHandler))
        }
        assertFailsWith<BusinessException> {
            interceptor.preHandle(request(), MockHttpServletResponse(), generalHandler)
        }
        assertTrue(interceptor.preHandle(request(), MockHttpServletResponse(), externalHandler))
    }

    private fun request() = MockHttpServletRequest().apply { remoteAddr = "127.0.0.1" }

    private class FixtureController {
        @RateLimit(permits = 1200, windowSeconds = 60, key = RateLimitKey.IP, scope = RateLimitScope.PARTICIPANT_GLOBAL)
        fun general() = Unit

        @RateLimit(permits = 300, windowSeconds = 60, key = RateLimitKey.IP, scope = RateLimitScope.PARTICIPANT_GLOBAL, bucket = RateLimitBucket.EXTERNAL)
        fun external() = Unit
    }
}
