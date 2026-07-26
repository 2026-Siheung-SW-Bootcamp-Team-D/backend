package com.siheungbootcamp.teamd.global.job

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Status

class JobPollerHealthIndicatorTest {
    @Test
    fun `활성화된 작업 폴러는 UP이다`() {
        assertEquals(Status.UP, JobPollerHealthIndicator(enabled = true).health().status)
    }

    @Test
    fun `비활성화된 작업 폴러는 DOWN이다`() {
        assertEquals(Status.DOWN, JobPollerHealthIndicator(enabled = false).health().status)
    }
}
