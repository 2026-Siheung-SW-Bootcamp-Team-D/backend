package com.siheungbootcamp.teamd.domain.area

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LinearRing
import kotlin.test.assertTrue

class GeometryServiceTest {
    private lateinit var service: GeometryService
    private lateinit var geometryFactory: GeometryFactory

    @BeforeEach
    fun setup() {
        service = GeometryService()
        geometryFactory = GeometryFactory()
    }

    @Test
    fun `두 도달권의 교집합에서 면적이 큰 조각 세 개까지만 반환한다`() {
        // 왼쪽 폴리곤: (0,0), (3,0), (3,2), (0,2)
        val leftCoordinates = arrayOf(
            Coordinate(0.0, 0.0),
            Coordinate(3.0, 0.0),
            Coordinate(3.0, 2.0),
            Coordinate(0.0, 2.0),
            Coordinate(0.0, 0.0)
        )
        val leftLinearRing = geometryFactory.createLinearRing(leftCoordinates)
        val leftPolygon = geometryFactory.createPolygon(leftLinearRing)

        // 오른쪽 폴리곤: (2,0), (5,0), (5,2), (2,2)
        val rightCoordinates = arrayOf(
            Coordinate(2.0, 0.0),
            Coordinate(5.0, 0.0),
            Coordinate(5.0, 2.0),
            Coordinate(2.0, 2.0),
            Coordinate(2.0, 0.0)
        )
        val rightLinearRing = geometryFactory.createLinearRing(rightCoordinates)
        val rightPolygon = geometryFactory.createPolygon(rightLinearRing)

        val result = service.intersectLargest(listOf(leftPolygon, rightPolygon), limit = 3)

        assertTrue(result.size <= 3)
        assertTrue(result.zipWithNext().all { (a, b) -> a.area >= b.area })
        assertTrue(result.all { it.isValid && !it.isEmpty })
    }
}
