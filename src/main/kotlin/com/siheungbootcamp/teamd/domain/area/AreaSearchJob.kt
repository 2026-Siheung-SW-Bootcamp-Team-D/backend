package com.siheungbootcamp.teamd.domain.area

import com.siheungbootcamp.teamd.global.persistence.JsonValue
import jakarta.persistence.*
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Instant

/**
 * 지역 탐색 작업(Area Search Job)의 상태와 진행 상황을 관리한다.
 *
 * 3단계 파이프라인:
 * 1. ISOCHRONE: ODsay에서 각 참여자별 도달권 조회
 * 2. INTERSECTION: JTS로 교집합 계산 후 상위 3개 조각
 * 3. AREA_ANCHOR_COLLECTION: Kakao Local에서 기준점 검색
 *
 * snapshot: 참여자 ID만 저장 (좌표는 절대 저장하지 않음)
 * progress: 각 단계별 진행 상황
 * result: 최종 결과 (candidates 배열)
 */
@Entity
@Table(name = "area_search_job")
class AreaSearchJob(
    @Column(name = "public_id", nullable = false, unique = true)
    val publicId: String,
    @Column(name = "board_id", nullable = false)
    val boardId: Long,
    @Column(name = "duration_min", nullable = false)
    val durationMin: Int,
    @JsonValue
    @Column(name = "snapshot", nullable = false, columnDefinition = "jsonb")
    val snapshotJson: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Column(name = "status", nullable = false)
    var status: String = "QUEUED" // QUEUED, RUNNING, SUCCEEDED, FAILED

    @JsonValue
    @Column(name = "progress", columnDefinition = "jsonb")
    var progressJson: String? = null

    @JsonValue
    @Column(name = "result", columnDefinition = "jsonb")
    var resultJson: String? = null

    @Column(name = "error_code")
    var errorCode: String? = null

    @Column(name = "retry_count", nullable = false)
    var retryCount: Int = 0

    @Column(name = "next_retry_at")
    var nextRetryAt: Instant? = null

    @Column(name = "started_at")
    var startedAt: Instant? = null

    @Column(name = "finished_at")
    var finishedAt: Instant? = null

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    fun markRunning() {
        this.status = "RUNNING"
        this.startedAt = Instant.now()
        this.updatedAt = Instant.now()
    }

    fun markSucceeded(result: JsonNode) {
        this.status = "SUCCEEDED"
        this.resultJson = ObjectMapper().writeValueAsString(result)
        this.finishedAt = Instant.now()
        this.updatedAt = Instant.now()
    }

    fun markFailed(errorCode: String) {
        this.status = "FAILED"
        this.errorCode = errorCode
        this.finishedAt = Instant.now()
        this.updatedAt = Instant.now()
    }

    fun updateProgress(phase: String, details: JsonNode) {
        val mapper = ObjectMapper()
        val currentProgress = if (this.progressJson == null) {
            mapper.createObjectNode()
        } else {
            mapper.readTree(this.progressJson) as ObjectNode
        }
        currentProgress.set(phase, details)
        this.progressJson = mapper.writeValueAsString(currentProgress)
        this.updatedAt = Instant.now()
    }
}
