package com.siheungbootcamp.teamd.domain.place

import com.siheungbootcamp.teamd.global.auth.AuthorizationChecks
import com.siheungbootcamp.teamd.global.auth.ParticipantPrincipal
import com.siheungbootcamp.teamd.global.error.BusinessException
import com.siheungbootcamp.teamd.global.error.ErrorCode
import com.siheungbootcamp.teamd.global.web.PageResponse
import com.siheungbootcamp.teamd.domain.board.BoardRepository
import com.siheungbootcamp.teamd.domain.board.ParticipantRepository
import com.siheungbootcamp.teamd.infra.external.kakao.KakaoLocalClient
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URL

/**
 * 장소 검색·등록·조회·삭제의 비즈니스 로직을 담당한다.
 *
 * 검색은 외부 API를 호출하지만 저장하지 않는다.
 * 등록·삭제는 권한과 상태를 검증한다.
 */
@Service
@Transactional(readOnly = true)
class PlaceService(
    private val places: PlaceRepository,
    private val boards: BoardRepository,
    private val participants: ParticipantRepository,
    private val kakao: KakaoLocalClient,
    private val checks: AuthorizationChecks,
    private val usageCheckers: List<PlaceUsageChecker> = emptyList(),
) {
    fun searchKeyword(boardId: String, principal: ParticipantPrincipal, query: String, lon: Double?, lat: Double?, radius: Int?): PlaceCandidateResponse {
        checks.requireBoard(principal, boardId)
        validateQuery(query)

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
        val validCategories = setOf("RESTAURANT", "CAFE", "PLAY", "BAR", "CULTURE", "ATTRACTION", "TRANSIT", "ETC")
        if (!validCategories.contains(request.internalCategory)) {
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
        return toResponse(saved, 0)
    }

    fun get(boardId: String, placeId: String, principal: ParticipantPrincipal): PlaceResponse {
        checks.requireBoard(principal, boardId)
        val board = boards.findByPublicId(boardId) ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        val boardId_internal = board.id ?: throw BusinessException(ErrorCode.INTERNAL_ERROR)

        val place = places.findByPublicIdAndBoardIdAndDeletedAtIsNull(placeId, boardId_internal)
            ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)

        return toResponse(place, 0)
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

        val sortBy = sort ?: "RECENT"
        val page = if (minLon != null && minLat != null && maxLon != null && maxLat != null) {
            places.findByBoardIdAndBbox(boardId_internal, minLon, minLat, maxLon, maxLat, pageable)
        } else if (category != null) {
            places.findByBoardIdAndCategory(boardId_internal, category, sortBy, pageable)
        } else {
            places.findActiveByBoardId(boardId_internal, pageable)
        }

        return PageResponse(
            items = page.content.map { toResponse(it, 0) },
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

        val proposer = participants.findByIdAndBoardId(place.proposer.id!!, boardId_internal)
            ?: throw BusinessException(ErrorCode.FORBIDDEN)

        // Check authorization: proposer or host
        val isProposer = principal.participantId == proposer.id
        val currentParticipant = participants.findByIdAndBoardId(principal.participantId, boardId_internal)
            ?: throw BusinessException(ErrorCode.FORBIDDEN)
        val isHost = currentParticipant.role.name == "HOST"

        if (!isProposer && !isHost) {
            throw BusinessException(ErrorCode.FORBIDDEN)
        }

        // 이미 삭제된 장소를 같은 권한으로 다시 삭제해도 204(멱등). 참조 검사·재삭제 없이 그대로 종료한다.
        if (place.deletedAt != null) return

        // Check if place is in use
        for (checker in usageCheckers) {
            val usage = checker.findUsage(place.id!!)
            if (usage != null) {
                throw BusinessException(ErrorCode.PLACE_IN_USE)
            }
        }

        place.softDelete()
        places.save(place)
    }

    private fun toResponse(place: Place, commentCount: Int): PlaceResponse {
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

    private fun validateProviderUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val allowedHosts = setOf("place.map.kakao.com")
        return try {
            val host = URL(url).host
            if (allowedHosts.contains(host)) url else null
        } catch (e: Exception) {
            throw BusinessException(ErrorCode.INVALID_ARGUMENT)
        }
    }
}
