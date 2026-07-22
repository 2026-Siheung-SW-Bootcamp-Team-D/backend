package com.siheungbootcamp.teamd.domain.vote

import jakarta.validation.constraints.Size
import java.time.Instant

// Request DTOs

data class CreateVoteRequest(
    @field:Size(min = 2, max = 10) val placeIds: List<String>,
    @field:Size(min = 1, max = 10) val maxSelections: Int,
    val anonymous: Boolean,
    val closesAt: Instant,
)

data class UpdateBallotRequest(
    @field:Size(max = 10) val placeIds: List<String>,
)

data class CloseVoteRequest(
    val status: String = "CLOSED",
)

// Response DTOs - Separate types for anonymous/named to prevent compile-time leaks

/**
 * 실명 투표 상세 응답.
 * participantId를 포함한다.
 */
data class VoteDetailResponse(
    val voteId: String,
    val boardId: String,
    val status: String,
    val maxSelections: Int,
    val anonymous: Boolean,
    val closesAt: Instant,
    val createdAt: Instant,
    val options: List<VoteOptionDetail>,
) {
    data class VoteOptionDetail(
        val optionId: String,
        val placeId: String,
        val placeName: String,
        val ballots: List<VoteBallotDetail>,
        val count: Int,
    ) {
        data class VoteBallotDetail(
            val participantId: String,
        )
    }
}

/**
 * 익명 투표 상세 응답.
 * participantId를 포함하지 않는다. 집계 수치만 반환한다.
 */
data class AnonymousVoteDetailResponse(
    val voteId: String,
    val boardId: String,
    val status: String,
    val maxSelections: Int,
    val anonymous: Boolean,
    val closesAt: Instant,
    val createdAt: Instant,
    val options: List<AnonymousVoteOptionDetail>,
) {
    data class AnonymousVoteOptionDetail(
        val optionId: String,
        val placeId: String,
        val placeName: String,
        val count: Int,
    )
}

/**
 * 투표 목록 아이템 (익명 여부와 무관한 기본 정보만)
 */
data class VoteListItemResponse(
    val voteId: String,
    val status: String,
    val maxSelections: Int,
    val anonymous: Boolean,
    val closesAt: Instant,
    val createdAt: Instant,
    val optionCount: Int,
)
