package com.siheungbootcamp.teamd.global.auth

import com.siheungbootcamp.teamd.global.error.ErrorCode
import com.siheungbootcamp.teamd.global.error.ErrorResponse
import com.siheungbootcamp.teamd.global.web.RequestIdFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import tools.jackson.databind.ObjectMapper

/** 로그인 세션 없이 참여 토큰 하나만 사용하는 API 보안 경계를 구성한다. */
@Configuration
@EnableConfigurationProperties(AuthProperties::class)
class SecurityConfig {
    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        participantAuthFilter: ParticipantAuthFilter,
        requestIdFilter: RequestIdFilter,
        authenticationEntryPoint: AuthenticationEntryPoint,
    ): SecurityFilterChain = http
        .cors { }
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .formLogin { it.disable() }
        .httpBasic { it.disable() }
        .exceptionHandling { it.authenticationEntryPoint(authenticationEntryPoint) }
        .authorizeHttpRequests {
            it.requestMatchers("/actuator/health").permitAll()
            it.requestMatchers(HttpMethod.POST, "/api/v1/boards").permitAll()
            it.requestMatchers(HttpMethod.GET, "/api/v1/invitations/*").permitAll()
            it.requestMatchers(HttpMethod.POST, "/api/v1/invitations/*/participants").permitAll()
            it.requestMatchers(HttpMethod.GET, "/api/v1/public/schedules/*").permitAll()
            it.anyRequest().authenticated()
        }
        .addFilterBefore(requestIdFilter, UsernamePasswordAuthenticationFilter::class.java)
        .addFilterAfter(participantAuthFilter, RequestIdFilter::class.java)
        .build()

    @Bean
    fun authenticationEntryPoint(objectMapper: ObjectMapper): AuthenticationEntryPoint = AuthenticationEntryPoint {
            request: HttpServletRequest,
            response: HttpServletResponse,
            _: org.springframework.security.core.AuthenticationException,
        ->
        val requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)?.toString() ?: "unknown"
        response.status = ErrorCode.AUTHENTICATION_REQUIRED.status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.setHeader("Cache-Control", "private, no-store")
        objectMapper.writeValue(
            response.outputStream,
            ErrorResponse.from(ErrorCode.AUTHENTICATION_REQUIRED, emptyMap(), requestId),
        )
    }
}
