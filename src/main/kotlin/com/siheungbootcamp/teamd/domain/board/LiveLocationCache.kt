package com.siheungbootcamp.teamd.domain.board

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

internal data class CachedLiveLocation(
    val boardId: Long,
    val lat: Double,
    val lon: Double,
    val accuracyMeters: Double?,
    val updatedAt: Instant,
)

/** Bounded ephemeral location storage; every mutating capacity decision is atomic. */
internal class LiveLocationCache(
    private val ttl: Duration = Duration.ofMinutes(2),
    private val maxEntries: Int = 10_000,
) {
    private val entries = ConcurrentHashMap<Long, CachedLiveLocation>()
    private val mutationLock = Any()

    fun get(participantId: Long, now: Instant): CachedLiveLocation? =
        entries[participantId]?.takeUnless { isExpired(it, now) }

    fun put(participantId: Long, location: CachedLiveLocation, now: Instant) = synchronized(mutationLock) {
        removeExpired(now)
        if (!entries.containsKey(participantId) && entries.size >= maxEntries) {
            entries.entries.minByOrNull { it.value.updatedAt }?.key?.let(entries::remove)
        }
        entries[participantId] = location
    }

    fun remove(participantId: Long) {
        entries.remove(participantId)
    }

    fun evictExpired(now: Instant) = synchronized(mutationLock) {
        removeExpired(now)
    }

    private fun removeExpired(now: Instant) {
        entries.entries.removeIf { (_, value) -> isExpired(value, now) }
    }

    private fun isExpired(value: CachedLiveLocation, now: Instant): Boolean = value.updatedAt.plus(ttl).isBefore(now)

    internal fun size(): Int = entries.size
}
