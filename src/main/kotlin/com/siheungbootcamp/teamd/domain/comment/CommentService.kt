package com.siheungbootcamp.teamd.domain.comment

import com.siheungbootcamp.teamd.global.auth.AuthorizationChecks
import com.siheungbootcamp.teamd.global.auth.ParticipantPrincipal
import com.siheungbootcamp.teamd.global.error.BusinessException
import com.siheungbootcamp.teamd.global.error.ErrorCode
import com.siheungbootcamp.teamd.global.ratelimit.RateLimit
import com.siheungbootcamp.teamd.global.ratelimit.RateLimitKey
import com.siheungbootcamp.teamd.global.ratelimit.RateLimitScope
import com.siheungbootcamp.teamd.global.web.PageResponse
import com.siheungbootcamp.teamd.domain.board.BoardRepository
import com.siheungbootcamp.teamd.domain.board.ParticipantRepository
import com.siheungbootcamp.teamd.domain.place.PlaceRepository
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 댓글의 생성·조회·수정·삭제의 비즈니스 로직을 담당한다.
 *
 * 댓글은 soft delete를 지원하며, 목록 조회는 삭제된 댓글을 제외한다.
 * 작성자는 수정·삭제 가능하고, 호스트는 삭제만 가능하다.
 * 생성 시 참여자별 20회/분 rate limit을 적용한다.
 */
@Service
@Transactional(readOnly = true)
class CommentService(
    private val comments: CommentRepository,
    private val places: PlaceRepository,
    private val boards: BoardRepository,
    private val participants: ParticipantRepository,
    private val checks: AuthorizationChecks,
) {
    fun list(boardId: String, placeId: String, principal: ParticipantPrincipal, pageable: Pageable): PageResponse<CommentResponse> {
        checks.requireBoard(principal, boardId)
        val board = boards.findByPublicId(boardId) ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        val boardId_internal = board.id ?: throw BusinessException(ErrorCode.INTERNAL_ERROR)

        val place = places.findByPublicIdAndBoardIdAndDeletedAtIsNull(placeId, boardId_internal)
            ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        val placeId_internal = place.id ?: throw BusinessException(ErrorCode.INTERNAL_ERROR)

        val page = comments.findByPlaceIdAndNotDeleted(placeId_internal, pageable)
        return PageResponse(
            items = page.content.map { toResponse(it) },
            page = PageResponse.PageMetadata(
                number = pageable.pageNumber + 1,
                size = pageable.pageSize,
                totalItems = page.totalElements,
                totalPages = page.totalPages,
            ),
        )
    }

    @Transactional
    @RateLimit(permits = 20, windowSeconds = 60, key = RateLimitKey.PARTICIPANT, scope = RateLimitScope.ENDPOINT)
    fun create(boardId: String, placeId: String, principal: ParticipantPrincipal, request: CreateCommentRequest): CommentResponse {
        checks.requireBoard(principal, boardId)
        val board = boards.findByPublicId(boardId) ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        val boardId_internal = board.id ?: throw BusinessException(ErrorCode.INTERNAL_ERROR)

        val place = places.findByPublicIdAndBoardIdAndDeletedAtIsNull(placeId, boardId_internal)
            ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        val placeId_internal = place.id ?: throw BusinessException(ErrorCode.INTERNAL_ERROR)

        val author = participants.findByIdAndBoardId(principal.participantId, boardId_internal)
            ?: throw BusinessException(ErrorCode.FORBIDDEN)

        val comment = PlaceComment(
            place = place,
            author = author,
            body = request.body,
        )

        val saved = comments.save(comment)
        return toResponse(saved)
    }

    @Transactional
    fun update(boardId: String, placeId: String, commentId: String, principal: ParticipantPrincipal, request: UpdateCommentRequest) {
        checks.requireBoard(principal, boardId)
        val board = boards.findByPublicId(boardId) ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        val boardId_internal = board.id ?: throw BusinessException(ErrorCode.INTERNAL_ERROR)

        val place = places.findByPublicIdAndBoardIdAndDeletedAtIsNull(placeId, boardId_internal)
            ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)

        val comment = comments.findByPublicIdAndDeletedAtIsNull(commentId)
            ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)

        // 댓글이 이 장소에 속하는지 확인
        if (comment.place.id != place.id) {
            throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        }

        // 작성자만 수정 가능
        if (comment.author.id != principal.participantId) {
            throw BusinessException(ErrorCode.FORBIDDEN)
        }

        comment.updateBody(request.body)
        comments.save(comment)
    }

    @Transactional
    fun delete(boardId: String, placeId: String, commentId: String, principal: ParticipantPrincipal) {
        checks.requireBoard(principal, boardId)
        val board = boards.findByPublicId(boardId) ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        val boardId_internal = board.id ?: throw BusinessException(ErrorCode.INTERNAL_ERROR)

        val place = places.findByPublicIdAndBoardIdAndDeletedAtIsNull(placeId, boardId_internal)
            ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)

        // Find comment regardless of deletion status for idempotent delete
        val comment = comments.findByPublicId(commentId)
            ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)

        // 댓글이 이 장소에 속하는지 확인
        if (comment.place.id != place.id) {
            throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        }

        val currentParticipant = participants.findByIdAndBoardId(principal.participantId, boardId_internal)
            ?: throw BusinessException(ErrorCode.FORBIDDEN)

        // 작성자 또는 호스트만 삭제 가능
        val isAuthor = comment.author.id == principal.participantId
        val isHost = currentParticipant.role.name == "HOST"

        if (!isAuthor && !isHost) {
            throw BusinessException(ErrorCode.FORBIDDEN)
        }

        // 이미 삭제된 경우 멱등성 보장 (204)
        if (comment.deletedAt != null) return

        comment.softDelete()
        comments.save(comment)
    }

    private fun toResponse(comment: PlaceComment): CommentResponse {
        return CommentResponse(
            commentId = comment.publicId,
            placeId = comment.place.publicId,
            authorId = comment.author.publicId,
            body = comment.body,
            createdAt = comment.createdAt,
        )
    }
}
