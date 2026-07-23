package com.siheungbootcamp.teamd.infra.external.odsay

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * ODsay Isochrone API 호출에 필요한 설정.
 *
 * `app.odsay.api-key`(필수, `ODSAY_API_KEY` 환경변수 주입)와 `app.odsay.base-url`(기본값 있음)을
 * `application.yml`에서 읽어온다. [OdsayIsochroneClient]가 이 설정으로 요청 URL과 인증을 구성한다.
 */
@ConfigurationProperties(prefix = "app.odsay")
data class OdsayProperties(
    val apiKey: String = "",
    val baseUrl: String = "https://api.odsay.com",
)
