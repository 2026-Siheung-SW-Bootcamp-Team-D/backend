package com.siheungbootcamp.teamd.domain.board

import com.siheungbootcamp.teamd.global.auth.*
import com.siheungbootcamp.teamd.global.crypto.OriginCipher
import com.siheungbootcamp.teamd.global.error.BusinessException
import com.siheungbootcamp.teamd.global.error.ErrorCode
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 보드 생성부터 참여자 출발지 변경까지 P1 유스케이스와 트랜잭션 경계를 담당한다. */
@Service
@Transactional(readOnly = true)
class BoardService(
    private val boards: BoardRepository,
    private val participants: ParticipantRepository,
    private val tokenHasher: TokenHasher,
    private val inviteCodes: InviteCodeGenerator,
    private val originCipher: OriginCipher,
    private val jobChecker: ActiveAreaSearchJobChecker,
    private val staleNotifier: DepartureStaleNotifier,
    private val checks: AuthorizationChecks,
    private val jdbc: JdbcClient,
) {
    private val clock: Clock = Clock.systemUTC()
    private val colors = listOf("#4A90E2", "#50B87A", "#F5A623", "#9B51E0")

    @Transactional
    fun create(request: CreateBoardRequest, frontendBaseUrl: String): CreateBoardResponse {
        validateDates(request.dateRange)
        val board = boards.save(Board(name = request.name, dateStart = request.dateRange.start, dateEnd = request.dateRange.end,
            purpose = request.purpose, inviteCode = uniqueInviteCode(), inviteExpiresAt = Instant.now(clock).plus(30, ChronoUnit.DAYS)))
        val publicId = com.siheungbootcamp.teamd.global.id.PublicId.generate(com.siheungbootcamp.teamd.global.id.IdPrefix.PARTICIPANT)
        val token = ParticipantToken.generate(publicId)
        val host = participants.save(Participant(publicId, board, request.hostNickname, ParticipantRole.HOST, tokenHasher.hash(token.secret), colors[0]))
        return CreateBoardResponse(summary(board), CreatedParticipant(host.publicId, host.nickname, host.role.name, token.value), invitation(board, frontendBaseUrl))
    }

    fun get(boardId: String, principal: ParticipantPrincipal): BoardResponse {
        checks.requireBoard(principal, boardId)
        val board = findBoard(boardId)
        return BoardResponse(board.publicId, board.name, range(board), board.purpose, board.status, counts = counts(board), updatedAt = board.updatedAt)
    }

    @Transactional
    fun patch(boardId: String, principal: ParticipantPrincipal, request: PatchBoardRequest): BoardResponse {
        checks.requireBoard(principal, boardId); checks.requireHost(principal)
        val board = findBoard(boardId)
        if (board.status == BoardStatus.CLOSED) conflict()
        request.dateRange?.let(::validateDates)
        if (request.status != null && request.status != BoardStatus.CLOSED) throw BusinessException(ErrorCode.INVALID_ARGUMENT)
        board.update(request.name, request.dateRange?.start, request.dateRange?.end, request.purpose)
        if (request.status == BoardStatus.CLOSED) board.close()
        return BoardResponse(board.publicId, board.name, range(board), board.purpose, board.status, counts = counts(board), updatedAt = board.updatedAt)
    }

    fun invitation(boardId: String, principal: ParticipantPrincipal, baseUrl: String): InvitationResponse {
        checks.requireBoard(principal, boardId); checks.requireHost(principal)
        return invitation(findBoard(boardId), baseUrl)
    }

    fun preview(code: String): InvitePreviewResponse {
        val board = validInvitation(code)
        return InvitePreviewResponse(board.publicId, board.name, participants.countByBoardId(requireNotNull(board.id)), board.status != BoardStatus.CLOSED, board.inviteExpiresAt)
    }

    @Transactional
    fun join(code: String, request: JoinRequest): JoinResponse {
        val board = validInvitation(code)
        if (board.status == BoardStatus.CLOSED) conflict()
        val publicId = com.siheungbootcamp.teamd.global.id.PublicId.generate(com.siheungbootcamp.teamd.global.id.IdPrefix.PARTICIPANT)
        val token = ParticipantToken.generate(publicId)
        val count = participants.countByBoardId(requireNotNull(board.id)).toInt()
        val participant = participants.save(Participant(publicId, board, request.nickname, ParticipantRole.MEMBER, tokenHasher.hash(token.secret), colors[count % colors.size]))
        return JoinResponse(board.publicId, participant.publicId, participant.nickname, participant.role.name, participant.avatarColor, token.value)
    }

    fun list(boardId: String, principal: ParticipantPrincipal): ParticipantListResponse {
        checks.requireBoard(principal, boardId); val board = findBoard(boardId)
        return ParticipantListResponse(participants.findAllByBoardIdAndActiveTrueOrderById(requireNotNull(board.id)).map { p ->
            val mine = p.id == principal.participantId
            val coordinates = if (mine) p.originCiphertext?.let(::decryptOrigin) else null
            ParticipantResponse(p.publicId, p.nickname, p.role.name, p.avatarColor,
                OriginResponse(p.originCiphertext != null, if (mine) p.originLabel else null, coordinates?.first, coordinates?.second))
        })
    }

    @Transactional
    fun patchMe(boardId: String, principal: ParticipantPrincipal, request: PatchMeRequest): ParticipantResponse {
        checks.requireBoard(principal, boardId)
        val participant = participants.findByIdForUpdate(principal.participantId) ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        if (participant.board.status == BoardStatus.CLOSED) conflict()
        request.nickname?.let(participant::rename)
        request.origin?.let { origin ->
            if (jobChecker.exists(participant.publicId)) conflict()
            participant.changeOrigin(origin.label, encryptOrigin(origin.lon, origin.lat), origin.source, origin.providerPlaceId)
            staleNotifier.markStale(requireNotNull(participant.id))
        }
        val point = participant.originCiphertext?.let(::decryptOrigin)
        return ParticipantResponse(participant.publicId, participant.nickname, participant.role.name, participant.avatarColor,
            OriginResponse(point != null, participant.originLabel, point?.first, point?.second))
    }

    private fun validateDates(value: DateRangeRequest) {
        if (value.start.isBefore(LocalDate.now(clock)) || value.end.isBefore(value.start) || ChronoUnit.DAYS.between(value.start, value.end) > 30) throw BusinessException(ErrorCode.INVALID_ARGUMENT)
    }
    private fun uniqueInviteCode(): String = generateSequence(inviteCodes::generate).first { boards.findByInviteCode(it) == null }
    private fun validInvitation(code: String): Board = boards.findByInviteCode(code)?.takeIf { it.inviteExpiresAt.isAfter(Instant.now(clock)) } ?: throw BusinessException(ErrorCode.INVITE_NOT_FOUND)
    private fun findBoard(id: String) = boards.findByPublicId(id) ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
    private fun summary(b: Board) = BoardSummary(b.publicId, b.name, b.status, dateRange = range(b))
    private fun range(b: Board) = DateRangeResponse(b.dateStart, b.dateEnd)
    private fun invitation(b: Board, base: String) = InvitationResponse(b.inviteCode, "${base.trimEnd('/')}/j/${b.inviteCode}", b.inviteExpiresAt)
    private fun counts(b: Board): BoardCounts { val id = requireNotNull(b.id); return BoardCounts(participants.countByBoardId(id), jdbc.sql("select count(*) from place where board_id=:id and deleted_at is null").param("id", id).query(Long::class.java).single(), jdbc.sql("select count(*) from place_comment c join place p on p.id=c.place_id where p.board_id=:id and c.deleted_at is null").param("id", id).query(Long::class.java).single()) }
    private fun encryptOrigin(lon: Double, lat: Double) = originCipher.encrypt(ByteBuffer.allocate(16).putDouble(lon).putDouble(lat).array())
    private fun decryptOrigin(bytes: ByteArray): Pair<Double, Double> { val b = ByteBuffer.wrap(originCipher.decrypt(bytes)); return b.double to b.double }
    private fun conflict(): Nothing = throw BusinessException(ErrorCode.RESOURCE_CONFLICT)
}
