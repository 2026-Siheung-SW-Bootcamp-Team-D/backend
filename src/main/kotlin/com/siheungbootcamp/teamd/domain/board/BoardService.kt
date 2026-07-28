package com.siheungbootcamp.teamd.domain.board

import com.siheungbootcamp.teamd.global.auth.*
import com.siheungbootcamp.teamd.global.crypto.OriginCipher
import com.siheungbootcamp.teamd.global.error.BusinessException
import com.siheungbootcamp.teamd.global.error.ErrorCode
import com.siheungbootcamp.teamd.global.sse.BoardEventPublisher
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

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
    private val events: BoardEventPublisher,
) {
    private val log = LoggerFactory.getLogger(BoardService::class.java)

    /**
     * 커밋 이후에만 SSE 신호를 보낸다. 커밋 전에 보내면 프론트가 `reload()`로 REST를
     * 다시 조회했을 때 아직 반영되지 않은 옛 데이터를 읽게 된다.
     */
    private fun notifyAfterCommit(boardId: String, resource: String) {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    try {
                        events.publish(boardId, resource)
                    } catch (e: Exception) {
                        log.warn("SSE 발행 실패: boardId={}, resource={}", boardId, resource, e)
                    }
                }
            },
        )
    }
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Seoul"))
    private val colors = listOf("#4A90E2", "#50B87A", "#F5A623", "#9B51E0")

    @Transactional
    fun create(request: CreateBoardRequest, frontendBaseUrl: String): CreateBoardResponse {
        val boardName = normalized(request.name, 2, 40)
        val creatorNickname = normalized(request.creatorNickname, 1, 20)
        val board = boards.save(Board(name = boardName, purpose = request.purpose, inviteCode = uniqueInviteCode(), inviteExpiresAt = Instant.now(clock).plus(30, ChronoUnit.DAYS)))
        val publicId = com.siheungbootcamp.teamd.global.id.PublicId.generate(com.siheungbootcamp.teamd.global.id.IdPrefix.PARTICIPANT)
        val token = ParticipantToken.generate(publicId)
        val host = participants.save(Participant(publicId, board, creatorNickname, ParticipantRole.HOST, tokenHasher.hash(token.secret), colors[0]))
        return CreateBoardResponse(summary(board), CreatedParticipant(host.publicId, host.nickname, host.role.name, host.avatarColor), invitation(board, frontendBaseUrl), token.value)
    }

    fun get(boardId: String, principal: ParticipantPrincipal): BoardResponse {
        checks.requireBoard(principal, boardId)
        val board = findBoard(boardId)
        return toResponseWithSelection(board)
    }

    @Transactional
    fun patch(boardId: String, principal: ParticipantPrincipal, request: PatchBoardRequest): BoardResponse {
        checks.requireBoard(principal, boardId)
        val board = findBoardForUpdate(boardId)
        if (board.status == BoardStatus.CLOSED) conflict()
        board.update(request.name?.let { normalized(it, 2, 40) }, request.purpose)
        boards.flush()
        notifyAfterCommit(board.publicId, "board")
        return BoardResponse(board.publicId, board.name, board.purpose, board.status, counts = counts(board), updatedAt = board.updatedAt)
    }

    fun invitation(boardId: String, principal: ParticipantPrincipal, baseUrl: String): InvitationResponse {
        checks.requireBoard(principal, boardId)
        return invitation(findBoard(boardId), baseUrl)
    }

    fun preview(code: String): InvitePreviewResponse {
        val board = validInvitation(code)
        return InvitePreviewResponse(board.publicId, board.name, participants.countByBoardIdAndActiveTrue(requireNotNull(board.id)), board.status != BoardStatus.CLOSED, board.inviteExpiresAt)
    }

    @Transactional
    fun join(code: String, request: JoinRequest): JoinResponse {
        val board = validInvitationForUpdate(code)
        if (board.status == BoardStatus.CLOSED) conflict()
        val boardId = requireNotNull(board.id)
        if (participants.countByBoardIdAndActiveTrue(boardId) >= 10) {
            throw BusinessException(ErrorCode.PARTICIPANT_LIMIT_REACHED)
        }
        val publicId = com.siheungbootcamp.teamd.global.id.PublicId.generate(com.siheungbootcamp.teamd.global.id.IdPrefix.PARTICIPANT)
        val token = ParticipantToken.generate(publicId)
        val count = participants.countByBoardId(boardId).toInt()
        val participant = participants.save(Participant(publicId, board, normalized(request.nickname, 1, 20), ParticipantRole.MEMBER, tokenHasher.hash(token.secret), colors[count % colors.size]))
        notifyAfterCommit(board.publicId, "participants")
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
        val board = findBoardForUpdate(boardId)
        if (board.status == BoardStatus.CLOSED) conflict()
        request.nickname?.let { participant.rename(normalized(it, 1, 20)) }
        if (request.originProvided) {
            if (jobChecker.exists(participant.publicId)) conflict()
            request.origin?.let { origin ->
                participant.changeOrigin(origin.label, encryptOrigin(origin.lon, origin.lat), origin.source, origin.providerPlaceId)
            } ?: participant.clearOrigin()
            staleNotifier.markStale(requireNotNull(participant.id))
        }
        val point = participant.originCiphertext?.let(::decryptOrigin)
        notifyAfterCommit(board.publicId, "participants")
        return ParticipantResponse(participant.publicId, participant.nickname, participant.role.name, participant.avatarColor,
            OriginResponse(point != null, participant.originLabel, point?.first, point?.second))
    }

    @Transactional
    fun removeParticipant(boardId: String, participantId: String, principal: ParticipantPrincipal) {
        checks.requireBoard(principal, boardId)
        checks.requireHost(principal)
        val board = findBoardForUpdate(boardId)
        if (board.status == BoardStatus.CLOSED) conflict()
        val target = participants.findActiveByPublicIdAndBoardIdForUpdate(participantId, requireNotNull(board.id))
            ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        if (target.id == principal.participantId || target.role == ParticipantRole.HOST) {
            throw BusinessException(ErrorCode.INVALID_ARGUMENT)
        }
        target.deactivate()
        notifyAfterCommit(board.publicId, "participants")
    }

    @Transactional
    fun selectPlace(boardId: String, placeId: String, principal: ParticipantPrincipal): BoardResponse {
        checks.requireBoard(principal, boardId)
        val board = findBoardForUpdate(boardId)
        if (board.status == BoardStatus.CLOSED) conflict()
        val boardId_internal = requireNotNull(board.id)

        // placeId를 publicId로 해석해 placeId_internal을 구한다. ACTIVE 장소만 선택 가능
        val placeIdInternal = try {
            jdbc.sql("select id from place where public_id = :publicId and board_id = :boardId and deleted_at is null and status = 'ACTIVE'")
                .param("publicId", placeId)
                .param("boardId", boardId_internal)
                .query(Long::class.java)
                .single()
        } catch (e: Exception) {
            throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        }

        board.select(placeIdInternal, principal.participantId, Instant.now(clock))
        boards.flush()
        notifyAfterCommit(board.publicId, "board")
        return toResponseWithSelection(board)
    }

    @Transactional
    fun clearSelection(boardId: String, principal: ParticipantPrincipal) {
        checks.requireBoard(principal, boardId)
        val board = findBoardForUpdate(boardId)
        if (board.status == BoardStatus.CLOSED) conflict()
        board.clearSelection(principal.participantId, Instant.now(clock))
        boards.flush()
        notifyAfterCommit(board.publicId, "board")
    }

    private fun toResponseWithSelection(b: Board): BoardResponse {
        val selectedPlacePublicId = b.selectedPlaceId?.let { id ->
            jdbc.sql("select public_id from place where id = :id").param("id", id).query(String::class.java).single()
        }
        val selectedByParticipantPublicId = b.selectedByParticipantId?.let { id ->
            jdbc.sql("select public_id from participant where id = :id").param("id", id).query(String::class.java).single()
        }
        return BoardResponse(b.publicId, b.name, b.purpose, b.status, counts = counts(b), updatedAt = b.updatedAt,
            selectedPlaceId = selectedPlacePublicId,
            selectedByParticipantId = selectedByParticipantPublicId,
            selectedAt = b.selectedAt)
    }

    private fun uniqueInviteCode(): String = generateSequence(inviteCodes::generate).first { boards.findByInviteCode(it) == null }
    private fun validInvitation(code: String): Board = boards.findByInviteCode(code.trim().uppercase(Locale.ROOT))?.takeIf { it.inviteExpiresAt.isAfter(Instant.now(clock)) } ?: throw BusinessException(ErrorCode.INVITE_NOT_FOUND)
    private fun validInvitationForUpdate(code: String): Board = boards.findByInviteCodeForUpdate(code.trim().uppercase(Locale.ROOT))
        ?.takeIf { it.inviteExpiresAt.isAfter(Instant.now(clock)) }
        ?: throw BusinessException(ErrorCode.INVITE_NOT_FOUND)
    private fun findBoard(id: String) = boards.findByPublicId(id) ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
    private fun findBoardForUpdate(id: String) = boards.findByPublicIdForUpdate(id) ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
    private fun summary(b: Board) = BoardSummary(b.publicId, b.name, b.purpose, b.status)
    private fun invitation(b: Board, base: String) = InvitationResponse(b.inviteCode, "${base.trimEnd('/')}/#/join/${b.inviteCode}", b.inviteExpiresAt)
    private fun counts(b: Board): BoardCounts { val id = requireNotNull(b.id); return BoardCounts(participants.countByBoardIdAndActiveTrue(id), jdbc.sql("select count(*) from place where board_id=:id and deleted_at is null").param("id", id).query(Long::class.java).single(), jdbc.sql("select count(*) from place_comment c join place p on p.id=c.place_id where p.board_id=:id and c.deleted_at is null").param("id", id).query(Long::class.java).single()) }
    private fun encryptOrigin(lon: Double, lat: Double) = originCipher.encrypt(ByteBuffer.allocate(16).putDouble(lon).putDouble(lat).array())
    private fun decryptOrigin(bytes: ByteArray): Pair<Double, Double> { val b = ByteBuffer.wrap(originCipher.decrypt(bytes)); return b.double to b.double }
    private fun conflict(): Nothing = throw BusinessException(ErrorCode.RESOURCE_CONFLICT)
    private fun normalized(value: String, min: Int, max: Int): String = value.trim().takeIf { it.length in min..max }
        ?: throw BusinessException(ErrorCode.INVALID_ARGUMENT)
}
