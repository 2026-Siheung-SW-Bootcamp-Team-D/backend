package com.siheungbootcamp.teamd.global.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.util.AntPathMatcher
import org.springframework.web.filter.OncePerRequestFilter

/** Bearer 참여 토큰을 검증해 이후 유스케이스가 사용할 참여자 principal을 만든다. */
@Component
class ParticipantAuthFilter(private val authenticator: ParticipantAuthenticator) : OncePerRequestFilter() {
    private val pathMatcher = AntPathMatcher()
    private val ssePattern = "/api/v1/boards/*/events"

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        resolveToken(request)?.let(ParticipantToken::parse)?.let(authenticator::authenticate)?.let { principal ->
            SecurityContextHolder.getContext().authentication =
                UsernamePasswordAuthenticationToken(principal, null, emptyList())
        }
        filterChain.doFilter(request, response)
    }

    /**
     * `EventSource`는 커스텀 헤더를 붙일 수 없어 SSE 구독 경로에서만 쿼리 파라미터 토큰을 허용한다.
     * 다른 경로까지 쿼리 토큰을 받으면 URL이 로그·리퍼러에 남아 보안 구멍이 되므로 경로를 엄격히 제한한다.
     */
    private fun resolveToken(request: HttpServletRequest): String? {
        val header = request.getHeader("Authorization")
        if (header?.startsWith("Bearer ") == true) return header.removePrefix("Bearer ")
        if (isSseRequest(request)) return request.getParameter("token")
        return null
    }

    private fun isSseRequest(request: HttpServletRequest): Boolean = pathMatcher.match(ssePattern, request.requestURI)
}
