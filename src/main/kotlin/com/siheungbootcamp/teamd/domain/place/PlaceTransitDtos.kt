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
)
