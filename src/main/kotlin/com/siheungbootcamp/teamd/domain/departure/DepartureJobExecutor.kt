package com.siheungbootcamp.teamd.domain.departure

import com.siheungbootcamp.teamd.domain.course.CourseStopRepository
import com.siheungbootcamp.teamd.domain.course.CourseStopRole
import com.siheungbootcamp.teamd.global.crypto.OriginCipher
import com.siheungbootcamp.teamd.global.job.JobExecutor
import com.siheungbootcamp.teamd.infra.external.tmap.TmapTransitClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.nio.ByteBuffer
import java.time.Instant
import java.time.temporal.ChronoUnit

/** [DepartureJobExecutor.processOne]이 외부 호출 없이 읽어야 하는, CALCULATING 행 하나의 처리 재료. */
data class DepartureProcessingTarget(
    val calculationId: Long,
    val originCiphertext: ByteArray?,
    val destLon: Double?,
    val destLat: Double?,
    val destScheduledAt: Instant?,
)

/**
 * CALCULATING 행을 읽기 전용 트랜잭션 하나 안에서 조회하는 전담 빈.
 *
 * [DepartureJobExecutor]가 이 메서드를 자기 자신이 아니라 별도 빈으로 호출해야 `@Transactional`이
 * Spring 프록시를 거친다(같은 객체 내부 호출은 프록시를 우회해 트랜잭션이 걸리지 않는다).
 * 코스의 첫 만남 장소까지 이 짧은 트랜잭션 안에서 미리 읽어, 트랜잭션이 끝난 뒤에는
 * 지연 로딩을 건드리지 않는다.
 */
@Component
class DepartureCalculationReader(
    private val departureRepository: DepartureCalculationRepository,
    private val courseStopRepository: CourseStopRepository,
) {
    @Transactional(readOnly = true)
    fun loadNextTarget(): DepartureProcessingTarget? {
        val calculation = departureRepository.findAllCalculating().firstOrNull() ?: return null
        val participant = calculation.participant
        val courseId = requireNotNull(calculation.course.id)

        val courseStops = courseStopRepository.findByCourseIdOrderByOrderIndex(courseId)
        val firstMeeting = courseStops.firstOrNull { it.role == CourseStopRole.FIRST_MEETING.name }
            ?: courseStops.firstOrNull()

        return DepartureProcessingTarget(
            calculationId = requireNotNull(calculation.id),
            originCiphertext = participant.originCiphertext,
            destLon = firstMeeting?.place?.lon,
            destLat = firstMeeting?.place?.lat,
            destScheduledAt = firstMeeting?.scheduledAt,
        )
    }
}

/**
 * 출발 안내 계산을 실행하는 Job Executor.
 *
 * 흐름:
 * 1. [DepartureCalculationReader]의 짧은 읽기 전용 트랜잭션으로 CALCULATING 행 하나를 조회
 * 2. 트랜잭션 밖에서 좌표 복호화, TMAP Transit 호출(최대 3회 재시도), 재시도 대기
 * 3. 결과에 맞는 markReady/markUnavailable/markFailed가 각자 짧은 쓰기 트랜잭션으로 저장
 *
 * 1·3단계 모두 `departureRepository`(별도 빈, Spring Data JPA 프록시)를 통해서만 트랜잭션이 걸리므로,
 * 외부 API 호출(2단계)이 진행되는 동안에는 어떤 DB 트랜잭션도 열려 있지 않다.
 */
