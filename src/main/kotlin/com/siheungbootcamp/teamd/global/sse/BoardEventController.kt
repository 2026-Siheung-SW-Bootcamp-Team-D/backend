package com.siheungbootcamp.teamd.global.sse

import com.siheungbootcamp.teamd.global.auth.AuthorizationChecks
import com.siheungbootcamp.teamd.global.auth.CurrentParticipant
import com.siheungbootcamp.teamd.global.auth.ParticipantPrincipal
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * 새로고침 없이 보드 화면을 갱신하기 위한 SSE 구독 엔드포인트다.
 *
 * 클라이언트는 이 연결로 "무엇이 바뀌었는지"만 받고, 실제 데이터는 기존 REST API를
 * 다시 호출해 가져간다. 그래야 이 엔드포인트가 참여자별 권한 필터링을 다시 구현하지 않아도 된다.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "실시간 갱신", description = "보드 변경을 SSE로 알립니다.")
class BoardEventController(
    private val publisher: BoardEventPublisher,
    private val checks: AuthorizationChecks,
) {
    @GetMapping("/boards/{boardId}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Operation(summary = "보드 실시간 이벤트 구독", description = "참가자, 선택 장소 등이 바뀌면 신호를 보냅니다. 데이터는 담기지 않으며 수신 측이 REST API로 다시 조회해야 합니다.")
    @SecurityRequirement(name = "participantToken")
    fun events(
        @PathVariable boardId: String,
        @Parameter(hidden = true) @CurrentParticipant principal: ParticipantPrincipal,
    ): ResponseEntity<SseEmitter> {
        checks.requireBoard(principal, boardId)
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(publisher.register(boardId))
    }
}
