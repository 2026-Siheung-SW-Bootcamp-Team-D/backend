package com.siheungbootcamp.teamd.domain.vote

import com.siheungbootcamp.teamd.global.auth.CurrentParticipant
import com.siheungbootcamp.teamd.global.auth.ParticipantPrincipal
import com.siheungbootcamp.teamd.global.web.PageResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import jakarta.validation.Valid

/**
 * 장소 투표의 생성·조회·투표·종료 엔드포인트를 제공한다.
 *
 * 엔드포인트 20-24:
 * - POST /boards/{boardId}/votes (20): 생성, 호스트만, 후보 2~10개
 * - GET /boards/{boardId}/votes (21): status=OPEN|CLOSED 필터 + 페이지네이션
 * - GET /boards/{boardId}/votes/{voteId} (22): 상세 조회 + 집계
 * - PUT /boards/{boardId}/votes/{voteId}/ballots/me (23): 내 표 전체 교체
 * - PATCH /boards/{boardId}/votes/{voteId} (24): 호스트 조기 종료
 */
@RestController
@RequestMapping("/api/v1/boards/{boardId}/votes")
@Tag(name = "Votes", description = "장소 투표")
class VoteController(
    private val service: VoteService,
) {
    @PostMapping
    @Operation(summary = "투표 생성", description = "호스트가 장소 투표를 생성한다. 보드당 열린 투표는 1개만 허용.")
    fun create(
        @PathVariable boardId: String,
        @CurrentParticipant principal: ParticipantPrincipal,
        @Valid @RequestBody request: CreateVoteRequest,
    ): ResponseEntity<Any> {
        val result = service.create(boardId, principal, request)
        return ResponseEntity.status(HttpStatus.CREATED).body(result)
    }

    @GetMapping
    @Operation(summary = "투표 목록 조회", description = "status=OPEN|CLOSED 필터와 페이지네이션을 지원한다.")
    fun list(
        @PathVariable boardId: String,
        @CurrentParticipant principal: ParticipantPrincipal,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<PageResponse<VoteListItemResponse>> {
        val pageable = PageRequest.of(page - 1, size.coerceIn(1, 50))
        val result = service.list(boardId, principal, status, pageable)
        return ResponseEntity.ok(result)
    }

    @GetMapping("/{voteId}")
    @Operation(summary = "투표 상세 조회", description = "투표의 후보와 투표 결과를 조회한다. 익명 투표는 참여자 신원을 포함하지 않는다.")
    fun getDetail(
        @PathVariable boardId: String,
        @PathVariable voteId: String,
        @CurrentParticipant principal: ParticipantPrincipal,
    ): ResponseEntity<Any> {
        val result = service.getDetail(boardId, voteId, principal)
        return ResponseEntity.ok(result)
    }

    @PutMapping("/{voteId}/ballots/me")
    @Operation(summary = "내 투표 교체", description = "내 투표를 생성하거나 전체 교체한다. 빈 배열은 투표 취소를 의미한다.")
    fun updateMyBallot(
        @PathVariable boardId: String,
        @PathVariable voteId: String,
        @CurrentParticipant principal: ParticipantPrincipal,
        @Valid @RequestBody request: UpdateBallotRequest,
    ): ResponseEntity<Unit> {
        service.updateMyBallot(boardId, voteId, principal, request)
        return ResponseEntity.ok().build()
    }

    @PatchMapping("/{voteId}")
    @Operation(summary = "투표 조기 종료", description = "호스트가 투표를 조기 종료한다. 이미 종료된 투표는 200으로 반환 (멱등).")
    fun close(
        @PathVariable boardId: String,
        @PathVariable voteId: String,
        @CurrentParticipant principal: ParticipantPrincipal,
        @Valid @RequestBody request: CloseVoteRequest,
    ): ResponseEntity<Unit> {
        service.close(boardId, voteId, principal, request)
        return ResponseEntity.ok().build()
    }
}
