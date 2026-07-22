package com.siheungbootcamp.teamd.global.auth

import org.springframework.core.MethodParameter
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import com.siheungbootcamp.teamd.global.error.BusinessException
import com.siheungbootcamp.teamd.global.error.ErrorCode

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class CurrentParticipant

/** 컨트롤러가 Security API를 직접 다루지 않고 현재 참여자를 받게 한다. */
class CurrentParticipantResolver : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.hasParameterAnnotation(CurrentParticipant::class.java) && parameter.parameterType == ParticipantPrincipal::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): ParticipantPrincipal = (SecurityContextHolder.getContext().authentication?.principal as? ParticipantPrincipal)
        ?: throw BusinessException(ErrorCode.AUTHENTICATION_REQUIRED)
}
