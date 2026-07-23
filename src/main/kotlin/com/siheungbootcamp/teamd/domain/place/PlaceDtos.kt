package com.siheungbootcamp.teamd.domain.place

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Size
import java.time.Instant

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

data class PlaceCandidateResponse(
    val provider: String,
    val items: List<CandidateItem>,
    val hint: String? = null,
) {
    data class CandidateItem(
        val providerPlaceId: String,
        val name: String,
        val category: String,
        val internalCategory: String,
        val addressName: String,
        val roadAddressName: String,
        val lon: Double,
        val lat: Double,
        val providerPlaceUrl: String?,
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
        val lon: Double,
        val lat: Double,
    )
}

data class CoordinateAddressResponse(
    val roadAddressName: String?,
    val addressName: String?,
)
