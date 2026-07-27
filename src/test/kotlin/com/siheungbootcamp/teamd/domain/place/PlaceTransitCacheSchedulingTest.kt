package com.siheungbootcamp.teamd.domain.place

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.scheduling.annotation.Scheduled

class PlaceTransitCacheSchedulingTest {
    @Test
    fun `transit cache removes expired entries on a schedule`() {
        val method = PlaceTransitService::class.java.getDeclaredMethod("evictExpiredCacheEntries")
        val scheduled = method.getAnnotation(Scheduled::class.java)

        assertNotNull(scheduled)
        assertEquals("\${app.tmap.transit-cache-cleanup-interval-ms:3600000}", scheduled.fixedDelayString)
    }
}
