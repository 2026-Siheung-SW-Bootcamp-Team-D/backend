package com.siheungbootcamp.teamd.global.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import com.siheungbootcamp.teamd.global.auth.CurrentParticipantResolver
import com.siheungbootcamp.teamd.global.ratelimit.RateLimitInterceptor
import com.siheungbootcamp.teamd.global.web.CacheControlInterceptor
import org.springframework.web.method.support.HandlerMethodArgumentResolver

/**
 * 브라우저에서 API를 호출할 때 적용할 공통 웹 설정이다.
 *
 * 현재는 프론트엔드 개발 서버가 API 경로에 요청할 수 있도록 CORS를 설정한다.
 * 허용 출처는 `app.cors.allowed-origins` 환경 설정으로 바꿀 수 있으므로 운영 주소를 코드에 넣지 않는다.
 */
@Configuration
@EnableConfigurationProperties(CorsProperties::class)
class WebConfig(private val corsProperties: CorsProperties) : WebMvcConfigurer {

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/**")
            .allowedOrigins(*corsProperties.allowedOrigins.toTypedArray())
            .allowedOriginPatterns(*corsProperties.allowedOriginPatterns.toTypedArray())
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("Authorization", "Content-Type", "X-Request-Id", "If-Match")
            .exposedHeaders("ETag", "Location", "Retry-After", "X-Request-Id")
            .maxAge(3600)
    }

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(CacheControlInterceptor()).addPathPatterns("/api/**")
        registry.addInterceptor(RateLimitInterceptor()).addPathPatterns("/api/**")
    }

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(CurrentParticipantResolver())
    }
}

/** 운영 도메인은 정확히, 매번 달라지는 Preview 도메인은 프로젝트 범위 패턴으로 제한한다. */
@ConfigurationProperties("app.cors")
data class CorsProperties(
    val allowedOrigins: List<String> = listOf("http://localhost:5173"),
    val allowedOriginPatterns: List<String> = emptyList(),
)
