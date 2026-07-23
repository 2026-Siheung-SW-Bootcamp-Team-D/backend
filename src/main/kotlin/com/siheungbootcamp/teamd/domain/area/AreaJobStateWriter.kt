package com.siheungbootcamp.teamd.domain.area

import com.siheungbootcamp.teamd.global.error.BusinessException
import com.siheungbootcamp.teamd.global.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Instant

/**
 * 지역 탐색 작업(AreaSearchJob)의 상태 변경을 담당하는 전담 빈.
 *
 * AreaJobExecutor가 이 메서드들을 자기 자신이 아니라 별도 빈으로 호출해야 `@Transactional`이
 * Spring 프록시를 거친다(같은 객체 내부 호출은 프록시를 우회해 트랜잭션이 걸리지 않는다).
 *
 * 각 상태 변경은 짧은 트랜잭션 안에서 일어나며, 외부 API 호출(ISOCHRONE, INTERSECTION, AREA_ANCHOR_COLLECTION)은
 * 트랜잭션 밖에서 진행된다.
 */
@Component
class AreaJobStateWriter(
    private val jobRepository: AreaSearchJobRepository,
    private val candidateRepository: AreaSuggestionRepository,
    private val mapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * 다음 처리할 작업을 원자적으로 조회하고 RUNNING 상태로 표시한다.
     * 두 executor 인스턴스가 같은 작업을 동시에 집을 수 없도록 보장한다.
     *
     * @return 조회 및 잠금한 작업, 또는 처리할 작업이 없으면 null
     */
    @Transactional
    fun claimNextJob(): AreaSearchJob? {
        val job = jobRepository.findNextPendingForUpdate() ?: return null
        job.markRunning()
        return jobRepository.save(job)
    }

    /**
     * 작업의 진행 상황을 저장한다.
     */
    @Transactional
    fun updateProgress(job: AreaSearchJob, phase: String, details: String) {
        val progress = (mapper.createObjectNode() as ObjectNode).apply {
            put(phase, details)
            put("updatedAt", Instant.now().toString())
        }
        job.updateProgress(phase, progress)
        jobRepository.save(job)
    }

    /**
     * 작업을 성공 상태로 표시하고 후보들을 저장한다.
     */
    @Transactional
    fun markSucceeded(job: AreaSearchJob, candidates: List<AreaSuggestion>) {
        // 후보 저장
        candidates.forEach { candidateRepository.save(it) }

        // 결과 JSON 구성
        val result = (mapper.createObjectNode() as ObjectNode).apply {
            val candidatesArray = (mapper.createArrayNode())
            candidates.forEach { candidate ->
                candidatesArray.add(
                    (mapper.createObjectNode() as ObjectNode).apply {
                        put("candidateId", candidate.publicId)
                        put("name", candidate.name)
                        put("lon", candidate.lon)
                        put("lat", candidate.lat)
                        set("metrics", mapper.readTree(candidate.metricsJson))
                        set("reasons", mapper.readTree(candidate.reasonsJson))
                        put("rank", candidate.rank)
                    }
                )
            }
            set("candidates", candidatesArray)
        }

        job.markSucceeded(result)
        jobRepository.save(job)
    }

    /**
     * 작업을 실패 상태로 표시한다.
     * 실제 오류 코드를 보존한다.
     */
    @Transactional
    fun markFailed(job: AreaSearchJob, errorCode: String) {
        job.markFailed(errorCode)
        jobRepository.save(job)
    }

    /**
     * 오래된 RUNNING 작업들을 QUEUED로 되돌린다 (재시작 복구).
     * 이전 실행 중 crashed인 작업을 감지하고 재처리하기 위함.
     *
     * @param staleBefore 이 시각 이전에 시작한 RUNNING 작업이 대상
     */
    @Transactional
    fun recoverStaleRunningJobs(staleBefore: Instant) {
        val staleJobs = jobRepository.findAllByStatusAndStartedAtBefore("RUNNING", staleBefore)
        staleJobs.forEach { job ->
            logger.info("area_job_recovery_resetting jobId=${job.publicId} startedAt=${job.startedAt}")
            job.status = "QUEUED"
            job.startedAt = null
            job.updatedAt = Instant.now()
            jobRepository.save(job)
        }
    }

    /**
     * 작업에 연결된 부분적인 후보들을 삭제한다 (중복 방지).
     * 작업이 crash 후 재처리될 때, 이전 실행에서 저장된 후보들이 남아있을 수 있으므로
     * 재처리 전에 삭제해야 한다.
     */
    @Transactional
    fun deleteCandidatesForJob(jobId: Long) {
        val candidates = candidateRepository.findByJobIdOrderByRankAsc(jobId)
        candidates.forEach { candidateRepository.delete(it) }
        logger.info("area_job_recovery_deleted_candidates jobId=$jobId count=${candidates.size}")
    }
}
