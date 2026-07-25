package com.siheungbootcamp.teamd.domain.board

import com.siheungbootcamp.teamd.global.error.BusinessException
import com.siheungbootcamp.teamd.global.error.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresBoardOpen

/** P2 이후 쓰기 API도 같은 어노테이션으로 CLOSED 보드 변경을 한곳에서 차단하게 한다. */
class BoardOpenInterceptor(private val boards: BoardRepository) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (handler !is HandlerMethod || !handler.hasMethodAnnotation(RequiresBoardOpen::class.java)) return true
        @Suppress("UNCHECKED_CAST")
        val variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as? Map<String, String>
        val boardId = variables?.get("boardId") ?: throw BusinessException(ErrorCode.INVALID_ARGUMENT)
        val board = boards.findByPublicId(boardId) ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        if (board.status == BoardStatus.CLOSED) throw BusinessException(ErrorCode.RESOURCE_CONFLICT)
        return true
    }
}

@Configuration
class BoardWebConfiguration(private val boards: ObjectProvider<BoardRepository>) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        boards.ifAvailable {
            registry.addInterceptor(BoardOpenInterceptor(it)).addPathPatterns("/api/**")
        }
    }
}
