package com.siheungbootcamp.teamd.domain.board

import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveLocationCacheTest {
    @Test
    fun `가득 찬 위치 캐시는 가장 오래된 위치를 교체한다`() {
        val cache = LiveLocationCache(ttl = Duration.ofMinutes(2), maxEntries = 2)
        val now = Instant.parse("2026-07-27T10:00:00Z")

        cache.put(1, CachedLiveLocation(1, 37.1, 127.1, null, now), now)
        cache.put(2, CachedLiveLocation(1, 37.2, 127.2, null, now.plusSeconds(1)), now.plusSeconds(1))
        cache.put(3, CachedLiveLocation(1, 37.3, 127.3, null, now.plusSeconds(2)), now.plusSeconds(2))

        assertNull(cache.get(1, now.plusSeconds(2)))
        assertEquals(2, cache.size())
    }

    @Test
    fun `스케줄 정리는 요청이 없어도 만료 위치를 제거한다`() {
        val cache = LiveLocationCache(ttl = Duration.ofMinutes(2), maxEntries = 2)
        val now = Instant.parse("2026-07-27T10:00:00Z")
        cache.put(1, CachedLiveLocation(1, 37.1, 127.1, null, now), now)

        cache.evictExpired(now.plus(Duration.ofMinutes(2)).plusSeconds(1))

        assertEquals(0, cache.size())
    }
}
