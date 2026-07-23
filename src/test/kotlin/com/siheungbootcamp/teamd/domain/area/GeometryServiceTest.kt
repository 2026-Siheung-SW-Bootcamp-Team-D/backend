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

    @Test
    fun `입력 리스트가 비어있으면 빈 리스트를 반환한다`() {
        val result = service.intersectLargest(emptyList(), limit = 3)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `입력이 폴리곤 하나이면 그 폴리곤을 반환한다`() {
        val coordinates = arrayOf(
            Coordinate(0.0, 0.0),
            Coordinate(2.0, 0.0),
            Coordinate(2.0, 2.0),
            Coordinate(0.0, 2.0),
            Coordinate(0.0, 0.0)
        )
        val ring = geometryFactory.createLinearRing(coordinates)
        val polygon = geometryFactory.createPolygon(ring)

        val result = service.intersectLargest(listOf(polygon), limit = 3)

        assertTrue(result.size == 1)
        assertTrue(result[0].isValid && !result[0].isEmpty)
    }

    @Test
    fun `겹치지 않는 폴리곤들은 빈 교집합을 반환한다`() {
        // 왼쪽 폴리곤: (0,0), (1,0), (1,1), (0,1)
        val leftCoordinates = arrayOf(
            Coordinate(0.0, 0.0),
            Coordinate(1.0, 0.0),
            Coordinate(1.0, 1.0),
            Coordinate(0.0, 1.0),
            Coordinate(0.0, 0.0)
        )
        val leftRing = geometryFactory.createLinearRing(leftCoordinates)
        val leftPolygon = geometryFactory.createPolygon(leftRing)

        // 오른쪽 폴리곤: (2,0), (3,0), (3,1), (2,1)
        val rightCoordinates = arrayOf(
            Coordinate(2.0, 0.0),
            Coordinate(3.0, 0.0),
            Coordinate(3.0, 1.0),
            Coordinate(2.0, 1.0),
            Coordinate(2.0, 0.0)
        )
        val rightRing = geometryFactory.createLinearRing(rightCoordinates)
        val rightPolygon = geometryFactory.createPolygon(rightRing)

        val result = service.intersectLargest(listOf(leftPolygon, rightPolygon), limit = 3)

        assertTrue(result.isEmpty(), "겹치지 않으면 교집합은 비어있어야 함")
    }

    @Test
    fun `limit이 0이면 교집합 결과에서 0개 폴리곤을 반환한다`() {
        // 두 개 폴리곤의 교집합으로 결과를 만들어야 take(limit)가 적용됨
        val leftCoordinates = arrayOf(
            Coordinate(0.0, 0.0),
            Coordinate(3.0, 0.0),
            Coordinate(3.0, 2.0),
            Coordinate(0.0, 2.0),
            Coordinate(0.0, 0.0)
        )
        val leftRing = geometryFactory.createLinearRing(leftCoordinates)
        val leftPolygon = geometryFactory.createPolygon(leftRing)

        val rightCoordinates = arrayOf(
            Coordinate(2.0, 0.0),
            Coordinate(5.0, 0.0),
            Coordinate(5.0, 2.0),
            Coordinate(2.0, 2.0),
            Coordinate(2.0, 0.0)
        )
        val rightRing = geometryFactory.createLinearRing(rightCoordinates)
        val rightPolygon = geometryFactory.createPolygon(rightRing)

        val result = service.intersectLargest(listOf(leftPolygon, rightPolygon), limit = 0)

        assertTrue(result.isEmpty(), "limit이 0이면 빈 리스트를 반환해야 함")
    }

    @Test
    fun `limit이 1이면 면적이 가장 큰 폴리곤 하나만 반환한다`() {
        // 왼쪽 폴리곤: (0,0), (3,0), (3,2), (0,2)
        val leftCoordinates = arrayOf(
            Coordinate(0.0, 0.0),
            Coordinate(3.0, 0.0),
            Coordinate(3.0, 2.0),
            Coordinate(0.0, 2.0),
            Coordinate(0.0, 0.0)
        )
        val leftRing = geometryFactory.createLinearRing(leftCoordinates)
        val leftPolygon = geometryFactory.createPolygon(leftRing)

        // 오른쪽 폴리곤: (2,0), (5,0), (5,2), (2,2)
        val rightCoordinates = arrayOf(
            Coordinate(2.0, 0.0),
            Coordinate(5.0, 0.0),
            Coordinate(5.0, 2.0),
            Coordinate(2.0, 2.0),
            Coordinate(2.0, 0.0)
        )
        val rightRing = geometryFactory.createLinearRing(rightCoordinates)
        val rightPolygon = geometryFactory.createPolygon(rightRing)

        val result = service.intersectLargest(listOf(leftPolygon, rightPolygon), limit = 1)

        assertTrue(result.size == 1, "limit이 1이면 폴리곤 하나만 반환해야 함")
        assertTrue(result[0].isValid && !result[0].isEmpty)
    }

    @Test
    fun `limit이 결과 개수보다 크면 모든 폴리곤을 반환한다`() {
        // 왼쪽 폴리곤: (0,0), (3,0), (3,2), (0,2)
        val leftCoordinates = arrayOf(
            Coordinate(0.0, 0.0),
            Coordinate(3.0, 0.0),
            Coordinate(3.0, 2.0),
            Coordinate(0.0, 2.0),
            Coordinate(0.0, 0.0)
        )
        val leftRing = geometryFactory.createLinearRing(leftCoordinates)
        val leftPolygon = geometryFactory.createPolygon(leftRing)

        // 오른쪽 폴리곤: (2,0), (5,0), (5,2), (2,2)
        val rightCoordinates = arrayOf(
            Coordinate(2.0, 0.0),
            Coordinate(5.0, 0.0),
            Coordinate(5.0, 2.0),
            Coordinate(2.0, 2.0),
            Coordinate(2.0, 0.0)
        )
        val rightRing = geometryFactory.createLinearRing(rightCoordinates)
        val rightPolygon = geometryFactory.createPolygon(rightRing)

        val result = service.intersectLargest(listOf(leftPolygon, rightPolygon), limit = 100)

        assertTrue(result.size <= 100, "limit보다 큰 개수가 반환되면 안 됨")
        assertTrue(result.all { it.isValid && !it.isEmpty })
    }
}
