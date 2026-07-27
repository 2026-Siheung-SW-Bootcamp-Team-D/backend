package com.siheungbootcamp.teamd.domain.transit

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Retains successful per-participant transit summaries inside one application
 * process so repeated place-detail requests do not call TMAP again.
 *
 * Entries expire after [ttl], and [maxEntries] bounds memory use if callers
 * continuously request unique origin/destination pairs. [PlaceTransitService]
 * reads through this cache, stores only cacheable results, and invokes
 * [evictExpired] from its scheduled cleanup task.
 */
internal class PlaceTransitCache(
    private val ttl: Duration = Duration.ofHours(24),
    private val maxEntries: Int = 10_000,
) {
    private val entries = ConcurrentHashMap<TransitCacheKey, TransitCachedResult>()
    private val mutationLock = Any()

    fun get(key: TransitCacheKey, now: Instant): TransitCachedResult? =
        entries[key]?.takeUnless { it.isExpired(now, ttl) }

    fun put(key: TransitCacheKey, value: TransitCachedResult, now: Instant) = synchronized(mutationLock) {
        if (entries.size >= maxEntries) removeExpired(now)
        if (entries.size < maxEntries) entries[key] = value
    }

    fun evictExpired(now: Instant) = synchronized(mutationLock) {
        removeExpired(now)
    }

    private fun removeExpired(now: Instant) {
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
