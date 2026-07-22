package com.siheungbootcamp.teamd.domain.board

import com.siheungbootcamp.teamd.global.auth.CurrentParticipant
import com.siheungbootcamp.teamd.global.auth.ParticipantPrincipal
import com.siheungbootcamp.teamd.global.ratelimit.RateLimit
import com.siheungbootcamp.teamd.global.ratelimit.RateLimitKey
import jakarta.validation.Valid
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.net.URI

@ConfigurationProperties("app.board")
data class BoardProperties(val frontendBaseUrl: String = "http://localhost:5173")

/** P1 HTTP 요청을 검증하고 애플리케이션 서비스 결과를 API 명세 형태로 반환한다. */
@RestController
@RequestMapping("/api/v1")
@EnableConfigurationProperties(BoardProperties::class)
class BoardController(private val service: BoardService, private val properties: BoardProperties) {
    @PostMapping("/boards")
    fun create(@Valid @RequestBody request: CreateBoardRequest): ResponseEntity<CreateBoardResponse> {
        val response = service.create(request, properties.frontendBaseUrl)
        return ResponseEntity.created(URI.create("/api/v1/boards/${response.board.boardId}")).body(response)
    }

    @GetMapping("/boards/{boardId}")
    @RateLimit(permits = 60, windowSeconds = 60, key = RateLimitKey.PARTICIPANT)
    fun get(@PathVariable boardId: String, @CurrentParticipant principal: ParticipantPrincipal) = service.get(boardId, principal)

    @PatchMapping("/boards/{boardId}")
    @RateLimit(permits = 60, windowSeconds = 60, key = RateLimitKey.PARTICIPANT)
    fun patch(@PathVariable boardId: String, @CurrentParticipant principal: ParticipantPrincipal, @Valid @RequestBody request: PatchBoardRequest) = service.patch(boardId, principal, request)

    @GetMapping("/boards/{boardId}/invitation")
    @RateLimit(permits = 60, windowSeconds = 60, key = RateLimitKey.PARTICIPANT)
    fun invitation(@PathVariable boardId: String, @CurrentParticipant principal: ParticipantPrincipal) = service.invitation(boardId, principal, properties.frontendBaseUrl)

    @GetMapping("/invitations/{inviteCode}")
    @RateLimit(permits = 30, windowSeconds = 60, key = RateLimitKey.IP)
    fun preview(@PathVariable inviteCode: String) = service.preview(inviteCode)

    @PostMapping("/invitations/{inviteCode}/participants")
    fun join(@PathVariable inviteCode: String, @Valid @RequestBody request: JoinRequest): ResponseEntity<JoinResponse> {
        val response = service.join(inviteCode, request)
        return ResponseEntity.created(URI.create("/api/v1/boards/${response.boardId}/participants/${response.participantId}")).body(response)
    }

    @GetMapping("/boards/{boardId}/participants")
    @RateLimit(permits = 60, windowSeconds = 60, key = RateLimitKey.PARTICIPANT)
    fun participants(@PathVariable boardId: String, @CurrentParticipant principal: ParticipantPrincipal) = service.list(boardId, principal)

    @PatchMapping("/boards/{boardId}/participants/me")
    @RequiresBoardOpen
    @RateLimit(permits = 60, windowSeconds = 60, key = RateLimitKey.PARTICIPANT)
    fun patchMe(@PathVariable boardId: String, @CurrentParticipant principal: ParticipantPrincipal, @Valid @RequestBody request: PatchMeRequest) = service.patchMe(boardId, principal, request)
}
