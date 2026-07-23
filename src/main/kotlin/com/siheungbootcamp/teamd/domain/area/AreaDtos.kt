package com.siheungbootcamp.teamd.domain.area

import jakarta.validation.constraints.NotBlank
import tools.jackson.databind.JsonNode

/**
 * 지역 탐색 요청/응답 DTO.
 */

data class CreateAreaSearchJobRequest(
    @field:NotBlank
    val durationMin: Int,
)

data class CreateAreaSearchJobResponse(
    val jobId: String,
    val status: String,
    val estimatedExternalCalls: EstimatedExternalCalls,
)

data class EstimatedExternalCalls(
    val odsay: Int,
    val kakaoLocal: Int,
    val tmapTransit: Int,
)

data class GetAreaSearchJobResponse(
    val jobId: String,
    val status: String,
    val durationMin: Int,
    val result: AreaSearchResult?,
    val errorCode: String?,
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
    val name: String,
    val lon: Double,
    val lat: Double,
    val centerDistanceMeters: Int,
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
