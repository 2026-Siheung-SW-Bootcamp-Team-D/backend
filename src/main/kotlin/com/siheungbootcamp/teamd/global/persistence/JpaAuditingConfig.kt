package com.siheungbootcamp.teamd.global.persistence

import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaAuditing

/** BaseEntity의 생성·수정 시각을 저장 시점에 자동 기록한다. */
@Configuration
@EnableJpaAuditing
class JpaAuditingConfig
