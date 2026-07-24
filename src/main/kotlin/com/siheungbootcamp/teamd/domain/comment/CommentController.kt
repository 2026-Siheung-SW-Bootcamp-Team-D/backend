package com.siheungbootcamp.teamd.domain.comment

import com.siheungbootcamp.teamd.domain.board.RequiresBoardOpen
import com.siheungbootcamp.teamd.global.auth.CurrentParticipant
import com.siheungbootcamp.teamd.global.auth.ParticipantPrincipal
import com.siheungbootcamp.teamd.global.ratelimit.RateLimit
import com.siheungbootcamp.teamd.global.ratelimit.RateLimitKey
import com.siheungbootcamp.teamd.global.ratelimit.RateLimitScope
import com.siheungbootcamp.teamd.global.web.PageResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import jakarta.validation.Valid

/**
 * 장소 댓글의 조회·생성·수정·삭제 엔드포인트를 제공한다.
 *
 * 엔드포인트 16-19:
 * - GET /boards/{boardId}/places/{placeId}/comments (16): 페이지네이션
 * - POST /boards/{boardId}/places/{placeId}/comments (17): 생성, 참여자당 rate limit 20회/분
 * - PATCH /boards/{boardId}/places/{placeId}/comments/{commentId} (18): 수정, 작성자만
 * - DELETE /boards/{boardId}/places/{placeId}/comments/{commentId} (19): 삭제, 작성자만
 */
@RestController
@RequestMapping("/api/v1/boards/{boardId}/places/{placeId}/comments")
@Tag(name = "Comments", description = "장소 댓글")
class CommentController(
    private val service: CommentService,
) {
    @GetMapping
    @Operation(summary = "댓글 목록 조회", description = "장소의 댓글 목록을 페이지네이션으로 조회한다.")
    fun list(
        @PathVariable boardId: String,
        @PathVariable placeId: String,
        @CurrentParticipant principal: ParticipantPrincipal,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<PageResponse<CommentResponse>> {
        val pageable = PageRequest.of(maxOf(0, page - 1), size.coerceIn(1, 50))
        val result = service.list(boardId, placeId, principal, pageable)
        return ResponseEntity.ok(result)
    }

    @PostMapping
    @Operation(summary = "댓글 작성", description = "장소에 댓글을 작성한다. 참여자당 20회/분 rate limit.")
    @RateLimit(permits = 20, windowSeconds = 60, key = RateLimitKey.PARTICIPANT, scope = RateLimitScope.ENDPOINT)
    @RequiresBoardOpen
    fun create(
        @PathVariable boardId: String,
        @PathVariable placeId: String,
        @CurrentParticipant principal: ParticipantPrincipal,
        @Valid @RequestBody request: CreateCommentRequest,
    ): ResponseEntity<CommentResponse> {
        val result = service.create(boardId, placeId, principal, request)
        return ResponseEntity.status(HttpStatus.CREATED).body(result)
    }

    @PatchMapping("/{commentId}")
    @Operation(summary = "댓글 수정", description = "작성자만 댓글을 수정할 수 있다.")
    @RequiresBoardOpen
    fun update(
        @PathVariable boardId: String,
        @PathVariable placeId: String,
        @PathVariable commentId: String,
        @CurrentParticipant principal: ParticipantPrincipal,
        @Valid @RequestBody request: UpdateCommentRequest,
    ): ResponseEntity<Unit> {
        service.update(boardId, placeId, commentId, principal, request)
        return ResponseEntity.ok().build()
    }

    @DeleteMapping("/{commentId}")
    @Operation(summary = "댓글 삭제", description = "작성자만 댓글을 삭제할 수 있다.")
    @RequiresBoardOpen
    fun delete(
        @PathVariable boardId: String,
        @PathVariable placeId: String,
        @PathVariable commentId: String,
        @CurrentParticipant principal: ParticipantPrincipal,
    ): ResponseEntity<Unit> {
        service.delete(boardId, placeId, commentId, principal)
        return ResponseEntity.noContent().build()
    }
}