@Component
class DepartureJobExecutor(
    private val reader: DepartureCalculationReader,
    private val departureRepository: DepartureCalculationRepository,
    private val tmapClient: TmapTransitClient,
    private val originCipher: OriginCipher,
) : JobExecutor {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val maxRetries = 3

    override fun processOne(): Boolean {
        val target = reader.loadNextTarget() ?: return false
        logger.info("departure_job_processing calculationId=${target.calculationId}")

        try {
            executeTransit(target)
            logger.info("departure_job_processOne_success calculationId=${target.calculationId}")
        } catch (e: Exception) {
            logger.error("departure_job_processOne_error calculationId=${target.calculationId} error=${e.message}", e)
            markFailed(target.calculationId)
        }
        return true
    }

    private fun executeTransit(target: DepartureProcessingTarget) {
        val originCiphertext = target.originCiphertext
        if (originCiphertext == null) {
            logger.warn("departure_job_no_origin calculationId=${target.calculationId}")
            markFailed(target.calculationId)
            return
        }

        // 출발지 복호화 및 좌표 추출 (Job Executor 내부에서만 호출. 좌표는 로그에 남기지 않는다)
        val (originLon, originLat) = try {
            val decrypted = originCipher.decrypt(originCiphertext)
            val buffer = ByteBuffer.wrap(decrypted)
            Pair(buffer.double, buffer.double)
        } catch (e: Exception) {
            logger.warn("departure_job_decrypt_error calculationId=${target.calculationId}")
            markFailed(target.calculationId)
            return
        }

        val destLon = target.destLon
        val destLat = target.destLat
        val destScheduledAt = target.destScheduledAt
        if (destLon == null || destLat == null || destScheduledAt == null) {
            logger.warn("departure_job_no_first_meeting calculationId=${target.calculationId}")
            markFailed(target.calculationId)
            return
        }

        // TMAP 호출 (재시도 포함). 이 블록 전체가 트랜잭션 밖에서 실행된다.
        for (attempt in 1..maxRetries) {
            try {
                val summary = tmapClient.searchTransit(
                    startLon = originLon,
                    startLat = originLat,
                    endLon = destLon,
                    endLat = destLat,
                    arrivalAt = destScheduledAt,
                )

                if (summary == null) {
                    logger.info("departure_job_unavailable calculationId=${target.calculationId}")
                    markUnavailable(target.calculationId)
                    return
                }

                // 권장 출발시각 계산: 도착시각 - 이동시간 - 10분
                val recommendedDepartureAt = destScheduledAt
                    .minusSeconds(summary.totalSeconds.toLong())
                    .minus(10, ChronoUnit.MINUTES)

                markReady(target.calculationId, summary, recommendedDepartureAt)
                return
            } catch (e: Exception) {
                logger.warn("departure_job_retry calculationId=${target.calculationId} attempt=$attempt maxRetries=$maxRetries error=${e.message}")
                if (attempt < maxRetries) {
                    Thread.sleep(1000L * attempt) // 1s, 2s 대기
                }
            }
        }

        // 모든 재시도 소진
        logger.error("departure_job_failed_after_retries calculationId=${target.calculationId}")
        markFailed(target.calculationId)
    }

    /** findById→저장을 각각 별도 트랜잭션(레포지토리 빈 호출)으로 짧게 끊어 상태를 확정한다. */
    private fun markReady(calculationId: Long, summary: TmapTransitClient.TransitSummary, recommendedDepartureAt: Instant) {
        val calculation = departureRepository.findById(calculationId).orElse(null) ?: return
        calculation.markReady(
            totalSeconds = summary.totalSeconds,
            transferCount = summary.transferCount,
            fareAmount = summary.fareAmount,
            totalWalkSeconds = summary.totalWalkSeconds,
            recommendedDepartureAt = recommendedDepartureAt,
            calculatedAt = Instant.now(),
        )
        departureRepository.save(calculation)
        logger.info("departure_job_ready calculationId=$calculationId totalSeconds=${summary.totalSeconds}")
    }

    private fun markUnavailable(calculationId: Long) {
        val calculation = departureRepository.findById(calculationId).orElse(null) ?: return
        calculation.markUnavailable(Instant.now())
        departureRepository.save(calculation)
        logger.info("departure_job_unavailable calculationId=$calculationId")
    }

    private fun markFailed(calculationId: Long) {
        val calculation = departureRepository.findById(calculationId).orElse(null) ?: return
        calculation.markFailed(Instant.now())
        departureRepository.save(calculation)
        logger.warn("departure_job_failed calculationId=$calculationId")
    }
}
