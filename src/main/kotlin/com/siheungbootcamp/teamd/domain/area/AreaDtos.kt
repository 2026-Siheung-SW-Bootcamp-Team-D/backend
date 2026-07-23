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

data class AreaSearchResult(
    val candidates: List<AreaCandidateResponse>,
)

data class AreaCandidateResponse(
    val candidateId: String,
    val name: String,
    val lon: Double,
    val lat: Double,
    val metrics: JsonNode,
    val reasons: JsonNode,
    val rank: Int,
)
