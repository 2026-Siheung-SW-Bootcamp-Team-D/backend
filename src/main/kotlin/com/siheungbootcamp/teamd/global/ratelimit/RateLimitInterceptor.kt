package com.siheungbootcamp.teamd.global.ratelimit

import com.siheungbootcamp.teamd.global.auth.ParticipantPrincipal
import com.siheungbootcamp.teamd.global.error.BusinessException
import com.siheungbootcamp.teamd.global.error.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import org.springframework.web.servlet.HandlerMapping

/** 단계별 API가 선언한 한도만 적용하는 단일 VM용 인메모리 고정 윈도우 골격이다. */
class RateLimitInterceptor(private val clock: Clock = Clock.systemUTC()) : HandlerInterceptor {
    private val buckets = ConcurrentHashMap<String, Bucket>()

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val method = handler as? HandlerMethod ?: return true
        val limit = method.getMethodAnnotation(RateLimit::class.java) ?: return true
        val bucketScope = when (limit.scope) {
            RateLimitScope.ENDPOINT -> method.method.toGenericString()
            RateLimitScope.PARTICIPANT_GLOBAL -> "participant-global"
        }
        val key = "$bucketScope:${resolveKey(limit.key, request)}"
        val now = clock.instant().epochSecond
        val bucket = buckets.compute(key) { _, current ->
            val previous = current ?: Bucket(limit.permits.toDouble(), now)
            val refillRate = limit.permits.toDouble() / limit.windowSeconds
            val available = (previous.tokens + (now - previous.updatedAt) * refillRate).coerceAtMost(limit.permits.toDouble())
            Bucket(available - 1.0, now)
        } ?: return true
        if (bucket.tokens < 0) {
            val refillRate = limit.permits.toDouble() / limit.windowSeconds
            response.setHeader("Retry-After", ceil(-bucket.tokens / refillRate).toLong().coerceAtLeast(1).toString())
            throw BusinessException(ErrorCode.RATE_LIMITED)
        }
        return true
    }

    private fun resolveKey(type: RateLimitKey, request: HttpServletRequest): String = when (type) {
        RateLimitKey.IP -> request.remoteAddr
        RateLimitKey.BOARD -> {
            val variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as? Map<*, *>
            variables?.get("boardId")?.toString() ?: request.requestURI
        }
        RateLimitKey.PARTICIPANT -> (SecurityContextHolder.getContext().authentication?.principal as? ParticipantPrincipal)?.participantId?.toString() ?: request.remoteAddr
    }

    private data class Bucket(val tokens: Double, val updatedAt: Long)
}
