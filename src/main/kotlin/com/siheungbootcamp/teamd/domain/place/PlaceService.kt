package com.siheungbootcamp.teamd.domain.place

import com.siheungbootcamp.teamd.global.auth.AuthorizationChecks
import com.siheungbootcamp.teamd.global.auth.ParticipantPrincipal
import com.siheungbootcamp.teamd.global.error.BusinessException
import com.siheungbootcamp.teamd.global.error.ErrorCode
import com.siheungbootcamp.teamd.global.web.PageResponse
import com.siheungbootcamp.teamd.domain.board.BoardRepository
import com.siheungbootcamp.teamd.domain.board.ParticipantRepository
import com.siheungbootcamp.teamd.domain.comment.CommentRepository
import com.siheungbootcamp.teamd.infra.external.kakao.KakaoLocalClient
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URL

/**
 * 장소 검색·등록·조회·삭제의 비즈니스 로직을 담당한다.
 *
 * 검색은 외부 API를 호출하지만 저장하지 않는다.
 * 등록은 제안자, 삭제는 모든 활성 참여자가 가능하며 상태를 검증한다.
 * 선택된 장소 삭제 시 보드의 선택 포인터를 함께 cleared한다.
 */
@Service
@Transactional(readOnly = true)
class PlaceService(
    private val places: PlaceRepository,
    private val boards: BoardRepository,
    private val participants: ParticipantRepository,
    private val comments: CommentRepository,
    private val likes: PlaceLikeRepository,
    private val kakao: KakaoLocalClient,
    private val checks: AuthorizationChecks,
    private val usageCheckers: List<PlaceUsageChecker> = emptyList(),
) {
    fun searchKeyword(boardId: String, principal: ParticipantPrincipal, query: String, lon: Double?, lat: Double?, radius: Int?): PlaceCandidateResponse {
        checks.requireBoard(principal, boardId)
        validateQuery(query)
        validateSearchLocation(lon, lat, radius)

        val candidates = kakao.searchKeyword(query, lon, lat, radius)
        return PlaceCandidateResponse(
            provider = "KAKAO",
            items = candidates.map { c ->
                PlaceCandidateResponse.CandidateItem(
                    providerPlaceId = c.providerPlaceId,
                    name = c.name,
                    category = c.category,
                    internalCategory = c.internalCategory,
                    addressName = c.addressName,
                    roadAddressName = c.roadAddressName,
                    lon = c.lon,
                    lat = c.lat,
                    providerPlaceUrl = c.providerPlaceUrl,
                    distanceMeters = c.distanceMeters,
                )
            },
            hint = if (candidates.isEmpty()) "장소명에 지역이나 지점명을 더해 보세요." else null,
        )
    }

    fun searchAddress(boardId: String, principal: ParticipantPrincipal, query: String): AddressCandidateResponse {
        checks.requireBoard(principal, boardId)
        validateQuery(query)

        val candidates = kakao.searchAddress(query)
        return AddressCandidateResponse(
            items = candidates.map { c ->
                AddressCandidateResponse.AddressItem(
                    addressName = c.addressName,
                    roadAddressName = c.roadAddressName,
                    addressType = c.addressType,
                    lon = c.lon,
                    lat = c.lat,
                )
            },
        )
    }

    fun coord2Address(boardId: String, principal: ParticipantPrincipal, lon: Double, lat: Double): CoordinateAddressResponse {
        checks.requireBoard(principal, boardId)
        if (lon < -180.0 || lon > 180.0 || lat < -90.0 || lat > 90.0) throw BusinessException(ErrorCode.INVALID_ARGUMENT)

        val result = kakao.coord2Address(lon, lat)
        return CoordinateAddressResponse(
            roadAddressName = result.roadAddressName,
            addressName = result.addressName,
        )
    }

    @Transactional
    fun create(boardId: String, principal: ParticipantPrincipal, request: CreatePlaceRequest): PlaceResponse {
        checks.requireBoard(principal, boardId)
        val board = boards.findByPublicId(boardId) ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        val boardId_internal = board.id ?: throw BusinessException(ErrorCode.INTERNAL_ERROR)
        val proposer = participants.findByIdAndBoardId(principal.participantId, boardId_internal)
            ?: throw BusinessException(ErrorCode.FORBIDDEN)

        // Validate internal category
        if (request.internalCategory !in VALID_CATEGORIES) {
            throw BusinessException(ErrorCode.INVALID_ARGUMENT)
        }

        // Validate source
        val validSources = setOf("SEARCH_SELECT", "MANUAL_PIN")
        if (!validSources.contains(request.source)) {
            throw BusinessException(ErrorCode.INVALID_ARGUMENT)
        }

        val place = Place(
            board = board,
            proposer = proposer,
            name = request.name,
            lon = request.lon,
            lat = request.lat,
            addressName = request.addressName,
            roadAddressName = request.roadAddressName,
            internalCategory = request.internalCategory,
            provider = request.provider,
            providerPlaceId = request.providerPlaceId,
            providerPlaceUrl = validateProviderUrl(request.providerPlaceUrl),
            source = request.source,
        )

        val saved = places.save(place)
        val placeId_internal = saved.id ?: throw BusinessException(ErrorCode.INTERNAL_ERROR)
        val commentCount = comments.countByPlaceIdAndNotDeleted(placeId_internal).toInt()
        return toResponse(saved, commentCount, 0, false, false)
    }

    fun get(boardId: String, placeId: String, principal: ParticipantPrincipal): PlaceResponse {
        checks.requireBoard(principal, boardId)
        val board = boards.findByPublicId(boardId) ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        val boardId_internal = board.id ?: throw BusinessException(ErrorCode.INTERNAL_ERROR)

        val place = places.findByPublicIdAndBoardIdAndDeletedAtIsNull(placeId, boardId_internal)
            ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)

        val placeId_internal = place.id ?: throw BusinessException(ErrorCode.INTERNAL_ERROR)
        val commentCount = comments.countByPlaceIdAndNotDeleted(placeId_internal).toInt()
        val likeCount = likes.countByPlaceId(placeId_internal).toInt()
        val likedByMe = likes.existsByPlaceIdAndParticipantId(placeId_internal, principal.participantId)
        val isSelected = placeId_internal == board.selectedPlaceId
        return toResponse(place, commentCount, likeCount, likedByMe, isSelected)
    }

    fun list(
        boardId: String,
        principal: ParticipantPrincipal,
        category: String?,
        sort: String?,
        minLon: Double?,
        minLat: Double?,
        maxLon: Double?,
        maxLat: Double?,
        pageable: Pageable,
    ): PageResponse<PlaceResponse> {
        checks.requireBoard(principal, boardId)
        val board = boards.findByPublicId(boardId) ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        val boardId_internal = board.id ?: throw BusinessException(ErrorCode.INTERNAL_ERROR)

        if (category != null && category !in VALID_CATEGORIES) throw BusinessException(ErrorCode.INVALID_ARGUMENT)
        val sortBy = sort ?: "RECENT"
        if (sortBy !in setOf("RECENT", "COMMENTS")) throw BusinessException(ErrorCode.INVALID_ARGUMENT)

        val page = places.findByBoardIdFiltered(boardId_internal, category, minLon, minLat, maxLon, maxLat, pageable)

        // N+1 방지: 한 번의 쿼리로 모든 장소의 댓글 수를 집계
        val placeIds = page.content.mapNotNull { it.id }
        val commentCounts = if (placeIds.isNotEmpty()) {
            comments.countCommentsByPlaceIds(placeIds).associate { row ->
                val placeId = row[0] as Long
                val count = row[1] as Long
                placeId to count
            }
        } else {
            emptyMap()
        }

        // N+1 방지: 한 번의 쿼리로 모든 장소의 좋아요 수를 집계
        val likeCounts = if (placeIds.isNotEmpty()) {
            likes.countLikesByPlaceIds(placeIds).associate { row ->
                val placeId = row[0] as Long
                val count = row[1] as Long
                placeId to count
            }
        } else {
            emptyMap()
        }

        // N+1 방지: 한 번의 쿼리로 현재 사용자가 좋아요한 장소들을 조회
        val likedPlaceIds = if (placeIds.isNotEmpty()) {
            likes.findLikedPlaceIdsByParticipantId(placeIds, principal.participantId).toSet()
        } else {
            emptySet()
        }

        return PageResponse(
            items = page.content.map { place ->
                val placeId = place.id ?: return@map toResponse(place, 0, 0, false, false)
                toResponse(
                    place,
                    (commentCounts[placeId] ?: 0L).toInt(),
                    (likeCounts[placeId] ?: 0L).toInt(),
                    likedPlaceIds.contains(placeId),
                    placeId == board.selectedPlaceId
                )
            },
            page = PageResponse.PageMetadata(
                number = pageable.pageNumber + 1,
                size = pageable.pageSize,
                totalItems = page.totalElements,
                totalPages = page.totalPages,
            ),
        )
    }

    @Transactional
    fun delete(boardId: String, placeId: String, principal: ParticipantPrincipal) {
        checks.requireBoard(principal, boardId)
        val board = boards.findByPublicId(boardId) ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        val boardId_internal = board.id ?: throw BusinessException(ErrorCode.INTERNAL_ERROR)

        val place = places.findByPublicIdAndBoardId(placeId, boardId_internal)
            ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)

        // Check authorization: any active participant can delete
        val currentParticipant = participants.findByIdAndBoardId(principal.participantId, boardId_internal)
            ?: throw BusinessException(ErrorCode.FORBIDDEN)

        // 이미 삭제된 장소를 같은 권한으로 다시 삭제해도 204(멱등). 참조 검사·재삭제 없이 그대로 종료한다.
        if (place.deletedAt != null) return

        // Check if place is in use
        for (checker in usageCheckers) {
            val usage = checker.findUsage(place.id!!)
            if (usage != null) {
                throw BusinessException(ErrorCode.PLACE_IN_USE, usage.details)
            }
        }

        // If this place is currently selected, clear the selection (ERD v1.0 section 5.2)
        val boardForUpdate = boards.findByPublicIdForUpdate(boardId)
            ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        val placeId_internal = place.id ?: throw BusinessException(ErrorCode.INTERNAL_ERROR)

        if (boardForUpdate.selectedPlaceId == placeId_internal) {
            boardForUpdate.clearSelection(principal.participantId, java.time.Instant.now())
            boards.save(boardForUpdate)
        }

        place.softDelete()
        places.save(place)
    }

    private fun toResponse(place: Place, commentCount: Int, likeCount: Int = 0, likedByMe: Boolean = false, selected: Boolean = false): PlaceResponse {
        return PlaceResponse(
            placeId = place.publicId,
            name = place.name,
            lon = place.lon,
            lat = place.lat,
            addressName = place.addressName,
            roadAddressName = place.roadAddressName,
            internalCategory = place.internalCategory,
            provider = place.provider,
            providerPlaceId = place.providerPlaceId,
            providerPlaceUrl = place.providerPlaceUrl,
            source = place.source,
            proposerId = place.proposer.publicId,
            commentCount = commentCount,
            createdAt = place.createdAt,
            likeCount = likeCount,
            likedByMe = likedByMe,
            selected = selected,
        )
    }

    private fun validateQuery(query: String) {
        if (query.length < 2 || query.length > 80) {
            throw BusinessException(ErrorCode.INVALID_ARGUMENT)
        }
        // Check if query looks like a URL
        if (query.startsWith("http://") || query.startsWith("https://")) {
            throw BusinessException(ErrorCode.URL_QUERY_NOT_ALLOWED)
        }
    }

    /** 명세 6.1: lon·lat은 함께 전달해야 하고, radius는 중심 좌표가 있을 때만 최대 20,000m다. */
    private fun validateSearchLocation(lon: Double?, lat: Double?, radius: Int?) {
        if ((lon == null) != (lat == null)) throw BusinessException(ErrorCode.INVALID_ARGUMENT)
        if (radius != null) {
            if (lon == null || lat == null) throw BusinessException(ErrorCode.INVALID_ARGUMENT)
            if (radius <= 0 || radius > 20_000) throw BusinessException(ErrorCode.INVALID_ARGUMENT)
        }
    }

    @Transactional
    fun putLike(boardId: String, placeId: String, principal: ParticipantPrincipal) {
        requireBoardParticipant(boardId, principal)
        val board = boards.findByPublicId(boardId) ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        val boardId_internal = board.id ?: throw BusinessException(ErrorCode.INTERNAL_ERROR)

        val place = places.findByPublicIdAndBoardIdAndDeletedAtIsNull(placeId, boardId_internal)
            ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        val placeId_internal = place.id ?: throw BusinessException(ErrorCode.INTERNAL_ERROR)

        // 멱등성: 이미 좋아요되어 있으면 무시하고 성공 반환
        if (!likes.existsByPlaceIdAndParticipantId(placeId_internal, principal.participantId)) {
            val like = PlaceLike(PlaceLikeId(placeId_internal, principal.participantId))
            likes.save(like)
        }
    }

    @Transactional
    fun deleteLike(boardId: String, placeId: String, principal: ParticipantPrincipal) {
        requireBoardParticipant(boardId, principal)
        val board = boards.findByPublicId(boardId) ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        val boardId_internal = board.id ?: throw BusinessException(ErrorCode.INTERNAL_ERROR)

        val place = places.findByPublicIdAndBoardIdAndDeletedAtIsNull(placeId, boardId_internal)
            ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        val placeId_internal = place.id ?: throw BusinessException(ErrorCode.INTERNAL_ERROR)

        // 멱등성: 없어도 성공 반환
        likes.deleteByPlaceIdAndParticipantId(placeId_internal, principal.participantId)
    }

    private fun requireBoardParticipant(boardId: String, principal: ParticipantPrincipal) {
        if (principal.boardId != boardId) {
            throw BusinessException(ErrorCode.FORBIDDEN)
        }
    }

    companion object {
        private val VALID_CATEGORIES = setOf("RESTAURANT", "CAFE", "PLAY", "BAR", "CULTURE", "ATTRACTION", "TRANSIT", "ETC")
    }

    private fun validateProviderUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val allowedHosts = setOf("place.map.kakao.com")
        val host = try {
            URL(url).host
        } catch (e: Exception) {
            throw BusinessException(ErrorCode.INVALID_ARGUMENT)
        }
        if (!allowedHosts.contains(host)) throw BusinessException(ErrorCode.INVALID_ARGUMENT)
        return url
    }
}
