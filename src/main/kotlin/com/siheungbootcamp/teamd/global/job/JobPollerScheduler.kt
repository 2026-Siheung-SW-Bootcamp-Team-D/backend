package com.siheungbootcamp.teamd.global.job

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * 모든 JobExecutor 구현체를 폴링하는 @Scheduled 스케줄러.
 *
 * 단일 프로세스·단일 VM 환경에서 1초 간격으로 각 JobExecutor.processOne()을 호출한다.
 * 각 executor는 한 건씩 처리한 뒤 반환하고, 폴러는 계속 루프를 돈다.
 *
 * app.job.enabled=false일 때는 정지한다(테스트에서 @Scheduled를 막기 위해).
 */
@Component
class JobPollerScheduler(
    private val executors: List<JobExecutor>,
    @Value("\${app.job.enabled:false}") private val enabled: Boolean,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    @Volatile private var lastPollStartedAt: Instant? = null

    @PostConstruct
    fun logConfiguration() {
        logger.info("job_poller_configuration enabled=$enabled executorCount=${executors.size}")
    }

    @Scheduled(fixedDelay = 1000)
    fun poll() {
        if (!enabled) return
        lastPollStartedAt = Instant.now()

        for (executor in executors) {
            try {
                while (executor.processOne()) {
                    // processOne이 true 반환하면 더 처리할 작업 있으므로 계속
                }
            } catch (e: Exception) {
                logger.error("job_executor_error executor=${executor.javaClass.simpleName} error=${e.message}", e)
            }
        }
    }

    fun isEnabled(): Boolean = enabled

    fun lastPollStartedAt(): Instant? = lastPollStartedAt
}
