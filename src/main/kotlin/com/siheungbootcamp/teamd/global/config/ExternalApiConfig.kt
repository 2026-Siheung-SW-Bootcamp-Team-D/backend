package com.siheungbootcamp.teamd.global.config

import com.siheungbootcamp.teamd.global.external.ExternalApiClient
import com.siheungbootcamp.teamd.global.external.DailyQuotaManager
import com.siheungbootcamp.teamd.infra.external.kakao.KakaoLocalProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient

/** 외부 API 호출을 위한 공통 설정을 정의한다. */
@Configuration
@EnableConfigurationProperties(KakaoLocalProperties::class)
class ExternalApiConfig {

    @Bean
    fun dailyQuotaManager(): DailyQuotaManager {
        return DailyQuotaManager(
            quotas = mapOf("KAKAO" to 10000)
        )
    }

    @Bean
    fun kakaoRestClient(): RestClient {
        val factory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(3000)  // 3 seconds
            setReadTimeout(5000)     // 5 seconds
        }
        return RestClient.builder()
            .requestFactory(factory)
            .build()
    }

    @Bean
    fun kakaoExternalApiClient(kakaoRestClient: RestClient, quotaManager: DailyQuotaManager): ExternalApiClient {
        return ExternalApiClient("KAKAO", kakaoRestClient, quotaManager)
    }
}
