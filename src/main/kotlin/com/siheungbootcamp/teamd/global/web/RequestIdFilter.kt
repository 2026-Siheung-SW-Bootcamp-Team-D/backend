package com.siheungbootcamp.teamd.global.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.*

/**
 * HTTP 요청마다 추적 번호(request ID)를 만들고 끝까지 유지하는 필터다.
 *
 * 클라이언트가 올바른 UUID를 보내면 그대로 사용하고, 없거나 잘못된 값이면 서버가 새로 만든다.
 * 이 번호는 응답 헤더와 로그 MDC에 함께 기록되어 한 요청에서 생긴 오류를 쉽게 찾게 한다.
 */
@Component
class RequestIdFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requestId = request.getHeader(REQUEST_ID_HEADER)
            ?.takeIf(::isUuid)
            ?: UUID.randomUUID().toString()

        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId)
        response.setHeader(REQUEST_ID_HEADER, requestId)
        MDC.put(REQUEST_ID_ATTRIBUTE, requestId)

        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(REQUEST_ID_ATTRIBUTE)
        }
    }

    private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess

    companion object {
        const val REQUEST_ID_HEADER = "X-Request-Id"
        const val REQUEST_ID_ATTRIBUTE = "requestId"
    }
}
