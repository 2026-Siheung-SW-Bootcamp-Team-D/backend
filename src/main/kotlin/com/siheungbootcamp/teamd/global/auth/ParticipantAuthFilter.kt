package com.siheungbootcamp.teamd.global.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/** Bearer 참여 토큰을 검증해 이후 유스케이스가 사용할 참여자 principal을 만든다. */
@Component
class ParticipantAuthFilter(private val authenticator: ParticipantAuthenticator) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val raw = request.getHeader("Authorization")
        if (raw?.startsWith("Bearer ") == true) {
            ParticipantToken.parse(raw.removePrefix("Bearer "))?.let(authenticator::authenticate)?.let { principal ->
                SecurityContextHolder.getContext().authentication =
                    UsernamePasswordAuthenticationToken(principal, null, emptyList())
            }
        }
        filterChain.doFilter(request, response)
    }
}
