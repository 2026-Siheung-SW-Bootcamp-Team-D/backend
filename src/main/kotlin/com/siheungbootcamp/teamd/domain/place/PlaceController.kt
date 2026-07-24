package com.siheungbootcamp.teamd.domain.place

import com.siheungbootcamp.teamd.domain.board.RequiresBoardOpen
import com.siheungbootcamp.teamd.global.auth.CurrentParticipant
import com.siheungbootcamp.teamd.global.auth.ParticipantPrincipal
import com.siheungbootcamp.teamd.global.error.BusinessException
import com.siheungbootcamp.teamd.global.error.ErrorCode
import com.siheungbootcamp.teamd.global.ratelimit.RateLimit
import com.siheungbootcamp.teamd.global.ratelimit.RateLimitKey
import com.siheungbootcamp.teamd.global.ratelimit.RateLimitScope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.net.URI

/** P2 HTTP 요청을 검증하고 애플리케이션 서비스 결과를 API 명세 형태로 반환한다. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "P2 장소·검색", description = "장소를 검색하고 등록·조회·삭제합니다.")
class PlaceController(private val service: PlaceService) {

    // 검색 엔드포인트 (9-11, P7 canonical paths)

    @GetMapping("/boards/{boardId}/search/places")
    @Operation(summary = "장소 검색", description = "Kakao Local 키워드 검색으로 최대 15개 결과를 반환합니다.")
    @SecurityRequirement(name = "participantToken")
    @ApiResponse(responseCode = "200", description = "검색 성공, 결과 없을 수 있음")
    @ApiResponse(responseCode = "400", description = "쿼리 길이·형식 오류")
    @ApiResponse(responseCode = "404", description = "다른 보드의 토큰(존재 숨김)")
    @RateLimit(permits = 20, windowSeconds = 60, key = RateLimitKey.PARTICIPANT, scope = RateLimitScope.PARTICIPANT_GLOBAL)
    fun searchPlaces(
        @PathVariable boardId: String,
        @RequestParam(required = true, name = "q") query: String,
        @RequestParam(defaultValue = "KAKAO") provider: String,
        @RequestParam(required = false) lon: Double?,
        @RequestParam(required = false) lat: Double?,
        @RequestParam(required = false) radius: Int?,
        @Parameter(hidden = true) @CurrentParticipant principal: ParticipantPrincipal,
    ) = service.searchKeyword(boardId, principal, query, lon, lat, radius, provider)

    @GetMapping("/boards/{boardId}/search/addresses")
    @Operation(summary = "주소 검색", description = "도로명 또는 지번 주소를 검색합니다.")
    @SecurityRequirement(name = "participantToken")
    @ApiResponse(responseCode = "200", description = "검색 성공, 결과 없을 수 있음")
    @ApiResponse(responseCode = "404", description = "다른 보드의 토큰(존재 숨김)")
    @RateLimit(permits = 20, windowSeconds = 60, key = RateLimitKey.PARTICIPANT, scope = RateLimitScope.PARTICIPANT_GLOBAL)
    fun searchAddresses(
        @PathVariable boardId: String,
        @RequestParam(required = true, name = "q") query: String,
        @Parameter(hidden = true) @CurrentParticipant principal: ParticipantPrincipal,
    ) = service.searchAddress(boardId, principal, query)

    @GetMapping("/boards/{boardId}/search/reverse-geocode")
    @Operation(summary = "역지오코딩", description = "경위도로부터 도로명·지번 주소를 조회합니다.")
    @SecurityRequirement(name = "participantToken")
    @ApiResponse(responseCode = "200", description = "조회 성공, 주소 없을 수 있음")
    @ApiResponse(responseCode = "404", description = "다른 보드의 토큰(존재 숨김)")
    @RateLimit(permits = 20, windowSeconds = 60, key = RateLimitKey.PARTICIPANT, scope = RateLimitScope.PARTICIPANT_GLOBAL)
    fun reverseGeocode(
        @PathVariable boardId: String,
        @RequestParam(required = true) lon: Double,
        @RequestParam(required = true) lat: Double,
        @Parameter(hidden = true) @CurrentParticipant principal: ParticipantPrincipal,
    ) = service.coord2Address(boardId, principal, lon, lat)

    @GetMapping("/boards/{boardId}/search/nearby-places")
    @Operation(summary = "주변 장소 검색", description = "자유 좌표에서 주변 장소를 검색합니다. q 또는 category 중 정확히 하나를 전달해야 합니다.")
    @SecurityRequirement(name = "participantToken")
    @ApiResponse(responseCode = "200", description = "검색 성공, 결과 없을 수 있음")
    @ApiResponse(responseCode = "400", description = "입력 검증 오류 (좌표 누락, 둘 다 누락/동시 전달 등)")
    @ApiResponse(responseCode = "404", description = "다른 보드의 토큰(존재 숨김)")
    @RateLimit(permits = 20, windowSeconds = 60, key = RateLimitKey.PARTICIPANT, scope = RateLimitScope.PARTICIPANT_GLOBAL)
    fun searchNearbyPlaces(
        @PathVariable boardId: String,
        @RequestParam(required = false) lon: Double?,
        @RequestParam(required = false) lat: Double?,
        @RequestParam(required = false, name = "q") query: String?,
        @RequestParam(required = false) category: String?,
        @RequestParam(defaultValue = "1000") radius: Int,
        @Parameter(hidden = true) @CurrentParticipant principal: ParticipantPrincipal,
    ) = service.searchNearby(boardId, principal, lon, lat, query, category, radius)

    // 장소 관리 엔드포인트 (12-15)

    @PostMapping("/boards/{boardId}/places")
    @Operation(summary = "장소 등록", description = "검색 결과 또는 지도 핀으로부터 장소를 등록합니다.")
    @SecurityRequirement(name = "participantToken")
    @ApiResponse(responseCode = "201", description = "등록 성공")
    @ApiResponse(responseCode = "400", description = "필드 검증 오류")
    @ApiResponse(responseCode = "404", description = "다른 보드의 토큰(존재 숨김)")
    @ApiResponse(responseCode = "409", description = "보드가 종료됨")
    @RequiresBoardOpen
    @RateLimit(permits = 20, windowSeconds = 60, key = RateLimitKey.PARTICIPANT, scope = RateLimitScope.PARTICIPANT_GLOBAL)
    fun createPlace(
        @PathVariable boardId: String,
        @Parameter(hidden = true) @CurrentParticipant principal: ParticipantPrincipal,
        @Valid @RequestBody request: CreatePlaceRequest,
    ): ResponseEntity<PlaceResponse> {
        val response = service.create(boardId, principal, request)
        return ResponseEntity.created(URI.create("/api/v1/boards/$boardId/places/${response.placeId}"))
            .body(response)
    }

    @GetMapping("/boards/{boardId}/places")
    @Operation(summary = "장소 목록 조회", description = "카테고리·정렬·바운딩박스로 필터링하여 조회합니다.")
    @SecurityRequirement(name = "participantToken")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "404", description = "다른 보드의 토큰(존재 숨김)")
    @RateLimit(permits = 60, windowSeconds = 60, key = RateLimitKey.PARTICIPANT, scope = RateLimitScope.PARTICIPANT_GLOBAL)
    fun listPlaces(
        @PathVariable boardId: String,
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false, defaultValue = "RECENT") sort: String,
        @RequestParam(required = false) bbox: String?,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @Parameter(hidden = true) @CurrentParticipant principal: ParticipantPrincipal,
    ): PlaceListResponse {
        val pageable = PageRequest.of(maxOf(0, page - 1), size.coerceIn(1, 50))
        val box = parseBbox(bbox)
        val response = service.list(boardId, principal, category, sort, box?.minLon, box?.minLat, box?.maxLon, box?.maxLat, pageable)
        return PlaceListResponse(
            items = response.items,
            page = PlaceListResponse.PageMetadata(
                number = response.page.number,
                size = response.page.size,
                totalItems = response.page.totalItems,
                totalPages = response.page.totalPages,
            ),
        )
    }

    @GetMapping("/boards/{boardId}/places/{placeId}")
    @Operation(summary = "장소 상세 조회", description = "특정 장소의 정보를 조회합니다.")
    @SecurityRequirement(name = "participantToken")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "404", description = "다른 보드의 토큰(존재 숨김)")
    @ApiResponse(responseCode = "404", description = "장소가 없음")
    @RateLimit(permits = 60, windowSeconds = 60, key = RateLimitKey.PARTICIPANT, scope = RateLimitScope.PARTICIPANT_GLOBAL)
    fun getPlace(
        @PathVariable boardId: String,
        @PathVariable placeId: String,
        @Parameter(hidden = true) @CurrentParticipant principal: ParticipantPrincipal,
    ) = service.get(boardId, placeId, principal)

    @DeleteMapping("/boards/{boardId}/places/{placeId}")
    @Operation(summary = "장소 보관", description = "같은 보드의 활성 참여자는 누구나 후보를 보관할 수 있습니다. 이미 보관된 장소도 204를 반환합니다.")
    @SecurityRequirement(name = "participantToken")
    @ApiResponse(responseCode = "204", description = "삭제 성공 또는 이미 삭제됨")
    @ApiResponse(responseCode = "403", description = "활성 참여자가 아님")
    @ApiResponse(responseCode = "404", description = "장소 또는 다른 보드의 토큰(존재 숨김)")
    @ApiResponse(responseCode = "409", description = "투표·코스에서 사용 중")
    @RequiresBoardOpen
    @RateLimit(permits = 20, windowSeconds = 60, key = RateLimitKey.PARTICIPANT, scope = RateLimitScope.PARTICIPANT_GLOBAL)
    fun deletePlace(
        @PathVariable boardId: String,
        @PathVariable placeId: String,
        @Parameter(hidden = true) @CurrentParticipant principal: ParticipantPrincipal,
    ): ResponseEntity<Void> {
        service.delete(boardId, placeId, principal)
        return ResponseEntity.noContent().build()
    }

    // P6 좋아요 엔드포인트

    @PutMapping("/boards/{boardId}/places/{placeId}/likes/me")
    @Operation(summary = "장소 좋아요", description = "현재 참여자가 장소에 좋아요를 표현합니다. 이미 좋아요했으면 멱등성을 유지합니다.")
    @SecurityRequirement(name = "participantToken")
    @ApiResponse(responseCode = "204", description = "좋아요 성공 또는 이미 좋아요함")
    @ApiResponse(responseCode = "403", description = "다른 보드의 토큰")
    @ApiResponse(responseCode = "404", description = "장소 또는 보드 없음")
    @RateLimit(permits = 60, windowSeconds = 60, key = RateLimitKey.PARTICIPANT, scope = RateLimitScope.PARTICIPANT_GLOBAL)
    @RequiresBoardOpen
    fun putLike(
        @PathVariable boardId: String,
        @PathVariable placeId: String,
        @Parameter(hidden = true) @CurrentParticipant principal: ParticipantPrincipal,
    ): ResponseEntity<Void> {
        service.putLike(boardId, placeId, principal)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/boards/{boardId}/places/{placeId}/likes/me")
    @Operation(summary = "장소 좋아요 취소", description = "현재 참여자의 좋아요를 취소합니다. 좋아요하지 않은 상태에서도 멱등성을 유지합니다.")
    @SecurityRequirement(name = "participantToken")
    @ApiResponse(responseCode = "204", description = "좋아요 취소 성공 또는 좋아요하지 않음")
    @ApiResponse(responseCode = "403", description = "다른 보드의 토큰")
    @ApiResponse(responseCode = "404", description = "보드 또는 장소 없음")
    @RateLimit(permits = 60, windowSeconds = 60, key = RateLimitKey.PARTICIPANT, scope = RateLimitScope.PARTICIPANT_GLOBAL)
    @RequiresBoardOpen
    fun deleteLike(
        @PathVariable boardId: String,
        @PathVariable placeId: String,
        @Parameter(hidden = true) @CurrentParticipant principal: ParticipantPrincipal,
    ): ResponseEntity<Void> {
        service.deleteLike(boardId, placeId, principal)
        return ResponseEntity.noContent().build()
    }

    /** 명세 7.2: `bbox=minLon,minLat,maxLon,maxLat` 단일 쿼리 파라미터. 형식이 어긋나면 400. */
    private fun parseBbox(raw: String?): BoundingBox? {
        if (raw == null) return null
        val parts = raw.split(",").map { it.trim().toDoubleOrNull() ?: throw BusinessException(ErrorCode.INVALID_ARGUMENT) }
        if (parts.size != 4) throw BusinessException(ErrorCode.INVALID_ARGUMENT)
        val box = BoundingBox(minLon = parts[0], minLat = parts[1], maxLon = parts[2], maxLat = parts[3])
        if (box.minLon > box.maxLon || box.minLat > box.maxLat) throw BusinessException(ErrorCode.INVALID_ARGUMENT)
        return box
    }

    private data class BoundingBox(val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double)
}
