package com.siheungbootcamp.teamd.infra.external.kakao

import org.springframework.boot.context.properties.ConfigurationProperties

/** Kakao Local API 설정을 환경변수와 프로필별 파일에서 읽는다. */
@ConfigurationProperties("app.kakao")
data class KakaoLocalProperties(
    val restKey: String = "",
    val baseUrl: String = "https://dapi.kakao.com",
)
