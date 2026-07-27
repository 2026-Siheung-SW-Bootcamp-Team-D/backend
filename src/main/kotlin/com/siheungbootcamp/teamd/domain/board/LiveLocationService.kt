package com.siheungbootcamp.teamd.domain.board

import com.siheungbootcamp.teamd.global.auth.AuthorizationChecks
import com.siheungbootcamp.teamd.global.auth.ParticipantPrincipal
import com.siheungbootcamp.teamd.global.error.BusinessException
import com.siheungbootcamp.teamd.global.error.ErrorCode
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant

/** Ephemeral current-location store. It deliberately never persists location history. */
@Service
class LiveLocationService(
    private val participants: ParticipantRepository,
    private val checks: AuthorizationChecks,
) {
    private val locations = LiveLocationCache()

    fun save(boardId: String, principal: ParticipantPrincipal, request: LiveLocationRequest) {
        checks.requireBoard(principal, boardId)
        val participant = participants.findById(principal.participantId)
            .orElseThrow { BusinessException(ErrorCode.RESOURCE_NOT_FOUND) }
        val now = Instant.now()
        locations.put(principal.participantId, CachedLiveLocation(
            boardId = requireNotNull(participant.board.id),
            lat = request.lat,
            lon = request.lon,
            accuracyMeters = request.accuracyMeters,
            updatedAt = now,
        ), now)
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
        val boardIdInternal = requireNotNull(actor.board.id)
        return LiveLocationListResponse(
            participants.findAllByBoardIdAndActiveTrueOrderById(boardIdInternal).mapNotNull { participant ->
                val cached = locations.get(requireNotNull(participant.id), now)?.takeIf { it.boardId == boardIdInternal }
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

    @Scheduled(fixedDelayString = "\${app.live-location.cache-cleanup-interval-ms:60000}")
    fun evictExpiredLocations() {
        locations.evictExpired(Instant.now())
    }
}
