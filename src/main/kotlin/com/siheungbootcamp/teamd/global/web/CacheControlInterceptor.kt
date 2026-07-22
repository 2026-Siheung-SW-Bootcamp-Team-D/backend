package com.siheungbootcamp.teamd.global.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.servlet.HandlerInterceptor

/** 인증 정보가 포함될 수 있는 API 응답을 브라우저·프록시 캐시에 남기지 않는다. */
class CacheControlInterceptor : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (request.getHeader("Authorization") != null) response.setHeader("Cache-Control", "private, no-store")
        return true
    }
}
