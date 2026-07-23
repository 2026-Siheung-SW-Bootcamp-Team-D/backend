package com.siheungbootcamp.teamd.domain.place

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Size
import java.time.Instant

// Canonical value objects

/**
 * 지도 좌표: 경도(lon)와 위도(lat)를 포함합니다.
 * WGS84 표준을 사용하며, lon은 경도, lat은 위도입니다.
 */
data class LocationResponse(
    val lon: Double,
    val lat: Double,
)

/**
 * 외부 API 공급자 정보: sourceProvider가 제공한 장소 정보를 정규화합니다.
 * 이 정보는 사용자가 검색 결과를 확인하거나 원본을 탐색할 때 사용됩니다.
 */
data class PlaceSourceResponse(
    val sourceProvider: String,
    val providerPlaceId: String?,
    val sourceUrl: String?,
    val inputMethod: String,
)

// Request DTOs

data class CreatePlaceRequest(
    @field:Size(min = 1, max = 100) val name: String,
    @field:DecimalMin("-180.0") @field:DecimalMax("180.0") val lon: Double,
    @field:DecimalMin("-90.0") @field:DecimalMax("90.0") val lat: Double,
    @field:Size(max = 200) val addressName: String?,
    @field:Size(max = 200) val roadAddressName: String?,
    @field:Size(min = 1, max = 100) val internalCategory: String,
    @field:Size(max = 20) val provider: String?,
    @field:Size(max = 100) val providerPlaceId: String?,
    @field:Size(max = 2048) val providerPlaceUrl: String?,
    val source: String,
)

// Response DTOs

data class PlaceResponse(
    val placeId: String,
    val name: String,
    val lon: Double,
    val lat: Double,
    val addressName: String?,
    val roadAddressName: String?,
    val internalCategory: String,
    val provider: String?,
    val providerPlaceId: String?,
    val providerPlaceUrl: String?,
    val source: String,
    val proposerId: String,
    val commentCount: Int,
    val createdAt: Instant,
    val likeCount: Int = 0,
    val likedByMe: Boolean = false,
    val selected: Boolean = false,
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
        val internalCategory: String,
        val addressName: String,
        val roadAddressName: String,
        val location: LocationResponse,
        val sourceUrl: String?,
        val distanceMeters: Int?,
    )
}

data class AddressCandidateResponse(
    val items: List<AddressItem>,
) {
    data class AddressItem(
        val addressName: String,
        val roadAddressName: String?,
        val addressType: String,
        val location: LocationResponse,
    )
}

data class CoordinateAddressResponse(
    val roadAddressName: String?,
    val addressName: String?,
)
