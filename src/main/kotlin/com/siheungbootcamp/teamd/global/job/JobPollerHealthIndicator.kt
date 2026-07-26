package com.siheungbootcamp.teamd.global.job

import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("prod")
class JobPollerHealthIndicator(
    private val scheduler: JobPollerScheduler,
) : HealthIndicator {
    override fun health(): Health {
        if (!scheduler.isEnabled()) {
            return Health.down().withDetail("reason", "job poller disabled").build()
        }
        val lastPollStartedAt = scheduler.lastPollStartedAt()
            ?: return Health.down().withDetail("reason", "job poller has not started").build()
        return Health.up().withDetail("lastPollStartedAt", lastPollStartedAt).build()
    }
}
