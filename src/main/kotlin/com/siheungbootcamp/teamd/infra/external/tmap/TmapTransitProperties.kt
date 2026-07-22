package com.siheungbootcamp.teamd.infra.external.tmap

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.tmap")
data class TmapTransitProperties(
    val appKey: String = "",
    val baseUrl: String = "https://apis.openapi.sk.com",
)
