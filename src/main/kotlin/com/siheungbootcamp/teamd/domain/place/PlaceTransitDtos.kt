package com.siheungbootcamp.teamd.domain.place

data class PlaceTransitTimesResponse(
    val items: List<PlaceTransitTimeItem>,
)

data class PlaceTransitTimeItem(
    val participantId: String,
    val nickname: String,
    val avatarColor: String,
    val status: String,
    val totalMinutes: Int? = null,
    val transferCount: Int? = null,
    val totalWalkMinutes: Int? = null,
    val route: TransitRouteDto? = null,
)

data class TransitRouteDto(val legs: List<TransitLegDto>, val path: List<TransitPointDto>)
data class TransitLegDto(val mode: String, val routeName: String?, val startName: String?, val endName: String?, val durationMinutes: Int)
data class TransitPointDto(val lon: Double, val lat: Double)
