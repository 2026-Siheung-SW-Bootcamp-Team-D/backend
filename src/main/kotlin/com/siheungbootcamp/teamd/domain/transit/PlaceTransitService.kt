package com.siheungbootcamp.teamd.domain.transit

import com.siheungbootcamp.teamd.domain.board.Participant
import com.siheungbootcamp.teamd.domain.board.ParticipantRepository
import com.siheungbootcamp.teamd.domain.place.PlaceRepository
import com.siheungbootcamp.teamd.domain.place.PlaceStatus
import com.siheungbootcamp.teamd.domain.place.PlaceTransitTimeItem
import com.siheungbootcamp.teamd.domain.place.PlaceTransitTimesResponse
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
import java.time.Instant
import kotlin.math.ceil

@Service
class PlaceTransitService(
    private val places: PlaceRepository,
    private val participants: ParticipantRepository,
    private val checks: AuthorizationChecks,
    private val originCipher: OriginCipher,
    private val tmapTransitClient: TmapTransitClient,
) {
    private val transitCache = PlaceTransitCache()

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

        val cacheKey = TransitCacheKey(
            participantId = requireNotNull(participant.id),
            originHash = ciphertext.sha256(),
            destLon = destLon,
            destLat = destLat,
        )
        transitCache.get(cacheKey, now())
            ?.let { cached -> return cached.toResponse(participant) }

        val resolved = try {
            val (originLon, originLat) = decryptOrigin(ciphertext)
            when (val summary = tmapTransitClient.searchTransit(originLon, originLat, destLon, destLat)) {
                null -> TransitCachedResult(status = "UNAVAILABLE", totalMinutes = null, transferCount = null, totalWalkMinutes = null, cachedAt = now())
                else -> TransitCachedResult(
                    status = "READY",
                    totalMinutes = summary.totalSeconds.toMinutesCeil(),
                    transferCount = summary.transferCount,
                    totalWalkMinutes = summary.totalWalkSeconds.toMinutesCeil(),
                    cachedAt = now(),
                )
            }
        } catch (_: Exception) {
            TransitCachedResult(status = "FAILED", totalMinutes = null, transferCount = null, totalWalkMinutes = null, cachedAt = now())
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

    private fun TransitCachedResult.toResponse(participant: Participant) = baseItem(
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
        transitCache.evictExpired(now())
    }

    private fun cacheResult(cacheKey: TransitCacheKey, result: TransitCachedResult) =
        transitCache.put(cacheKey, result, now())
}
