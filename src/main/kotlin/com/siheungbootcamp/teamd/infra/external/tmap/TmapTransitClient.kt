package com.siheungbootcamp.teamd.infra.external.tmap

import com.siheungbootcamp.teamd.global.error.BusinessException
import com.siheungbootcamp.teamd.global.error.ErrorCode
import com.siheungbootcamp.teamd.global.external.ExternalApiClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * TMAP Transit API를 통해 출발지에서 도착지까지의 대중교통 이동정보를 조회한다.
 *
 * 입력: 출발 좌표(lon, lat), 도착 좌표(lon, lat), 도착 희망 시각(Instant)
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
     * @param arrivalAt 도착 희망 시각
     * @return 경로 정보 또는 경로 없음 시 null
     */
    fun searchTransit(
        startLon: Double,
        startLat: Double,
        endLon: Double,
        endLat: Double,
        arrivalAt: Instant,
    ): TransitSummary? {
        val arrivalDateStr = arrivalAt
            .atZone(ZoneId.of("Asia/Seoul"))
            .format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        val arrivalTimeStr = arrivalAt
            .atZone(ZoneId.of("Asia/Seoul"))
            .format(DateTimeFormatter.ofPattern("HHmmss"))

        val url = buildString {
            append("${properties.baseUrl}/tmap/routes/recognition")
            append("?startX=$startLon&startY=$startLat")
            append("&endX=$endLon&endY=$endLat")
            append("&arrivalDate=$arrivalDateStr&arrivalTime=$arrivalTimeStr")
        }

        // appKey는 접근 로그·프록시 로그에 남는 쿼리스트링이 아니라 헤더로 전달한다.
        val response = externalApiClient.get(url, headers = mapOf("appKey" to properties.appKey))

        return try {
            val root = mapper.readTree(response)
            val summary = root.path("summary")

            if (summary.isEmpty) {
                logger.info("tmap_transit_no_route")
                return null
            }

            // 경로가 있다고 응답했는데 필수 필드가 없으면 계약 위반이다. 0으로 조용히 채우면
            // 잘못된 recommendedDepartureAt이 계산되므로 명시적으로 실패시킨다.
            val requiredFields = listOf("totalTime", "transferCount", "totalFare", "totalWalkTime")
            requiredFields.forEach { field ->
                if (summary.path(field).isMissingNode) {
                    logger.warn("tmap_transit_missing_field field=$field")
                    throw BusinessException(ErrorCode.EXTERNAL_BAD_RESPONSE)
                }
            }

            // totalTime/totalWalkTime: milliseconds → 초로 변환
            TransitSummary(
                totalSeconds = (summary.path("totalTime").asLong() / 1000).toInt(),
                transferCount = summary.path("transferCount").asInt(),
                fareAmount = summary.path("totalFare").asInt(),
                totalWalkSeconds = (summary.path("totalWalkTime").asLong() / 1000).toInt(),
            )
        } catch (e: BusinessException) {
            throw e
        } catch (e: Exception) {
            logger.warn("tmap_transit_parse_error error=${e.message}")
            throw BusinessException(ErrorCode.EXTERNAL_BAD_RESPONSE)
        }
    }
}
