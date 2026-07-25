package com.siheungbootcamp.teamd.infra.external.odsay

import com.siheungbootcamp.teamd.global.error.BusinessException
import com.siheungbootcamp.teamd.global.error.ErrorCode
import com.siheungbootcamp.teamd.global.external.ExternalApiClient
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * ODsay 대중교통 도달권(Isochrone) API를 통해 주어진 출발지와 시간 제한에서
 * 도달 가능한 영역을 폴리곤으로 반환한다.
 *
 * 요청: 출발 좌표(lon, lat), 이동시간(분 단위: 30/45/60)
 * 응답: 대중교통 도달권 폴리곤 또는 MultiPolygon (GeoJSON)
 *
 * 외부 API 오류: ExternalApiClient 규약에 따라 BusinessException 발생
 */
@Component
class OdsayIsochroneClient(
    private val properties: OdsayProperties,
    @Qualifier("odsayExternalApiClient") private val externalApiClient: ExternalApiClient,
    private val mapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val geometryFactory = GeometryFactory()

    /**
     * 주어진 출발지와 이동시간에서 대중교통으로 도달 가능한 영역을 조회한다.
     *
     * @param lon 출발지 경도 (WGS84)
     * @param lat 출발지 위도 (WGS84)
     * @param durationMin 이동시간(분): 30, 45, 60 중 하나
     * @return 도달 가능 영역의 폴리곤 (Polygon 또는 MultiPolygon)
     * @throws BusinessException 외부 API 오류 시
     */
    fun fetch(lon: Double, lat: Double, durationMin: Int): Geometry {
        val url = "${properties.baseUrl}/v1/api/searchPubTransIsochrone" +
            "?x=$lon&y=$lat&searchTime=$durationMin&searchMethod=4&apiKey=${properties.apiKey}"

        val response = externalApiClient.get(url)

        return try {
            val root = mapper.readTree(response)
            val feature = root.path("result").path("feature")

            // feature 배열이 없으면 계약 위반
            if (!feature.isArray || feature.isEmpty) {
                logger.warn("odsay_isochrone_malformed_response missing=feature")
                throw BusinessException(ErrorCode.EXTERNAL_BAD_RESPONSE)
            }

            val geometry = feature[0].path("geometry")

            // geometry 객체가 없거나 타입·좌표가 없으면 계약 위반
            if (geometry.isMissingNode || !geometry.has("type") || !geometry.has("coordinates")) {
                logger.warn("odsay_isochrone_invalid_geometry")
                throw BusinessException(ErrorCode.EXTERNAL_BAD_RESPONSE)
            }

            val jtsGeometry = geoJsonToJts(geometry)

            if (jtsGeometry.isEmpty) {
                logger.warn("odsay_isochrone_empty_geometry")
                throw BusinessException(ErrorCode.EXTERNAL_BAD_RESPONSE)
            }

            logger.info("odsay_isochrone_fetch durationMin=$durationMin")
            jtsGeometry
        } catch (e: BusinessException) {
            throw e
        } catch (e: Exception) {
            logger.warn("odsay_isochrone_parse_error error=${e.message}")
            throw BusinessException(ErrorCode.EXTERNAL_BAD_RESPONSE)
        }
    }

    private fun geoJsonToJts(geometryNode: JsonNode): Geometry {
        val type = geometryNode.path("type").asText()
        val coordinates = geometryNode.path("coordinates")

        return when (type) {
            "Polygon" -> parsePolygon(coordinates)
            "MultiPolygon" -> parseMultiPolygon(coordinates)
            else -> throw IllegalArgumentException("Unsupported geometry type: $type")
        }
    }

    private fun parsePolygon(coordinates: JsonNode): Geometry {
        val rings = mutableListOf<Coordinate>()

        // Parse the first ring (shell)
        val shellCoords = mutableListOf<Coordinate>()
        val firstRing = coordinates[0]
        for (i in 0 until firstRing.size()) {
            val coord = firstRing[i]
            shellCoords.add(Coordinate(coord[0].asDouble(), coord[1].asDouble()))
        }

        if (shellCoords.size < 4) {
            throw IllegalArgumentException("Invalid ring: fewer than 4 coordinates")
        }

        val shell = geometryFactory.createLinearRing(shellCoords.toTypedArray())

        // Parse holes if any
        val holes = mutableListOf<org.locationtech.jts.geom.LinearRing>()
        for (i in 1 until coordinates.size()) {
            val holeCoords = mutableListOf<Coordinate>()
            val holeRing = coordinates[i]
            for (j in 0 until holeRing.size()) {
                val coord = holeRing[j]
                holeCoords.add(Coordinate(coord[0].asDouble(), coord[1].asDouble()))
            }
            holes.add(geometryFactory.createLinearRing(holeCoords.toTypedArray()))
        }

        return geometryFactory.createPolygon(shell, if (holes.isEmpty()) null else holes.toTypedArray())
    }

    private fun parseMultiPolygon(coordinates: JsonNode): Geometry {
        val polygons = mutableListOf<org.locationtech.jts.geom.Polygon>()
        for (i in 0 until coordinates.size()) {
            val poly = parsePolygon(coordinates[i])
            if (poly is org.locationtech.jts.geom.Polygon) {
                polygons.add(poly)
            }
        }
        return geometryFactory.createMultiPolygon(polygons.toTypedArray())
    }
}
