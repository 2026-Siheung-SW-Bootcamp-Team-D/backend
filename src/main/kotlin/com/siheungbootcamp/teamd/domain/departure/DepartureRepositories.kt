package com.siheungbootcamp.teamd.domain.departure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface DepartureCalculationRepository : JpaRepository<DepartureCalculation, Long> {
    /** 참여자의 현재 확정 코스에 대한 계산 결과를 조회한다. NOT_REQUESTED(행 없음)는 null로 반환된다. */
    @Query("""
        select dc from DepartureCalculation dc
        where dc.participant.id = :participantId and dc.course.id = :courseId
    """)
    fun findByParticipantIdAndCourseId(
        @Param("participantId") participantId: Long,
        @Param("courseId") courseId: Long,
    ): DepartureCalculation?

    /** Job Executor 폴링: CALCULATING 상태의 모든 행을 조회한다. */
    @Query("select dc from DepartureCalculation dc where dc.status = 'CALCULATING' order by dc.createdAt")
    fun findAllCalculating(): List<DepartureCalculation>

    /** 참여자의 모든 출발 계산 결과를 STALE로 표시한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update DepartureCalculation dc
        set dc.status = 'STALE'
        where dc.participant.id = :participantId
    """)
    fun markStaleByParticipantId(@Param("participantId") participantId: Long)

    /** 보드의 모든 참여자에 대한 코스의 출발 계산 결과를 STALE로 표시한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update DepartureCalculation dc
        set dc.status = 'STALE'
        where dc.course.id in (
            select c.id from Course c where c.board.id = :boardId
        )
    """)
    fun markStaleByCourseBoard(@Param("boardId") boardId: Long)
}
