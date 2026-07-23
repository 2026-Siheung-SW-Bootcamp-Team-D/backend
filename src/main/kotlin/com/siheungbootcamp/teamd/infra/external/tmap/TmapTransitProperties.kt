package com.siheungbootcamp.teamd.infra.external.tmap

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * TMAP Transit(대중교통) API 호출에 필요한 설정.
 *
 * `app.tmap.app-key`(필수, `TMAP_APP_KEY` 환경변수 주입)와 `app.tmap.base-url`(기본값 있음)을
 * `application.yml`에서 읽어온다. [TmapTransitClient]가 이 설정으로 요청 URL과 인증 헤더를 구성한다.
 */
@ConfigurationProperties(prefix = "app.tmap")
data class TmapTransitProperties(
    val appKey: String = "",
    val baseUrl: String = "https://apis.openapi.sk.com",
)
