package com.siheungbootcamp.teamd.infra.external.tmap

import com.siheungbootcamp.teamd.global.error.BusinessException
import com.siheungbootcamp.teamd.global.error.ErrorCode
import com.siheungbootcamp.teamd.global.external.ExternalApiClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * TMAP Transit API(`POST /transit/routes/sub`)를 통해 출발지에서 도착지까지의
 * 대중교통 이동정보를 조회한다.
 *
 * 요청·응답 계약은 2026-07-20 실제 키로 검증한 결과를 따른다
 * (`docs/api-validation/RESULTS.md`, `results/4_tmap_요약정보_*.json`).
 * 이 엔드포인트는 도착 희망 시각을 받지 않는다 — 항상 현재 시간표 기준 경로를 반환하며
 * (`basis: CURRENT_TIMETABLE`), 권장 출발시각은 호출부가 만남 시각에서 totalSeconds를
 * 빼서 계산한다.
 *
 * 입력: 출발 좌표(lon, lat), 도착 좌표(lon, lat)
 * 출력: totalSeconds(초), transferCount(환승 수), fareAmount(요금, KRW), totalWalkSeconds(총 도보시간, 초)
 * 경로 없음: 예외가 아니라 null 반환 (도메인에서 UNAVAILABLE 상태로 처리)
 * 외부 API 오류: ExternalApiClient 규약에 따라 BusinessException 발생
 */
@Component
class TmapTransitClient(
    private val properties: TmapTransitProperties,
    @Qualifier("tmapExternalApiClient") private val externalApiClient: ExternalApiClient,
    private val mapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    data class TransitSummary(
        val totalSeconds: Int,
        val transferCount: Int,
        val fareAmount: Int, // KRW
        val totalWalkSeconds: Int,
    )

    /**
     * 출발지에서 도착지까지의 대중교통 최단 경로를 조회한다.
     *
     * @param startLon 출발지 경도
     * @param startLat 출발지 위도
     * @param endLon 도착지 경도
     * @param endLat 도착지 위도
     * @return 경로 정보 또는 경로 없음 시 null
     */
    fun searchTransit(
        startLon: Double,
        startLat: Double,
        endLon: Double,
        endLat: Double,
    ): TransitSummary? {
        val url = "${properties.baseUrl}/transit/routes/sub"
        val body = mapOf(
            "startX" to startLon.toString(),
            "startY" to startLat.toString(),
            "endX" to endLon.toString(),
            "endY" to endLat.toString(),
            "count" to 1,
        )

        // appKey는 접근 로그·프록시 로그에 남는 쿼리스트링이 아니라 헤더로 전달한다.
        val response = externalApiClient.post(url, body, headers = mapOf("appKey" to properties.appKey))

        return try {
            val root = mapper.readTree(response)
            val itineraries = root.path("metaData").path("plan").path("itineraries")

            // itineraries 자체가 없으면(metaData/plan 구조가 깨진 응답) 계약 위반이다.
            // "경로가 없다"는 정상적인 빈 배열과 구분해야 한다 — 전자는 재시도 대상(EXTERNAL_BAD_RESPONSE),
            // 후자만 도메인상 정상인 UNAVAILABLE(null)로 수렴해야 한다.
            if (!itineraries.isArray) {
                logger.warn("tmap_transit_malformed_response missing=itineraries")
                throw BusinessException(ErrorCode.EXTERNAL_BAD_RESPONSE)
            }
            if (itineraries.isEmpty) {
                logger.info("tmap_transit_no_route")
                return null
            }

            val itinerary = itineraries[0]

            // 경로가 있다고 응답했는데 필수 필드가 없거나 정수로 변환할 수 없으면 계약 위반이다.
            // isMissingNode만 검사하면 null·문자열·객체 값이 asInt()에서 조용히 0으로 강제 변환되어
            // 이동시간 0초 같은 잘못된 결과가 READY로 저장될 수 있으므로 정수 변환 가능 여부까지 확인한다.
            val totalFareNode = itinerary.path("fare").path("regular").path("totalFare")
            val requiredFields = mapOf(
                "totalTime" to itinerary.path("totalTime"),
                "totalWalkTime" to itinerary.path("totalWalkTime"),
                "transferCount" to itinerary.path("transferCount"),
                "fare.regular.totalFare" to totalFareNode,
            )
            requiredFields.forEach { (field, node) ->
                if (!node.canConvertToInt()) {
                    logger.warn("tmap_transit_invalid_field field=$field")
                    throw BusinessException(ErrorCode.EXTERNAL_BAD_RESPONSE)
                }
            }

            // totalTime/totalWalkTime은 이미 초 단위다 (밀리초가 아님 — 검증 결과 확인됨).
            TransitSummary(
                totalSeconds = itinerary.path("totalTime").asInt(),
                transferCount = itinerary.path("transferCount").asInt(),
                fareAmount = totalFareNode.asInt(),
                totalWalkSeconds = itinerary.path("totalWalkTime").asInt(),
            )
        } catch (e: BusinessException) {
            throw e
        } catch (e: Exception) {
            logger.warn("tmap_transit_parse_error error=${e.message}")
            throw BusinessException(ErrorCode.EXTERNAL_BAD_RESPONSE)
        }
    }
}
