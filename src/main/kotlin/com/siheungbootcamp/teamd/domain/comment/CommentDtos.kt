package com.siheungbootcamp.teamd.domain.comment

import jakarta.validation.constraints.Size
import java.time.Instant

// Request DTOs

data class CreateCommentRequest(
    @field:Size(min = 1, max = 500) val body: String,
)

data class UpdateCommentRequest(
    @field:Size(min = 1, max = 500) val body: String,
)

// Response DTOs

data class CommentResponse(
    val commentId: String,
    val placeId: String,
    val authorId: String,
    val body: String,
    val createdAt: Instant,
)
