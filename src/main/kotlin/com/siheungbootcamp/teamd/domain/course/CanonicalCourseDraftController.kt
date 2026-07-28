package com.siheungbootcamp.teamd.domain.course

import com.siheungbootcamp.teamd.domain.board.RequiresBoardOpen
import com.siheungbootcamp.teamd.global.auth.CurrentParticipant
import com.siheungbootcamp.teamd.global.auth.ParticipantPrincipal
import com.siheungbootcamp.teamd.global.error.BusinessException
import com.siheungbootcamp.teamd.global.error.ErrorCode
import com.siheungbootcamp.teamd.global.error.ErrorResponse
import com.siheungbootcamp.teamd.global.ratelimit.RateLimit
import com.siheungbootcamp.teamd.global.ratelimit.RateLimitKey
import com.siheungbootcamp.teamd.global.ratelimit.RateLimitScope
import com.siheungbootcamp.teamd.global.web.RequestIdFilter
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@ConditionalOnProperty(prefix = "app", name = ["legacy-api-enabled"], havingValue = "false", matchIfMissing = true)
@RestController
@RequestMapping("/api/v1")
@Tag(name = "P4 코스 초안", description = "정렬된 장소 목록 형태로 코스 초안을 협업 편집합니다.")
class CanonicalCourseDraftController(private val service: CourseService) {

    @GetMapping("/boards/{boardId}/course-draft")
    @Operation(summary = "코스 초안 조회", description = "초안이 없어도 200과 함께 빈 초안을 반환합니다. ETag는 If-Match에 그대로 사용합니다.")
    @SecurityRequirement(name = "participantToken")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "404", description = "다른 보드의 토큰(존재 숨김)")
    @RateLimit(permits = 1200, windowSeconds = 60, key = RateLimitKey.PARTICIPANT, scope = RateLimitScope.PARTICIPANT_GLOBAL)
    fun getDraft(
        @PathVariable boardId: String,
        @Parameter(hidden = true) @CurrentParticipant principal: ParticipantPrincipal,
    ): ResponseEntity<CourseDraftResponse> {
        val response = service.getDraft(boardId, principal)
        return ResponseEntity.ok().header(HttpHeaders.ETAG, quoted(response.version)).body(response)
    }

    @PutMapping("/boards/{boardId}/course-draft")
    @Operation(summary = "코스 초안 전체 저장", description = "활성 참여자가 정렬된 placeIds 전체를 교체합니다. If-Match가 없으면 400, 버전이 다르면 412를 반환합니다.")
    @SecurityRequirement(name = "participantToken")
    @ApiResponse(responseCode = "200", description = "저장 성공")
    @ApiResponse(responseCode = "400", description = "If-Match 없음 또는 중복된 placeIds")
    @ApiResponse(responseCode = "404", description = "다른 보드·삭제된 장소 또는 다른 보드의 토큰")
    @ApiResponse(responseCode = "412", description = "If-Match 버전 불일치(최신 ETag 헤더 포함)")
    @RequiresBoardOpen
    @RateLimit(permits = 1200, windowSeconds = 60, key = RateLimitKey.PARTICIPANT, scope = RateLimitScope.PARTICIPANT_GLOBAL)
    fun putDraft(
        @PathVariable boardId: String,
        @RequestHeader(value = "If-Match", required = false) ifMatch: String?,
        @Parameter(hidden = true) @CurrentParticipant principal: ParticipantPrincipal,
        @Valid @RequestBody request: PutCourseDraftPlaceIdsRequest,
    ): ResponseEntity<CourseDraftResponse> {
        val response = service.putDraftPlaceIds(boardId, principal, ifMatch, request)
        return ResponseEntity.ok().header(HttpHeaders.ETAG, quoted(response.version)).body(response)
    }

    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(exception: BusinessException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)?.toString() ?: "unknown"
        val builder = ResponseEntity.status(exception.errorCode.status)
        if (exception.errorCode == ErrorCode.VERSION_MISMATCH) {
            (exception.details["currentETag"] as? String)?.let { builder.header(HttpHeaders.ETAG, "\"$it\"") }
        }
        return builder.body(ErrorResponse.from(exception.errorCode, exception.details, requestId))
    }

    private fun quoted(version: Int) = "\"draft-$version\""
}
