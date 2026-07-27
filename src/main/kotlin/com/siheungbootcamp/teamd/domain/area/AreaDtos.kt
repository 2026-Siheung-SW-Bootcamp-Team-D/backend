package com.siheungbootcamp.teamd.domain.area

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import tools.jackson.databind.JsonNode
import java.time.Instant

/**
 * 지역 탐색 요청/응답 DTO.
 */

data class CreateAreaSearchJobRequest(
    @field:Min(1)
    val durationMin: Int,
    @field:Size(min = 2)
    val participantIds: List<String>? = null,
)

data class CreateAreaSearchJobResponse(
    val job: AreaJobResponse,
)

data class EstimatedExternalCalls(
    val odsay: Int,
    val kakaoLocal: Int,
    val tmapTransit: Int,
)

data class GetAreaSearchJobResponse(
    val job: AreaJobResponse,
    val result: AreaSearchResult?,
)

/**
 * 보드 메인 지도에서 시간별 공통 도달 영역을 다시 그릴 때 쓰는 축약 응답이다.
 * 개별 도달권과 기준점은 출발지 추론·불필요한 화면 결합을 막기 위해 포함하지 않는다.
 */
data class AreaSearchMapResultsResponse(
    val results: List<AreaSearchMapResult>,
)

data class AreaSearchMapResult(
    val jobId: String,
    val durationMin: Int,
    val finishedAt: Instant?,
    val participantCenter: ParticipantCenterDto?,
    val commonArea: JsonNode?,
)

data class AreaJobResponse(
    val jobId: String,
    val status: String,
    val durationMin: Int,
    val createdAt: Instant,
    val resultSource: String? = null,
    val errorCode: String? = null,
    val estimatedExternalCalls: EstimatedExternalCalls? = null,
)

data class ParticipantCenterDto(
    val lon: Double,
    val lat: Double,
)

data class AnonymousIsochroneDto(
    val areaId: String,
    val geometry: JsonNode,
)

data class AreaComputationResult(
    val isochrones: List<AnonymousIsochroneDto>,
    val commonArea: JsonNode?,
    val participantCenter: ParticipantCenterDto?,
    val anchors: List<AreaAnchorDto>,
) {
    data class ParticipantCenter(val lon: Double, val lat: Double)
}

data class AreaAnchorDto(
    val anchorId: String,
    val provider: String,
    val providerPlaceId: String?,
    val category: String,
    val name: String,
    val roadAddress: String?,
    val location: ParticipantCenterDto,
    val centerDistanceMeters: Int,
    val rank: Int,
)

data class AreaSearchResult(
    val participantCenter: ParticipantCenterDto?,
    val isochrones: List<AnonymousIsochroneDto>,
    val commonArea: JsonNode?,
    val anchors: List<AreaAnchorDto>,
)

// Legacy DTO for backward compatibility (internal use)
data class AreaCandidateResponse(
    val candidateId: String,
    val name: String,
    val lon: Double,
    val lat: Double,
    val metrics: JsonNode,
    val reasons: JsonNode,
    val rank: Int,
)
