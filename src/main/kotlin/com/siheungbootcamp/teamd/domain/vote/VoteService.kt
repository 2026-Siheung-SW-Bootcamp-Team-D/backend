package com.siheungbootcamp.teamd.domain.vote

import com.siheungbootcamp.teamd.global.auth.AuthorizationChecks
import com.siheungbootcamp.teamd.global.auth.ParticipantPrincipal
import com.siheungbootcamp.teamd.global.error.BusinessException
import com.siheungbootcamp.teamd.global.error.ErrorCode
import com.siheungbootcamp.teamd.global.web.PageResponse
import com.siheungbootcamp.teamd.domain.board.BoardRepository
import com.siheungbootcamp.teamd.domain.board.ParticipantRepository
import com.siheungbootcamp.teamd.domain.place.PlaceRepository
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 투표의 생성·조회·투표·종료의 비즈니스 로직을 담당한다.
 *
 * 보드당 열린 투표는 최대 1개다. 후보는 2~10개이고, maxSelections는 1~후보수여야 한다.
 * 투표 항목(ballot) 교체는 한 트랜잭션 안에서 delete → flush → insert로 수행한다.
 * 익명 투표의 경우 응답 DTO에서 참여자 신원을 절대 포함하지 않는다.
 */
@Service
@Transactional(readOnly = true)
class VoteService(
    private val votes: VoteRepository,
    private val options: VoteOptionRepository,
    private val ballots: VoteBallotRepository,
    private val places: PlaceRepository,
    private val boards: BoardRepository,
    private val participants: ParticipantRepository,
    private val checks: AuthorizationChecks,
) {
    @Transactional
    fun create(boardId: String, principal: ParticipantPrincipal, request: CreateVoteRequest): Any {
        checks.requireBoard(principal, boardId)
        checks.requireHost(principal)

        val board = boards.findByPublicId(boardId) ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        val boardId_internal = board.id ?: throw BusinessException(ErrorCode.INTERNAL_ERROR)

        // Validate candidate count
        if (request.placeIds.size < 2 || request.placeIds.size > 10) {
            throw BusinessException(ErrorCode.INVALID_ARGUMENT)
        }

        // Validate maxSelections
        if (request.maxSelections < 1 || request.maxSelections > request.placeIds.size) {
            throw BusinessException(ErrorCode.INVALID_ARGUMENT)
        }

        // Validate closesAt is in the future
        if (!request.closesAt.isAfter(Instant.now())) {
            throw BusinessException(ErrorCode.INVALID_ARGUMENT)
        }

        // Resolve place IDs and verify they exist, are ACTIVE, and belong to this board
        val resolvedPlaces = mutableListOf<Long>()
        for (placePublicId in request.placeIds) {
            val place = places.findByPublicIdAndBoardIdAndDeletedAtIsNull(placePublicId, boardId_internal)
                ?: throw BusinessException(ErrorCode.INVALID_ARGUMENT)
            resolvedPlaces.add(place.id ?: throw BusinessException(ErrorCode.INTERNAL_ERROR))
        }

        // Try to create vote - if a unique constraint violation occurs on open vote, convert to 409
        val vote = Vote(
            board = board,
            maxSelections = request.maxSelections,
            anonymous = request.anonymous,
            closesAt = request.closesAt,
        )

        try {
            val savedVote = votes.save(vote)
            votes.flush()

            // Add options
            for (placeId in resolvedPlaces) {
                val place = places.findById(placeId)
                    .orElseThrow { BusinessException(ErrorCode.INTERNAL_ERROR) }
                val option = VoteOption(vote = savedVote, place = place)
                options.save(option)
            }

            return mapVoteToListResponse(savedVote, request.placeIds.size)
        } catch (e: Exception) {
            // If it's a constraint violation about open vote, convert to 409
            if (e.cause?.message?.contains("vote (board_id)") == true ||
                e.message?.contains("unique") == true) {
                throw BusinessException(ErrorCode.RESOURCE_CONFLICT)
            }
            throw e
        }
    }

    fun list(boardId: String, principal: ParticipantPrincipal, status: String?, pageable: Pageable): PageResponse<VoteListItemResponse> {
        checks.requireBoard(principal, boardId)
        val board = boards.findByPublicId(boardId) ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        val boardId_internal = board.id ?: throw BusinessException(ErrorCode.INTERNAL_ERROR)

        val voteStatus = if (status != null) {
            try {
                VoteStatus.valueOf(status)
            } catch (e: IllegalArgumentException) {
                throw BusinessException(ErrorCode.INVALID_ARGUMENT)
            }
        } else null

        val page = votes.findByBoardIdAndStatus(boardId_internal, voteStatus, pageable)
        return PageResponse(
            items = page.content.map { vote ->
                val optionCount = options.findByVoteId(vote.id ?: throw BusinessException(ErrorCode.INTERNAL_ERROR)).size
                VoteListItemResponse(
                    voteId = vote.publicId,
                    status = vote.status.name,
                    maxSelections = vote.maxSelections,
                    anonymous = vote.anonymous,
                    closesAt = vote.closesAt,
                    createdAt = vote.createdAt,
                    optionCount = optionCount,
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

    fun getDetail(boardId: String, voteId: String, principal: ParticipantPrincipal): Any {
        checks.requireBoard(principal, boardId)
        val board = boards.findByPublicId(boardId) ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        val boardId_internal = board.id ?: throw BusinessException(ErrorCode.INTERNAL_ERROR)

        val vote = votes.findByPublicId(voteId) ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)

        // Verify vote belongs to this board
        if (vote.board.id != boardId_internal) {
            throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        }

        val voteId_internal = vote.id ?: throw BusinessException(ErrorCode.INTERNAL_ERROR)
        val voteOptions = options.findByVoteId(voteId_internal)
        val allBallots = ballots.findByVoteId(voteId_internal)
        val countByOption = ballots.countByVoteId(voteId_internal).associate { (optionId, count) ->
            optionId as Long to count as Long
        }

        return if (vote.anonymous) {
            // Anonymous: no participantId in response
            AnonymousVoteDetailResponse(
                voteId = vote.publicId,
                boardId = vote.board.publicId,
                status = vote.status.name,
                maxSelections = vote.maxSelections,
                anonymous = vote.anonymous,
                closesAt = vote.closesAt,
                createdAt = vote.createdAt,
                options = voteOptions.map { option ->
                    AnonymousVoteDetailResponse.AnonymousVoteOptionDetail(
                        optionId = option.id?.toString() ?: throw BusinessException(ErrorCode.INTERNAL_ERROR),
                        placeId = option.place.publicId,
                        placeName = option.place.name,
                        count = (countByOption[option.id] ?: 0L).toInt(),
                    )
                },
            )
        } else {
            // Named: include participantId
            VoteDetailResponse(
                voteId = vote.publicId,
                boardId = vote.board.publicId,
                status = vote.status.name,
                maxSelections = vote.maxSelections,
                anonymous = vote.anonymous,
                closesAt = vote.closesAt,
                createdAt = vote.createdAt,
                options = voteOptions.map { option ->
                    val ballotList = allBallots.filter { it.option.id == option.id }
                    VoteDetailResponse.VoteOptionDetail(
                        optionId = option.id?.toString() ?: throw BusinessException(ErrorCode.INTERNAL_ERROR),
                        placeId = option.place.publicId,
                        placeName = option.place.name,
                        ballots = ballotList.map { ballot ->
                            VoteDetailResponse.VoteOptionDetail.VoteBallotDetail(
                                participantId = ballot.participant.publicId,
                            )
                        },
                        count = (countByOption[option.id] ?: 0L).toInt(),
                    )
                },
            )
        }
    }

    @Transactional
    fun updateMyBallot(boardId: String, voteId: String, principal: ParticipantPrincipal, request: UpdateBallotRequest) {
        checks.requireBoard(principal, boardId)
        val board = boards.findByPublicId(boardId) ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        val boardId_internal = board.id ?: throw BusinessException(ErrorCode.INTERNAL_ERROR)

        val vote = votes.findByPublicId(voteId) ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        val voteId_internal = vote.id ?: throw BusinessException(ErrorCode.INTERNAL_ERROR)

        // Verify vote belongs to this board
        if (vote.board.id != boardId_internal) {
            throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        }

        // Check vote is not closed
        if (vote.isClosed()) {
            throw BusinessException(ErrorCode.RESOURCE_CONFLICT)
        }

        // Check vote is not expired
        if (vote.isExpired()) {
            throw BusinessException(ErrorCode.RESOURCE_CONFLICT)
        }

        // Verify placeIds count <= maxSelections
        if (request.placeIds.size > vote.maxSelections) {
            throw BusinessException(ErrorCode.INVALID_ARGUMENT)
        }

        val participant = participants.findByIdAndBoardId(principal.participantId, boardId_internal)
            ?: throw BusinessException(ErrorCode.FORBIDDEN)
        val participantId_internal = participant.id ?: throw BusinessException(ErrorCode.INTERNAL_ERROR)

        // Resolve place IDs and verify they exist
        val optionMap = mutableMapOf<Long, VoteOption>()
        val voteOptions = options.findByVoteId(voteId_internal)
        val voteOptionPlaceIds = voteOptions.associateBy { it.place.id ?: throw BusinessException(ErrorCode.INTERNAL_ERROR) }

        for (placePublicId in request.placeIds) {
            val place = places.findByPublicIdAndBoardIdAndDeletedAtIsNull(placePublicId, boardId_internal)
                ?: throw BusinessException(ErrorCode.INVALID_ARGUMENT)
            val placeId_internal = place.id ?: throw BusinessException(ErrorCode.INTERNAL_ERROR)

            // Verify this place is an option in this vote
            val option = voteOptionPlaceIds[placeId_internal]
                ?: throw BusinessException(ErrorCode.INVALID_ARGUMENT)

            optionMap[placeId_internal] = option
        }

        // Delete all existing ballots for this participant in this vote
        val existingBallots = ballots.findByVoteIdAndParticipantId(voteId_internal, participantId_internal)
        for (ballot in existingBallots) {
            ballots.delete(ballot)
        }
        ballots.flush()

        // Insert new ballots
        for (option in optionMap.values) {
            val newBallot = VoteBallot(
                vote = vote,
                participant = participant,
                option = option,
            )
            ballots.save(newBallot)
        }
    }

    @Transactional
    fun close(boardId: String, voteId: String, principal: ParticipantPrincipal, request: CloseVoteRequest) {
        checks.requireBoard(principal, boardId)
        checks.requireHost(principal)

        val board = boards.findByPublicId(boardId) ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        val boardId_internal = board.id ?: throw BusinessException(ErrorCode.INTERNAL_ERROR)

        val vote = votes.findByPublicId(voteId) ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)

        // Verify vote belongs to this board
        if (vote.board.id != boardId_internal) {
            throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        }

        // If already closed and requesting closed again, treat as idempotent 200
        if (vote.isClosed()) {
            return  // Already closed, idempotent
        }

        vote.close()
        votes.save(vote)
    }

    private fun mapVoteToListResponse(vote: Vote, optionCount: Int): VoteListItemResponse {
        return VoteListItemResponse(
            voteId = vote.publicId,
            status = vote.status.name,
            maxSelections = vote.maxSelections,
            anonymous = vote.anonymous,
            closesAt = vote.closesAt,
            createdAt = vote.createdAt,
            optionCount = optionCount,
        )
    }
}
