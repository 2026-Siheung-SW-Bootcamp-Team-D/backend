package com.siheungbootcamp.teamd.domain.course

import com.siheungbootcamp.teamd.domain.board.BoardProperties
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
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.net.URI

/** P4 HTTP 요청을 검증하고 애플리케이션 서비스 결과를 API 명세 형태로 반환한다. */
@RestController
@RequestMapping("/api/v1")
@EnableConfigurationProperties(BoardProperties::class)
@Tag(name = "P4 코스·공개 일정", description = "코스 초안을 편집하고 확정한 뒤 공개 일정을 조회합니다.")
class CourseController(private val service: CourseService, private val properties: BoardProperties) {

    @GetMapping("/boards/{boardId}/course-draft")
    @Operation(summary = "코스 초안 조회", description = "초안이 없어도 200과 함께 빈 초안을 반환합니다. ETag는 If-Match에 그대로 사용합니다.")
    @SecurityRequirement(name = "participantToken")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "404", description = "다른 보드의 토큰(존재 숨김)")
    @RateLimit(permits = 60, windowSeconds = 60, key = RateLimitKey.PARTICIPANT, scope = RateLimitScope.PARTICIPANT_GLOBAL)
    fun getDraft(
        @PathVariable boardId: String,
        @Parameter(hidden = true) @CurrentParticipant principal: ParticipantPrincipal,
    ): ResponseEntity<CourseDraftResponse> {
        val response = service.getDraft(boardId, principal)
        return ResponseEntity.ok().header(HttpHeaders.ETAG, quoted(response.version)).body(response)
    }

    @PutMapping("/boards/{boardId}/course-draft")
    @Operation(summary = "코스 초안 전체 저장", description = "호스트가 전체 초안을 교체합니다. If-Match가 없으면 400, 버전이 다르면 412를 반환합니다.")
    @SecurityRequirement(name = "participantToken")
    @ApiResponse(responseCode = "200", description = "저장 성공")
    @ApiResponse(responseCode = "400", description = "If-Match 없음 또는 초안 검증 실패")
    @ApiResponse(responseCode = "403", description = "호스트가 아님")
    @ApiResponse(responseCode = "412", description = "If-Match 버전 불일치(최신 ETag 헤더 포함)")
    @RequiresBoardOpen
    @RateLimit(permits = 60, windowSeconds = 60, key = RateLimitKey.PARTICIPANT, scope = RateLimitScope.PARTICIPANT_GLOBAL)
    fun putDraft(
        @PathVariable boardId: String,
        @RequestHeader(value = "If-Match", required = false) ifMatch: String?,
        @Parameter(hidden = true) @CurrentParticipant principal: ParticipantPrincipal,
        @Valid @RequestBody request: PutCourseDraftRequest,
    ): ResponseEntity<CourseDraftResponse> {
        val response = service.putDraft(boardId, principal, ifMatch, request)
        return ResponseEntity.ok().header(HttpHeaders.ETAG, quoted(response.version)).body(response)
    }

    @PostMapping("/boards/{boardId}/courses")
    @Operation(summary = "코스 확정", description = "현재 초안을 새 확정 코스 버전으로 만듭니다. 기존 확정 버전은 보존됩니다.")
    @SecurityRequirement(name = "participantToken")
    @ApiResponse(responseCode = "201", description = "확정 성공")
    @ApiResponse(responseCode = "400", description = "확정할 초안이 없거나 검증 실패")
    @ApiResponse(responseCode = "403", description = "호스트가 아님")
    @ApiResponse(responseCode = "409", description = "draftVersion 불일치")
    @RequiresBoardOpen
    @RateLimit(permits = 60, windowSeconds = 60, key = RateLimitKey.PARTICIPANT, scope = RateLimitScope.PARTICIPANT_GLOBAL)
    fun confirm(
        @PathVariable boardId: String,
        @Parameter(hidden = true) @CurrentParticipant principal: ParticipantPrincipal,
        @Valid @RequestBody request: ConfirmCourseRequest,
    ): ResponseEntity<ConfirmCourseResponse> {
        val response = service.confirm(boardId, principal, request, properties.frontendBaseUrl)
        return ResponseEntity.created(URI.create("/api/v1/boards/$boardId/courses/${response.courseId}")).body(response)
    }

    @GetMapping("/boards/{boardId}/courses/current")
    @Operation(summary = "현재 확정 코스 조회", description = "보드의 최대 버전 코스를 반환합니다. 없으면 404입니다.")
    @SecurityRequirement(name = "participantToken")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "404", description = "확정 코스 없음 또는 다른 보드의 토큰")
    @RateLimit(permits = 60, windowSeconds = 60, key = RateLimitKey.PARTICIPANT, scope = RateLimitScope.PARTICIPANT_GLOBAL)
    fun getCurrent(
        @PathVariable boardId: String,
        @Parameter(hidden = true) @CurrentParticipant principal: ParticipantPrincipal,
    ) = service.getCurrent(boardId, principal)

    @GetMapping("/boards/{boardId}/courses/{courseId}")
    @Operation(summary = "특정 확정 버전 조회", description = "지정한 버전의 확정 코스를 반환합니다.")
    @SecurityRequirement(name = "participantToken")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "404", description = "코스 없음 또는 다른 보드의 토큰")
    @RateLimit(permits = 60, windowSeconds = 60, key = RateLimitKey.PARTICIPANT, scope = RateLimitScope.PARTICIPANT_GLOBAL)
    fun getVersion(
        @PathVariable boardId: String,
        @PathVariable courseId: String,
        @Parameter(hidden = true) @CurrentParticipant principal: ParticipantPrincipal,
    ) = service.getVersion(boardId, courseId, principal)

    private fun quoted(version: Int) = "\"draft-$version\""

    /**
     * 이 컨트롤러에서 발생한 [BusinessException]만 가로채 [ErrorCode.VERSION_MISMATCH]일 때
     * 최신 ETag를 응답 헤더에도 실어 보낸다. 그 외 오류 코드는 전역 처리기와 동일한 형식으로
     * 변환해, 이 컨트롤러 범위에서만 동작이 달라지고 다른 도메인 파일은 건드리지 않게 한다.
     */
    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(exception: BusinessException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)?.toString() ?: "unknown"
        val builder = ResponseEntity.status(exception.errorCode.status)
        if (exception.errorCode == ErrorCode.VERSION_MISMATCH) {
            (exception.details["currentETag"] as? String)?.let { builder.header(HttpHeaders.ETAG, "\"$it\"") }
        }
        return builder.body(ErrorResponse.from(exception.errorCode, exception.details, requestId))
    }
}
