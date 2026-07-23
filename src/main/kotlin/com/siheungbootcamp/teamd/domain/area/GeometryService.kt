package com.siheungbootcamp.teamd.domain.area

import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import org.springframework.stereotype.Service

/**
 * JTS를 사용하여 교집합, 포함 관계 등의 기하학 연산을 수행한다.
 *
 * - intersectLargest: 여러 폴리곤의 교집합 결과에서 면적이 큰 조각부터 최대 limit개까지 반환
 * - contains: 주어진 좌표가 폴리곤에 포함되는지 판정
 *
 * 입력 폴리곤은 자동으로 정규화되며(buffer(0)),
 * 교집합 결과는 면적 내림차순으로 정렬되어 반환된다.
 */
@Service
class GeometryService {
    private val geometryFactory = GeometryFactory()

    /**
     * 여러 개의 폴리곤을 순차적으로 교집합하고, 결과에서 면적이 큰 조각 최대 limit개를 반환한다.
     *
     * 입력 폴리곤들은 먼저 정규화(buffer(0))한 후 reduce로 순차적 교집합을 계산한다.
     * 교집합 결과가 MultiPolygon인 경우 모든 폴리곤 조각을 추출하고 면적 내림차순으로 정렬한다.
     *
     * @param inputs 교집합할 폴리곤 리스트
     * @param limit 반환할 최대 조각 개수 (기본값 3)
     * @return 면적 내림차순의 폴리곤 리스트
     */
    fun intersectLargest(inputs: List<Geometry>, limit: Int = 3): List<Geometry> {
        if (inputs.isEmpty()) return emptyList()
        if (inputs.size == 1) {
            val normalized = normalize(inputs[0])
            return if (normalized.isEmpty) emptyList() else listOf(normalized)
        }

        // 입력을 정규화하고 교집합 계산
        val normalized = inputs.map { normalize(it) }
        val intersection = normalized.reduce { acc, geom ->
            acc.intersection(geom)
        }

        if (intersection.isEmpty) return emptyList()

        // 결과를 폴리곤 조각으로 분해하고 면적 내림차순 정렬
        val polygons = extractPolygons(intersection)
        return polygons
            .sortedByDescending { it.area }
            .take(limit)
    }

    /**
     * 주어진 좌표가 폴리곤에 포함되는지 판정한다.
     *
     * @param area 폴리곤
     * @param lon 경도 (WGS84)
     * @param lat 위도 (WGS84)
     * @return 포함 여부
     */
    fun contains(area: Geometry, lon: Double, lat: Double): Boolean {
        val point = geometryFactory.createPoint(org.locationtech.jts.geom.Coordinate(lon, lat))
        return area.contains(point)
    }

    private fun normalize(geometry: Geometry): Geometry {
        // buffer(0)으로 자기교집합 제거 및 정규화
        return geometry.buffer(0.0)
    }

    private fun extractPolygons(geometry: Geometry): List<Geometry> {
        val result = mutableListOf<Geometry>()
        when {
            geometry.isEmpty -> { /* 빈 도형은 추가하지 않음 */ }
            geometry is Polygon -> result.add(geometry)
            else -> {
                // MultiPolygon이거나 다른 컬렉션 타입
                for (i in 0 until geometry.numGeometries) {
                    val part = geometry.getGeometryN(i)
                    if (part is Polygon && !part.isEmpty) {
                        result.add(part)
                    }
                }
            }
        }
        return result
    }
}
