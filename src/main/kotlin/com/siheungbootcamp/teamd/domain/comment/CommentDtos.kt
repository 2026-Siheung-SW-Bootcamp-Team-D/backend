package com.siheungbootcamp.teamd.domain.comment

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

// Request DTOs

/**
 * P7 canonical 댓글 작성 요청
 * 필드명을 `body`에서 `content`로 정규화합니다.
 */
data class CreateCommentRequest(
    @field:NotBlank @field:Size(min = 1, max = 500) val content: String,
)

/**
 * P7 canonical 댓글 수정 요청
 * 필드명을 `body`에서 `content`로 정규화합니다.
 */
data class UpdateCommentRequest(
    @field:NotBlank @field:Size(min = 1, max = 500) val content: String,
)

// Response DTOs

/**
 * P7 canonical 댓글 응답
 * authorId 대신 authorParticipantId를 사용합니다.
 * body 대신 content를 사용합니다.
 */
data class CommentResponse(
    val commentId: String,
    val placeId: String,
    val authorParticipantId: String,
    val authorNickname: String,
    val content: String,
    val createdAt: Instant,
)
