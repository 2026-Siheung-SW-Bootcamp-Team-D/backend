package com.siheungbootcamp.teamd.domain.course

import com.siheungbootcamp.teamd.domain.place.PlaceUsage
import com.siheungbootcamp.teamd.domain.place.PlaceUsageChecker
import org.springframework.stereotype.Component

/**
 * 확정 코스가 참조하는 장소의 삭제를 막는다(T4-3).
 *
 * `course_stop`은 스냅샷 없이 Place FK만 참조하므로, 과거 확정 버전이라도 이 장소를
 * 여전히 표시한다. 따라서 어느 버전이든 참조 중이면 삭제를 거부한다.
 */
@Component
class CoursePlaceUsageChecker(
    private val stops: CourseStopRepository,
) : PlaceUsageChecker {
    override fun findUsage(placeId: Long): PlaceUsage? {
        val usage = stops.findByPlaceId(placeId).firstOrNull() ?: return null
        return PlaceUsage(
            details = mapOf("courseId" to usage.course.publicId, "orderIndex" to usage.orderIndex),
        )
    }
}
