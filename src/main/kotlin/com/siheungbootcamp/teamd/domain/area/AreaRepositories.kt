package com.siheungbootcamp.teamd.domain.area

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * 지역 탐색 작업(AreaSearchJob) 저장소.
 *
 * select-for-update-skip-locked 패턴으로 동시성을 관리한다.
 */
@Repository
interface AreaSearchJobRepository : JpaRepository<AreaSearchJob, Long> {
    /**
     * 공개 ID로 작업을 조회한다.
     */
    fun findByPublicId(publicId: String): AreaSearchJob?

    /**
     * 보드에서 활성 작업(QUEUED, RUNNING, RETRY_WAIT)을 조회한다.
     */
    @Query("""
        SELECT j FROM AreaSearchJob j
        WHERE j.boardId = :boardId
        AND j.status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT')
        ORDER BY j.createdAt DESC
        LIMIT 1
    """)
    fun findActiveByBoardId(boardId: Long): AreaSearchJob?

    /**
     * 다음 처리할 작업을 QUEUED 또는 RETRY_WAIT 상태에서 찾는다.
     * SELECT FOR UPDATE SKIP LOCKED로 동시 접근 방지.
     */
    @Query("""
        SELECT j FROM AreaSearchJob j
        WHERE (j.status = 'QUEUED' OR (j.status = 'RETRY_WAIT' AND j.nextRetryAt <= CURRENT_TIMESTAMP))
        ORDER BY j.createdAt ASC
        LIMIT 1
    """)
    fun findNextPending(): AreaSearchJob?

    /**
     * 다음 처리할 작업을 원자적으로 조회하고 잠금한다.
     * 두 executor 인스턴스가 같은 작업을 동시에 집을 수 없도록 보장한다.
     * FOR UPDATE SKIP LOCKED: 다른 트랜잭션이 잠금 중인 행을 건너뛰고, 잠금 가능한 행만 반환.
     *
     * 네이티브 SQL을 사용하여 PostgreSQL의 SKIP LOCKED 동작을 정확히 구현.
     */
    @Query(
        value = """
            SELECT * FROM area_search_job
            WHERE (status = 'QUEUED' OR (status = 'RETRY_WAIT' AND next_retry_at <= now()))
            ORDER BY created_at ASC
            LIMIT 1
            FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true,
    )
    fun findNextPendingForUpdate(): AreaSearchJob?

    /**
     * RUNNING 상태에서 오래된 행(stale)을 조회한다.
     */
    fun findAllByStatusAndStartedAtBefore(status: String, before: Instant): List<AreaSearchJob>
}

/**
 * 지역 탐색 결과 후보(AreaCandidate) 저장소.
 */
@Repository
interface AreaCandidateRepository : JpaRepository<AreaCandidate, Long> {
    /**
     * 작업 ID로 후보들을 조회한다.
     */
    fun findByJobIdOrderByRankAsc(jobId: Long): List<AreaCandidate>

    /**
     * 공개 ID로 후보를 조회한다.
     */
    fun findByPublicId(publicId: String): AreaCandidate?
}
