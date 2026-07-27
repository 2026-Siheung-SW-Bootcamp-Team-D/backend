package com.siheungbootcamp.teamd.domain.place

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

internal class PlaceTransitCache(
    private val ttl: Duration = Duration.ofHours(24),
    private val maxEntries: Int = 10_000,
) {
    private val entries = ConcurrentHashMap<TransitCacheKey, TransitCachedResult>()

    fun get(key: TransitCacheKey, now: Instant): TransitCachedResult? =
        entries[key]?.takeUnless { it.isExpired(now, ttl) }

    fun put(key: TransitCacheKey, value: TransitCachedResult, now: Instant) {
        if (entries.size >= maxEntries) evictExpired(now)
        if (entries.size < maxEntries) entries[key] = value
    }

    fun evictExpired(now: Instant) {
        entries.entries.removeIf { (_, value) -> value.isExpired(now, ttl) }
    }

    internal fun size(): Int = entries.size
}

internal data class TransitCacheKey(
    val participantId: Long,
    val originHash: String,
    val destLon: Double,
    val destLat: Double,
)

internal data class TransitCachedResult(
    val status: String,
    val totalMinutes: Int?,
    val transferCount: Int?,
    val totalWalkMinutes: Int?,
    val cachedAt: Instant,
) {
    fun isExpired(now: Instant, ttl: Duration): Boolean = cachedAt.plus(ttl).isBefore(now)
    fun isCacheable(): Boolean = status == "READY" || status == "UNAVAILABLE"
}
