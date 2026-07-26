package com.siheungbootcamp.teamd.global.job

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("prod")
class JobPollerHealthIndicator(
    @Value("\${app.job.enabled:false}") private val enabled: Boolean,
) : HealthIndicator {
    override fun health(): Health =
        if (enabled) {
            Health.up().build()
        } else {
            Health.down().withDetail("reason", "job poller disabled").build()
        }
}
