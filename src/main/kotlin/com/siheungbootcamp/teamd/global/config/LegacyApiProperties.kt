package com.siheungbootcamp.teamd.global.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * P7 Task 6: 레거시 API 기본 비활성화 설정
 *
 * 기본값: false (Vote·Course·Departure API 비활성화)
 * opt-in으로 활성화 가능: app.legacy-api-enabled=true
 */
@ConfigurationProperties(prefix = "app")
data class LegacyApiProperties(
    var legacyApiEnabled: Boolean = false,
)
