package com.siheungbootcamp.teamd.global.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Swagger UI가 참여 토큰을 표준 Bearer 헤더로 전송하고 P1 계약의 기준 문서를 표시하게 한다. */
@Configuration
class OpenApiConfig {
    @Bean
    fun teamdOpenApi(): OpenAPI = OpenAPI()
        .info(Info().title("teamd-backend API").version("v1").description("약속 보드 API. 인증 API는 보드 생성·참여 응답에서 발급된 참여 토큰을 사용합니다."))
        .components(Components().addSecuritySchemes(
            "participantToken",
            SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("participantId.secret"),
        ))
}
