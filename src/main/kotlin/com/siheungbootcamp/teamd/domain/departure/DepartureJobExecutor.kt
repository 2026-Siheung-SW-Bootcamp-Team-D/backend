package com.siheungbootcamp.teamd.domain.departure

import com.siheungbootcamp.teamd.domain.course.CourseRepository
import com.siheungbootcamp.teamd.domain.course.CourseStopRepository
import com.siheungbootcamp.teamd.domain.course.CourseStopRole
import com.siheungbootcamp.teamd.global.crypto.OriginCipher
import com.siheungbootcamp.teamd.global.job.JobExecutor
import com.siheungbootcamp.teamd.infra.external.tmap.TmapTransitClient
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.nio.ByteBuffer
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 출발 안내 계산을 실행하는 Job Executor.
 *
 * 흐름:
 * 1. CALCULATING 상태의 첫 행 조회
 * 2. 트랜잭션 끝내기
 * 3. TMAP Transit API 호출 (좌표 복호화, 외부 API)
 * 4. 결과 저장 (또는 재시도 한도 초과 시 FAILED)
 *
 * 외부 API 호출 중 DB 트랜잭션을 열어두지 않는다.
 * 재시도는 이 메서드 내부에서 최대 3회 시도한다(DB에 기록하지 않음).
 */
@Component
class DepartureJobExecutor(
    private val departureRepository: DepartureCalculationRepository,
    private val courseRepository: CourseRepository,
    private val courseStopRepository: CourseStopRepository,
    private val tmapClient: TmapTransitClient,
    private val originCipher: OriginCipher,
    private val entityManager: EntityManager,
) : JobExecutor {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val maxRetries = 3

    @Transactional(readOnly = false)
    override fun processOne(): Boolean {
        // 처리할 CALCULATING 행 찾기
        val targets = departureRepository.findAllCalculating()
        if (targets.isEmpty()) return false

        val target = targets[0]
        logger.info("departure_job_processing participantId=${target.participant.id} courseId=${target.course.id}")

        // 같은 트랜잭션 내에서 처리
        try {
            executeTransitInternal(target)
            logger.info("departure_job_processOne_success")
        } catch (e: Exception) {
            logger.error("departure_job_processOne_error participantId=${target.participant.id} error=${e.message}", e)
            markFailed(target)
        }
        return true
    }

    private fun executeTransitInternal(calculation: DepartureCalculation) {
        val participant = calculation.participant
        val course = calculation.course
        val participantIdInternal = requireNotNull(participant.id)
        val courseIdInternal = requireNotNull(course.id)

        logger.debug("DEBUG: executeTransitInternal started for participantId=$participantIdInternal courseId=$courseIdInternal")

        // 참여자 출발지 복호화 (Job Executor 내부에서만 호출)
        if (participant.originCiphertext == null) {
            logger.warn("departure_job_no_origin participantId=$participantIdInternal")
            markFailed(calculation)
            return
        }

        // 출발지 복호화 및 좌표 추출
        val (originLon, originLat) = try {
            val decrypted = originCipher.decrypt(participant.originCiphertext!!)
            val buffer = ByteBuffer.wrap(decrypted)
            Pair(buffer.double, buffer.double)
        } catch (e: Exception) {
            logger.warn("departure_job_decrypt_error participantId=$participantIdInternal")
            markFailed(calculation)
            return
        }

        // 코스의 첫 만남 장소 찾기
        val courseStops = courseStopRepository.findByCourseIdOrderByOrderIndex(courseIdInternal)
        val firstMeeting = courseStops.firstOrNull { it.role == CourseStopRole.FIRST_MEETING.name }
            ?: courseStops.firstOrNull()

        if (firstMeeting == null) {
            logger.warn("departure_job_no_first_meeting courseId=$courseIdInternal")
            markFailed(calculation)
            return
        }

        val place = firstMeeting.place
        val destLon = place.lon
        val destLat = place.lat
        val destScheduledAt = firstMeeting.scheduledAt

        // TMAP 호출 (재시도 포함)
        var lastException: Exception? = null
        for (attempt in 1..maxRetries) {
            try {
                logger.debug("DEBUG: calling tmapClient.searchTransit attempt=$attempt")
                val summary = tmapClient.searchTransit(
                    startLon = originLon,
                    startLat = originLat,
                    endLon = destLon,
                    endLat = destLat,
                    arrivalAt = destScheduledAt,
                )

                logger.debug("DEBUG: tmapClient returned summary=$summary")
                if (summary == null) {
                    // 경로 없음 → UNAVAILABLE로 저장
                    logger.info("departure_job_unavailable courseId=$courseIdInternal")
                    markUnavailable(calculation)
                    logger.debug("DEBUG: marked UNAVAILABLE and returning")
                    return
                }

                // 권장 출발시각 계산: 도착시각 - 이동시간 - 10분
                val recommendedDepartureAt = destScheduledAt
                    .minusSeconds(summary.totalSeconds.toLong())
                    .minus(10, ChronoUnit.MINUTES)

                logger.debug("DEBUG: calling markReady")
                markReady(calculation, summary, recommendedDepartureAt)
                logger.debug("DEBUG: marked READY and returning")
                return
            } catch (e: Exception) {
                lastException = e
                logger.warn("departure_job_retry attempt=$attempt maxRetries=$maxRetries error=${e.message}")
                if (attempt < maxRetries) {
                    Thread.sleep(1000L * attempt) // 1s, 2s 대기
                }
            }
        }

        // 모든 재시도 소진
        logger.error("departure_job_failed_after_retries courseId=${course.id}")
        logger.debug("DEBUG: calling markFailed")
        markFailed(calculation)
        logger.debug("DEBUG: marked FAILED")
    }

    private fun markReady(
        calculation: DepartureCalculation,
        summary: TmapTransitClient.TransitSummary,
        recommendedDepartureAt: Instant,
    ) {
        calculation.markReady(
            totalSeconds = summary.totalSeconds,
            transferCount = summary.transferCount,
            fareAmount = summary.fareAmount,
            totalWalkSeconds = summary.totalWalkSeconds,
            recommendedDepartureAt = recommendedDepartureAt,
            calculatedAt = Instant.now(),
        )
        departureRepository.save(calculation)
        entityManager.flush()
        logger.info("departure_job_ready courseId=${calculation.course.id} totalSeconds=${summary.totalSeconds}")
    }

    private fun markUnavailable(calculation: DepartureCalculation) {
        calculation.markUnavailable(Instant.now())
        departureRepository.save(calculation)
        entityManager.flush()
        logger.info("departure_job_unavailable courseId=${calculation.course.id}")
    }

    private fun markFailed(calculation: DepartureCalculation) {
        calculation.markFailed(Instant.now())
        departureRepository.save(calculation)
        entityManager.flush()
        logger.warn("departure_job_failed courseId=${calculation.course.id}")
    }
}
