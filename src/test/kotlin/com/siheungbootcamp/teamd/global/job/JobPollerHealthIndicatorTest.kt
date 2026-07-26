package com.siheungbootcamp.teamd.global.job

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Status

class JobPollerHealthIndicatorTest {
    @Test
    fun `활성화됐지만 아직 실행되지 않은 작업 폴러는 DOWN이다`() {
        val scheduler = JobPollerScheduler(executors = emptyList(), enabled = true)

        assertEquals(Status.DOWN, JobPollerHealthIndicator(scheduler).health().status)
    }

    @Test
    fun `한 번 실행된 작업 폴러는 UP이다`() {
        val scheduler = JobPollerScheduler(executors = emptyList(), enabled = true)
        scheduler.poll()

        assertEquals(Status.UP, JobPollerHealthIndicator(scheduler).health().status)
    }

    @Test
    fun `비활성화된 작업 폴러는 DOWN이다`() {
        val scheduler = JobPollerScheduler(executors = emptyList(), enabled = false)

        assertEquals(Status.DOWN, JobPollerHealthIndicator(scheduler).health().status)
    }
}
