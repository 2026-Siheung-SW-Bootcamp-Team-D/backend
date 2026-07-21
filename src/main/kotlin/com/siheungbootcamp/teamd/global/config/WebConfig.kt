package com.siheungbootcamp.teamd.global.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * 브라우저에서 API를 호출할 때 적용할 공통 웹 설정이다.
 *
 * 현재는 프론트엔드 개발 서버가 API 경로에 요청할 수 있도록 CORS를 설정한다.
 * 허용 출처는 `app.cors.allowed-origins` 환경 설정으로 바꿀 수 있으므로 운영 주소를 코드에 넣지 않는다.
 */
@Configuration
class WebConfig(
    @Value("\${app.cors.allowed-origins:http://localhost:5173}")
    private val allowedOrigins: List<String>,
) : WebMvcConfigurer {

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/**")
            .allowedOrigins(*allowedOrigins.toTypedArray())
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("Authorization", "Content-Type", "X-Request-Id", "If-Match")
            .exposedHeaders("ETag", "Location", "Retry-After", "X-Request-Id")
            .maxAge(3600)
    }
}
