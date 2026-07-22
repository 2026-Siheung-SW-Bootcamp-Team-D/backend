package com.siheungbootcamp.teamd.domain.departure

import com.siheungbootcamp.teamd.domain.board.Participant
import com.siheungbootcamp.teamd.domain.course.Course
import com.siheungbootcamp.teamd.global.persistence.BaseEntity
import jakarta.persistence.*
import java.time.Instant

/**
 * 참여자×코스 버전당 1건의 출발 안내 계산 결과.
 *
 * 상태:
 * - NOT_REQUESTED: 행 없음 (별도 값으로 저장하지 않음)
 * - CALCULATING: 계산 대기 중 또는 실행 중 (Job Executor가 처리함)
 * - READY: 결과 완성 (사용 가능)
 * - STALE: 출발지/코스 변경으로 재계산 필요
 * - UNAVAILABLE: 대중교통 경로 없음
 * - FAILED: 외부 API 오류 또는 최대 재시도 초과
 *
 * 고유 제약: (participant_id, course_id)
 */
@Entity
@Table(name = "departure_calculation")
class DepartureCalculation(
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "participant_id") val participant: Participant,
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "course_id") val course: Course,
    @Column(nullable = false) var status: String, // CALCULATING / READY / STALE / UNAVAILABLE / FAILED
    @Column(name = "total_seconds") var totalSeconds: Int? = null,
    @Column(name = "transfer_count") var transferCount: Int? = null,
    @Column(name = "fare_amount") var fareAmount: Int? = null, // KRW
    @Column(name = "total_walk_seconds") var totalWalkSeconds: Int? = null,
    @Column(name = "recommended_departure_at") var recommendedDepartureAt: Instant? = null,
    @Column(name = "calculated_at") var calculatedAt: Instant? = null,
) : BaseEntity() {

    enum class Status {
        CALCULATING, READY, STALE, UNAVAILABLE, FAILED
    }

    fun markReady(
        totalSeconds: Int,
        transferCount: Int,
        fareAmount: Int,
        totalWalkSeconds: Int,
        recommendedDepartureAt: Instant,
        calculatedAt: Instant,
    ) {
        this.status = Status.READY.name
        this.totalSeconds = totalSeconds
        this.transferCount = transferCount
        this.fareAmount = fareAmount
        this.totalWalkSeconds = totalWalkSeconds
        this.recommendedDepartureAt = recommendedDepartureAt
        this.calculatedAt = calculatedAt
    }

    fun markUnavailable(calculatedAt: Instant) {
        this.status = Status.UNAVAILABLE.name
        this.calculatedAt = calculatedAt
    }

    fun markFailed(calculatedAt: Instant) {
        this.status = Status.FAILED.name
        this.calculatedAt = calculatedAt
    }

    fun markStale() {
        this.status = Status.STALE.name
    }
}
