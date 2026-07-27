package com.siheungbootcamp.teamd.domain.transit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class PlaceTransitCacheTest {
    @Test
    fun `transit cache removes expired entries`() {
        val now = Instant.parse("2026-07-27T00:00:00Z")
        val cache = PlaceTransitCache(ttl = Duration.ofHours(24))
        val expiredKey = TransitCacheKey(1, "expired", 126.98, 37.56)
        val activeKey = TransitCacheKey(2, "active", 126.99, 37.57)
        cache.put(expiredKey, readyResult(now.minus(Duration.ofHours(25))), now)
        cache.put(activeKey, readyResult(now.minus(Duration.ofHours(23))), now)

        cache.evictExpired(now)

        assertEquals(null, cache.get(expiredKey, now))
        assertEquals("READY", cache.get(activeKey, now)?.status)
        assertEquals(1, cache.size())
    }

    @Test
    fun `transit cache does not exceed its entry limit`() {
        val now = Instant.parse("2026-07-27T00:00:00Z")
        val cache = PlaceTransitCache(maxEntries = 1)
        val firstKey = TransitCacheKey(1, "first", 126.98, 37.56)
        val secondKey = TransitCacheKey(2, "second", 126.99, 37.57)

        cache.put(firstKey, readyResult(now), now)
        cache.put(secondKey, readyResult(now), now)

        assertEquals(1, cache.size())
        assertEquals("READY", cache.get(firstKey, now)?.status)
        assertEquals(null, cache.get(secondKey, now))
    }

    private fun readyResult(cachedAt: Instant) = TransitCachedResult(
        status = "READY", totalMinutes = 30, transferCount = 1, totalWalkMinutes = 5, cachedAt = cachedAt,
    )
}
