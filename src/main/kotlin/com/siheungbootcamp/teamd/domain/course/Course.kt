package com.siheungbootcamp.teamd.domain.course

import com.siheungbootcamp.teamd.domain.board.Board
import com.siheungbootcamp.teamd.domain.place.Place
import com.siheungbootcamp.teamd.global.id.IdPrefix
import com.siheungbootcamp.teamd.global.id.PublicId
import com.siheungbootcamp.teamd.global.persistence.BaseEntity
import com.siheungbootcamp.teamd.global.persistence.JsonValue
import jakarta.persistence.*

/**
 * 보드당 1행인 코스 초안. `stops`는 스냅샷 없이 참여자가 정한 순서·역할·예정시각만 담고,
 * 장소 이름·좌표는 항상 현재 [Place] 값을 다시 읽어 조립한다.
 *
 * `version`은 PUT마다 1씩 증가하며 `ETag: "draft-{version}"`으로 그대로 노출되어
 * 낙관적 잠금(If-Match)의 비교 기준이 된다.
 */
@Entity
@Table(name = "course_draft")
class CourseDraft(
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "board_id", unique = true) val board: Board,
    @Column(nullable = false) var version: Int = 0,
    // Hibernate 내장 JSON 포맷 매퍼는 Kotlin data class용 생성자를 인식하지 못해(Jackson 모듈 미등록)
    // 직접 만든 List<DraftStopEntry>를 여기서 (역)직렬화하면 InvalidDefinitionException이 난다.
    // 그래서 이 컬럼은 원문 JSON 문자열로만 다루고, 앱 전역의 Kotlin 인식 ObjectMapper로
    // CourseService에서 직접 (역)직렬화한다.
    @JsonValue @Column(name = "stops", nullable = false, columnDefinition = "jsonb") var stopsJson: String = "[]",
) : BaseEntity() {
    fun replace(newStopsJson: String) {
        stopsJson = newStopsJson
        version += 1
    }
}

/** JSONB에 저장되는 초안 항목 하나. `scheduledAt`은 ISO-8601 문자열로 보존한다. */
data class DraftStopEntry(
    val placeId: String,
    val orderIndex: Int,
    val role: String,
    val scheduledAt: String,
)

/** 확정된 코스 버전 하나. 보드는 여러 버전을 가질 수 있고 전부 보존된다(ERD 2.9). */
@Entity
@Table(name = "course")
class Course(
    @Column(name = "public_id", nullable = false, unique = true) val publicId: String = PublicId.generate(IdPrefix.COURSE),
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "board_id") val board: Board,
    @Column(nullable = false) val version: Int,
    @Column(name = "confirmed_at", nullable = false) val confirmedAt: java.time.Instant,
) : BaseEntity()

/** 확정 코스에 속한 방문 장소 한 곳. 스냅샷 없이 [Place] FK만 참조하므로 장소가 수정되면
 * 이미 확정한 코스의 조회 결과도 함께 바뀐다(의도된 동작). */
@Entity
@Table(name = "course_stop")
class CourseStop(
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "course_id") val course: Course,
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "place_id") val place: Place,
    @Column(name = "order_index", nullable = false) val orderIndex: Int,
    @Column(nullable = false) val role: String,
    @Column(name = "scheduled_at", nullable = false) val scheduledAt: java.time.Instant,
) : BaseEntity()

enum class CourseStopRole { FIRST_MEETING, MEAL, CAFE, PLAY, ETC }
