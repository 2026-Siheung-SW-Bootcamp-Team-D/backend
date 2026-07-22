package com.siheungbootcamp.teamd.domain.course

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 코스 구간의 직선거리 기반 도보 시간 추정치를 계산한다.
 *
 * 실제 도보 경로가 아니라 Haversine 공식으로 구한 두 지점 간 직선거리만 사용하며,
 * 초안·확정 코스 조회·공개 일정 세 곳에서 같은 계산식을 재사용해 값이 어긋나지 않게 한다.
 */
object LegEstimator {
    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val WALK_SPEED_METERS_PER_MINUTE = 70.0

    data class StopPoint(val orderIndex: Int, val lon: Double, val lat: Double)

    fun estimate(points: List<StopPoint>): List<CourseLegResponse> {
        val ordered = points.sortedBy { it.orderIndex }
        return ordered.zipWithNext { from, to ->
            val distance = haversineMeters(from.lon, from.lat, to.lon, to.lat)
            CourseLegResponse(
                fromOrder = from.orderIndex,
                toOrder = to.orderIndex,
                straightDistanceMeters = distance.roundToInt(),
                estimatedWalkMinutes = (distance / WALK_SPEED_METERS_PER_MINUTE).roundToInt(),
                estimated = true,
            )
        }
    }

    private fun haversineMeters(lon1: Double, lat1: Double, lon2: Double, lat2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }
}
