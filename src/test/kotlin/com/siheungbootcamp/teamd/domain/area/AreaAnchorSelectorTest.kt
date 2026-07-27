package com.siheungbootcamp.teamd.domain.area

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AreaAnchorSelectorTest {
    @Test
    fun `1km 안의 중복 후보를 제외하고 최대 10개를 유지한다`() {
        val anchors = (0 until 12).map { index ->
            anchor(
                id = "station-$index",
                lon = 127.0 + index * 0.012,
                distanceMeters = index * 1_100,
            )
        } + anchor(
            id = "near-duplicate",
            lon = 127.0005,
            distanceMeters = 50,
        )

        val selected = selectSpacedAreaAnchors(anchors)

        assertEquals(10, selected.size)
        assertEquals((1..10).toList(), selected.map { it.rank })
        assertTrue(selected.none { it.anchorId == "near-duplicate" })
        selected.forEachIndexed { index, anchor ->
            selected.drop(index + 1).forEach { other ->
                assertTrue(
                    GeometryService.haversineDistanceMeters(
                        anchor.location.lon,
                        anchor.location.lat,
                        other.location.lon,
                        other.location.lat,
                    ) >= 1_000,
                )
            }
        }
    }

    private fun anchor(id: String, lon: Double, distanceMeters: Int) = AreaAnchorDto(
        anchorId = id,
        provider = "KAKAO",
        providerPlaceId = id,
        category = "SUBWAY_STATION",
        name = id,
        roadAddress = null,
        location = ParticipantCenterDto(lon = lon, lat = 37.5),
        centerDistanceMeters = distanceMeters,
        rank = 0,
    )
}
