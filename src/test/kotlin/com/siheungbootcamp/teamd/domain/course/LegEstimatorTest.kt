package com.siheungbootcamp.teamd.domain.course

import org.junit.jupiter.api.Test
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.assertEquals

/** V4-5: 알려진 두 좌표의 Haversine 직선거리와 round(d/70) 도보 추정이 명세식과 일치하는지 검증한다. */
class LegEstimatorTest {

    @Test
    fun `V4-5 두 지점의 직선거리와 도보 추정 시간이 Haversine 공식과 일치한다`() {
        // 서울시청(37.5665, 126.9780) -> 광화문(37.5759, 126.9769) 근사 좌표
        val from = LegEstimator.StopPoint(orderIndex = 1, lon = 126.9780, lat = 37.5665)
        val to = LegEstimator.StopPoint(orderIndex = 2, lon = 126.9769, lat = 37.5759)

        val expectedDistance = referenceHaversineMeters(from.lon, from.lat, to.lon, to.lat)
        val expectedWalkMinutes = (expectedDistance / 70.0).roundToInt()

        val legs = LegEstimator.estimate(listOf(from, to))

        assertEquals(1, legs.size)
        val leg = legs.first()
        assertEquals(1, leg.fromOrder)
        assertEquals(2, leg.toOrder)
        assertEquals(expectedDistance.roundToInt(), leg.straightDistanceMeters)
        assertEquals(expectedWalkMinutes, leg.estimatedWalkMinutes)
        assertEquals(true, leg.estimated)
    }

    @Test
    fun `stop이 1개 이하면 legs가 비어있다`() {
        assertEquals(emptyList(), LegEstimator.estimate(emptyList()))
        assertEquals(emptyList(), LegEstimator.estimate(listOf(LegEstimator.StopPoint(1, 127.0, 37.5))))
    }

    @Test
    fun `3개 stop이면 순서대로 2개의 leg가 나온다`() {
        val points = listOf(
            LegEstimator.StopPoint(1, 126.97, 37.55),
            LegEstimator.StopPoint(2, 126.98, 37.56),
            LegEstimator.StopPoint(3, 126.99, 37.57),
        )
        val legs = LegEstimator.estimate(points)
        assertEquals(2, legs.size)
        assertEquals(1 to 2, legs[0].fromOrder to legs[0].toOrder)
        assertEquals(2 to 3, legs[1].fromOrder to legs[1].toOrder)
    }

    /** 테스트가 프로덕션 코드와 같은 상수를 재사용해 자기증명이 되지 않도록 독립적으로 계산한다. */
    private fun referenceHaversineMeters(lon1: Double, lat1: Double, lon2: Double, lat2: Double): Double {
        val earthRadiusMeters = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadiusMeters * c
    }
}
