package com.siheungbootcamp.teamd.domain.place

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

// Canonical value objects

/**
 * 지도 좌표: 경도(lon)와 위도(lat)를 포함합니다.
 * WGS84 표준을 사용하며, lon은 경도, lat은 위도입니다.
 * 요청/응답 모두에서 사용됩니다.
 */
data class LocationDto(
    @field:DecimalMin("-180.0") @field:DecimalMax("180.0") val lon: Double,
    @field:DecimalMin("-90.0") @field:DecimalMax("90.0") val lat: Double,
)

/**
 * 외부 API 공급자 정보: sourceProvider가 제공한 장소 정보를 정규화합니다.
 * 이 정보는 사용자가 검색 결과를 확인하거나 원본을 탐색할 때 사용됩니다.
 */
data class SourceDto(
    @field:NotBlank @field:Size(max = 20) val sourceProvider: String,
    @field:Size(max = 100) val providerPlaceId: String?,
    @field:Size(max = 2048) val sourceUrl: String?,
    @field:Size(min = 1, max = 50) val inputMethod: String,
)

// 검색 응답용 - LocationResponse 유지 (backward compatibility)
data class LocationResponse(
    val lon: Double,
    val lat: Double,
)

data class PlaceSourceResponse(
    val sourceProvider: String,
    val providerPlaceId: String?,
    val sourceUrl: String?,
    val inputMethod: String,
)

// Request DTOs

/**
 * POST /boards/{boardId}/places 요청 DTO.
 * 중첩 구조의 location과 source를 포함합니다.
 */
data class CreatePlaceRequest(
    @field:NotBlank @field:Size(max = 80) val name: String,
    @field:Size(max = 100) val category: String?,
    @field:Size(max = 200) val roadAddress: String?,
    @field:Size(max = 200) val jibunAddress: String?,
    @field:Valid val location: LocationDto,
    @field:Valid val source: SourceDto,
)

// Response DTOs

/**
 * Canonical Place 응답 DTO.
 * 중첩 구조의 location과 source, createdByParticipantId, status를 포함합니다.
 */
data class PlaceResponse(
    val placeId: String,
    val boardId: String,
    val status: String,
    val name: String,
    val category: String,
    val roadAddress: String?,
    val jibunAddress: String?,
    val location: LocationDto,
    val source: SourceDto,
    val createdByParticipantId: String,
    val commentCount: Int,
    val createdAt: Instant,
    val likeCount: Int = 0,
    val likedByMe: Boolean = false,
    val selected: Boolean = false,
    val archivedAt: Instant?,
)

data class PlaceListResponse(
    val items: List<PlaceResponse>,
    val page: PageMetadata,
) {
    data class PageMetadata(
        val number: Int,
        val size: Int,
        val totalItems: Long,
        val totalPages: Int,
    )
}

/**
 * P7 canonical 검색 결과: 공급자 중립 형식입니다.
 * 장소 후보, 주소, 주변 검색 결과를 통일된 구조로 반환합니다.
 */
data class PlaceCandidateResponse(
    val provider: String = "KAKAO",
    val items: List<CandidateItem>,
    val hint: String? = null,
) {
    /**
     * Canonical 장소 후보 아이템
     * location과 sourceUrl은 공급자 독립적으로 정규화됩니다.
     */
    data class CandidateItem(
        val providerPlaceId: String,
        val name: String,
        val category: String,
        val roadAddress: String?,
        val jibunAddress: String?,
        val location: LocationResponse,
        val sourceUrl: String?,
        val distanceMeters: Int?,
    )
}

data class AddressCandidateResponse(
    val provider: String = "KAKAO",
    val items: List<AddressItem>,
) {
    data class AddressItem(
        val label: String,
        val roadAddress: String?,
        val location: LocationResponse,
    )
}

data class CoordinateAddressResponse(
    val label: String?,
    val roadAddress: String?,
    val jibunAddress: String?,
    val location: LocationResponse,
)
