package com.siheungbootcamp.teamd.global.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/** URL에 포함된 초대 코드와 공개 토큰을 일반 로그용 안전 경로로 치환한다. */
@Component
class MaskingFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        MDC.put("requestPath", mask(request.requestURI))
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove("requestPath")
        }
    }

    companion object {
        private val sensitivePath = Regex("(/(?:invitations|public/schedules)/)[^/]+")
        fun mask(path: String): String = path.replace(sensitivePath, "$1***")
    }
}
