package com.siheungbootcamp.teamd.domain.place

import com.siheungbootcamp.teamd.domain.board.Participant
import com.siheungbootcamp.teamd.domain.board.ParticipantRepository
import com.siheungbootcamp.teamd.global.auth.AuthorizationChecks
import com.siheungbootcamp.teamd.global.auth.ParticipantPrincipal
import com.siheungbootcamp.teamd.global.crypto.OriginCipher
import com.siheungbootcamp.teamd.global.error.BusinessException
import com.siheungbootcamp.teamd.global.error.ErrorCode
import com.siheungbootcamp.teamd.infra.external.tmap.TmapTransitClient
import org.springframework.stereotype.Service
import org.springframework.scheduling.annotation.Scheduled
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

@Service
class PlaceTransitService(
    private val places: PlaceRepository,
    private val participants: ParticipantRepository,
    private val checks: AuthorizationChecks,
    private val originCipher: OriginCipher,
    private val tmapTransitClient: TmapTransitClient,
) {
    fun getTransitTimes(
        boardId: String,
        placeId: String,
        principal: ParticipantPrincipal,
    ): PlaceTransitTimesResponse {
        checks.requireBoard(principal, boardId)
        val boardIdInternal = participants.findById(principal.participantId)
            .orElseThrow { BusinessException(ErrorCode.RESOURCE_NOT_FOUND) }
            .board.id
            ?: throw BusinessException(ErrorCode.INTERNAL_ERROR)
        val place = places.findByPublicIdAndBoardIdAndDeletedAtIsNull(placeId, boardIdInternal)
            ?.takeIf { it.status == PlaceStatus.ACTIVE }
            ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)

        val items = participants.findAllByBoardIdAndActiveTrueOrderById(boardIdInternal).map { participant ->
            resolveItem(participant, place.lon, place.lat)
        }
        return PlaceTransitTimesResponse(items)
    }

    private fun resolveItem(participant: Participant, destLon: Double, destLat: Double): PlaceTransitTimeItem {
        val ciphertext = participant.originCiphertext
            ?: return baseItem(participant, status = "ORIGIN_REQUIRED")

        val cacheKey = CacheKey(
            participantId = requireNotNull(participant.id),
            originHash = ciphertext.sha256(),
            destLon = destLon,
            destLat = destLat,
        )
        cache[cacheKey]
            ?.takeIf { !it.isExpired(now()) }
            ?.let { cached -> return cached.toResponse(participant) }

        val resolved = try {
            val (originLon, originLat) = decryptOrigin(ciphertext)
            when (val summary = tmapTransitClient.searchTransit(originLon, originLat, destLon, destLat)) {
                null -> CachedTransitResult(status = "UNAVAILABLE", totalMinutes = null, transferCount = null, totalWalkMinutes = null, cachedAt = now())
                else -> CachedTransitResult(
                    status = "READY",
                    totalMinutes = summary.totalSeconds.toMinutesCeil(),
                    transferCount = summary.transferCount,
                    totalWalkMinutes = summary.totalWalkSeconds.toMinutesCeil(),
                    cachedAt = now(),
                )
            }
        } catch (_: Exception) {
            CachedTransitResult(status = "FAILED", totalMinutes = null, transferCount = null, totalWalkMinutes = null, cachedAt = now())
        }

        if (resolved.isCacheable()) {
            cacheResult(cacheKey, resolved)
        }
        return resolved.toResponse(participant)
    }

    private fun baseItem(
        participant: Participant,
        status: String,
        totalMinutes: Int? = null,
        transferCount: Int? = null,
        totalWalkMinutes: Int? = null,
    ) = PlaceTransitTimeItem(
        participantId = participant.publicId,
        nickname = participant.nickname,
        avatarColor = participant.avatarColor,
        status = status,
        totalMinutes = totalMinutes,
        transferCount = transferCount,
        totalWalkMinutes = totalWalkMinutes,
    )

    private fun decryptOrigin(ciphertext: ByteArray): Pair<Double, Double> {
        val buffer = ByteBuffer.wrap(originCipher.decrypt(ciphertext))
        return buffer.double to buffer.double
    }

    private fun CachedTransitResult.toResponse(participant: Participant) = baseItem(
        participant = participant,
        status = status,
        totalMinutes = totalMinutes,
        transferCount = transferCount,
        totalWalkMinutes = totalWalkMinutes,
    )

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString(separator = "") { "%02x".format(it) }

    private fun Int.toMinutesCeil(): Int = ceil(this / 60.0).toInt()

    private fun now(): Instant = Instant.now()

    /**
     * Keeps the process-local transit cache bounded while preserving the
     * no-dependency MVP. Expired entries are also removed before insertions so
     * a busy process does not retain entries until the next scheduled sweep.
     */
    @Scheduled(fixedDelayString = "\${app.tmap.transit-cache-cleanup-interval-ms:3600000}")
    fun evictExpiredCacheEntries() {
        val current = now()
        cache.entries.removeIf { (_, value) -> value.isExpired(current) }
    }

    private fun cacheResult(cacheKey: CacheKey, result: CachedTransitResult) {
        if (cache.size >= MAX_CACHE_ENTRIES) {
            evictExpiredCacheEntries()
        }
        if (cache.size < MAX_CACHE_ENTRIES) {
            cache[cacheKey] = result
        }
    }

    private data class CacheKey(
        val participantId: Long,
        val originHash: String,
        val destLon: Double,
        val destLat: Double,
    )

    private data class CachedTransitResult(
        val status: String,
        val totalMinutes: Int?,
        val transferCount: Int?,
        val totalWalkMinutes: Int?,
        val cachedAt: Instant,
    ) {
        fun isExpired(now: Instant): Boolean = cachedAt.plus(CACHE_TTL).isBefore(now)
        fun isCacheable(): Boolean = status == "READY" || status == "UNAVAILABLE"
    }

    companion object {
        private val CACHE_TTL: Duration = Duration.ofHours(24)
        private const val MAX_CACHE_ENTRIES = 10_000
        private val cache = ConcurrentHashMap<CacheKey, CachedTransitResult>()
    }
}
