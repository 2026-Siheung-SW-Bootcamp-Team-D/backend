package com.siheungbootcamp.teamd.global.crypto

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/** 출발지 암호화 키를 환경 설정에서 검증 가능한 불변 객체로 주입한다. */
@Configuration
@EnableConfigurationProperties(CryptoProperties::class)
class CryptoConfig
