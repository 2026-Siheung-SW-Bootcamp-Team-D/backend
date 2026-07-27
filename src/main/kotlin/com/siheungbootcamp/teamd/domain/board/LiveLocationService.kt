package com.siheungbootcamp.teamd.domain.board

import com.siheungbootcamp.teamd.global.auth.AuthorizationChecks
import com.siheungbootcamp.teamd.global.auth.ParticipantPrincipal
import com.siheungbootcamp.teamd.global.error.BusinessException
import com.siheungbootcamp.teamd.global.error.ErrorCode
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private data class CachedLiveLocation(
    val boardId: Long,
    val lat: Double,
    val lon: Double,
    val accuracyMeters: Double?,
    val updatedAt: Instant,
)

/** Ephemeral current-location store. It deliberately never persists location history. */
@Service
class LiveLocationService(
    private val participants: ParticipantRepository,
    private val checks: AuthorizationChecks,
) {
    private val locations = ConcurrentHashMap<Long, CachedLiveLocation>()
    private val ttl = Duration.ofMinutes(2)
    private val maxEntries = 10_000

    fun save(boardId: String, principal: ParticipantPrincipal, request: LiveLocationRequest) {
        checks.requireBoard(principal, boardId)
        val participant = participants.findById(principal.participantId)
            .orElseThrow { BusinessException(ErrorCode.RESOURCE_NOT_FOUND) }
        val now = Instant.now()
        evictExpired(now)
        if (!locations.containsKey(principal.participantId) && locations.size >= maxEntries) return
        locations[principal.participantId] = CachedLiveLocation(
            boardId = requireNotNull(participant.board.id),
            lat = request.lat,
            lon = request.lon,
            accuracyMeters = request.accuracyMeters,
            updatedAt = now,
        )
    }

    fun remove(boardId: String, principal: ParticipantPrincipal) {
        checks.requireBoard(principal, boardId)
        locations.remove(principal.participantId)
    }

    fun list(boardId: String, principal: ParticipantPrincipal): LiveLocationListResponse {
        checks.requireBoard(principal, boardId)
        val actor = participants.findById(principal.participantId)
            .orElseThrow { BusinessException(ErrorCode.RESOURCE_NOT_FOUND) }
        val now = Instant.now()
        evictExpired(now)
        val boardIdInternal = requireNotNull(actor.board.id)
        return LiveLocationListResponse(
            participants.findAllByBoardIdAndActiveTrueOrderById(boardIdInternal).mapNotNull { participant ->
                val cached = locations[requireNotNull(participant.id)]?.takeIf { it.boardId == boardIdInternal && !isExpired(it, now) }
                    ?: return@mapNotNull null
                LiveLocationResponse(
                    participantId = participant.publicId,
                    nickname = participant.nickname,
                    avatarColor = participant.avatarColor,
                    lat = cached.lat,
                    lon = cached.lon,
                    accuracyMeters = cached.accuracyMeters,
                    updatedAt = cached.updatedAt,
                )
            },
        )
    }

    private fun evictExpired(now: Instant) {
        locations.entries.removeIf { (_, value) -> isExpired(value, now) }
    }

    private fun isExpired(value: CachedLiveLocation, now: Instant): Boolean = value.updatedAt.plus(ttl).isBefore(now)
}
