package com.siheungbootcamp.teamd.domain.board

import com.siheungbootcamp.teamd.global.auth.CurrentParticipant
import com.siheungbootcamp.teamd.global.auth.ParticipantPrincipal
import com.siheungbootcamp.teamd.global.ratelimit.RateLimit
import com.siheungbootcamp.teamd.global.ratelimit.RateLimitKey
import com.siheungbootcamp.teamd.global.ratelimit.RateLimitScope
import jakarta.validation.Valid
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.http.ResponseEntity
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation.*
import java.net.URI
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag

@ConfigurationProperties("app.board")
data class BoardProperties(val frontendBaseUrl: String = "http://localhost:5173")

/** P1 HTTP 요청을 검증하고 애플리케이션 서비스 결과를 API 명세 형태로 반환한다. */
@RestController
@RequestMapping("/api/v1")
@EnableConfigurationProperties(BoardProperties::class)
@Tag(name = "P1 보드·초대·참여자", description = "보드를 만들고 초대로 참여한 뒤 참여자 정보를 관리합니다.")
class BoardController(private val service: BoardService, private val properties: BoardProperties) {
    @PostMapping("/boards")
    @Operation(summary = "보드 생성", description = "보드와 HOST 참여자를 한 트랜잭션에서 만들고 참여 토큰을 한 번 발급합니다.")
    @ApiResponse(responseCode = "201", description = "보드와 HOST 생성")
    @ApiResponse(responseCode = "400", description = "이름·기간·닉네임 검증 실패")
    fun create(@Valid @RequestBody request: CreateBoardRequest): ResponseEntity<CreateBoardResponse> {
        val response = service.create(request, properties.frontendBaseUrl)
        return ResponseEntity.created(URI.create("/api/v1/boards/${response.board.boardId}"))
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(response)
    }

    @GetMapping("/boards/{boardId}")
    @Operation(summary = "보드 조회", description = "현재 참여자가 속한 보드의 기본 정보와 집계를 조회합니다.")
    @SecurityRequirement(name = "participantToken")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "404", description = "보드가 없거나 다른 보드 토큰")
    @RateLimit(permits = 60, windowSeconds = 60, key = RateLimitKey.PARTICIPANT, scope = RateLimitScope.PARTICIPANT_GLOBAL)
    fun get(@PathVariable boardId: String, @Parameter(hidden = true) @CurrentParticipant principal: ParticipantPrincipal) = service.get(boardId, principal)

    @PatchMapping("/boards/{boardId}")
    @Operation(summary = "보드 수정", description = "같은 보드의 모든 활성 참여자가 이름과 목적을 수정할 수 있습니다.")
    @SecurityRequirement(name = "participantToken")
    @ApiResponse(responseCode = "200", description = "수정 성공")
    @ApiResponse(responseCode = "403", description = "활성 참여자가 아님")
    @ApiResponse(responseCode = "409", description = "이미 종료된 보드")
    @RateLimit(permits = 60, windowSeconds = 60, key = RateLimitKey.PARTICIPANT, scope = RateLimitScope.PARTICIPANT_GLOBAL)
    fun patch(@PathVariable boardId: String, @Parameter(hidden = true) @CurrentParticipant principal: ParticipantPrincipal, @Valid @RequestBody request: PatchBoardRequest) = service.patch(boardId, principal, request)

    @GetMapping("/boards/{boardId}/invitation")
    @Operation(summary = "현재 초대 정보 조회", description = "보드의 모든 참여자가 저장된 초대 코드 원문과 URL, 만료 시각을 조회할 수 있습니다.")
    @SecurityRequirement(name = "participantToken")
    @RateLimit(permits = 60, windowSeconds = 60, key = RateLimitKey.PARTICIPANT, scope = RateLimitScope.PARTICIPANT_GLOBAL)
    fun invitation(@PathVariable boardId: String, @Parameter(hidden = true) @CurrentParticipant principal: ParticipantPrincipal) = service.invitation(boardId, principal, properties.frontendBaseUrl)

    @PutMapping("/boards/{boardId}/selected-place")
    @Operation(summary = "장소 선택", description = "현재 참여자가 보드의 공동 선택 장소를 변경합니다. 마지막 쓰기가 우선됩니다.")
    @SecurityRequirement(name = "participantToken")
    @ApiResponse(responseCode = "200", description = "선택 성공")
    @ApiResponse(responseCode = "403", description = "다른 보드의 토큰")
    @ApiResponse(responseCode = "404", description = "보드 또는 장소 없음(또는 ACTIVE 상태가 아님)")
    @ApiResponse(responseCode = "409", description = "보드가 종료됨")
    @RequiresBoardOpen
    @RateLimit(permits = 60, windowSeconds = 60, key = RateLimitKey.PARTICIPANT, scope = RateLimitScope.PARTICIPANT_GLOBAL)
    fun putSelectedPlace(
        @PathVariable boardId: String,
        @Parameter(hidden = true) @CurrentParticipant principal: ParticipantPrincipal,
        @Valid @RequestBody request: SelectPlaceRequest,
    ) = service.selectPlace(boardId, request.placeId, principal)

    @DeleteMapping("/boards/{boardId}/selected-place")
    @Operation(summary = "장소 선택 해제", description = "현재 참여자가 보드의 공동 선택 장소를 해제합니다. 선택된 장소가 없어도 성공합니다.")
    @SecurityRequirement(name = "participantToken")
    @ApiResponse(responseCode = "204", description = "선택 해제 성공 또는 선택된 장소 없음")
    @ApiResponse(responseCode = "403", description = "다른 보드의 토큰")
    @ApiResponse(responseCode = "404", description = "보드 없음")
    @ApiResponse(responseCode = "409", description = "보드가 종료됨")
    @RequiresBoardOpen
    @RateLimit(permits = 60, windowSeconds = 60, key = RateLimitKey.PARTICIPANT, scope = RateLimitScope.PARTICIPANT_GLOBAL)
    fun deleteSelectedPlace(
        @PathVariable boardId: String,
        @Parameter(hidden = true) @CurrentParticipant principal: ParticipantPrincipal,
    ): ResponseEntity<Void> {
        service.clearSelection(boardId, principal)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/invitations/{inviteCode}")
    @Operation(summary = "초대 확인", description = "인증 없이 초대 대상 보드와 참여 가능 여부를 확인합니다. IP당 분당 30회로 제한됩니다.")
    @ApiResponse(responseCode = "200", description = "유효한 초대")
    @ApiResponse(responseCode = "404", description = "없거나 만료된 초대")
    @RateLimit(permits = 30, windowSeconds = 60, key = RateLimitKey.IP)
    fun preview(@PathVariable inviteCode: String) = service.preview(inviteCode)

    @PostMapping("/invitations/{inviteCode}/participants")
    @Operation(summary = "보드 참여", description = "MEMBER 참여자를 만들고 참여 토큰을 한 번 발급합니다.")
    @ApiResponse(responseCode = "201", description = "참여 성공")
    @ApiResponse(responseCode = "404", description = "없거나 만료된 초대")
    @ApiResponse(responseCode = "409", description = "종료된 보드")
    fun join(@PathVariable inviteCode: String, @Valid @RequestBody request: JoinRequest): ResponseEntity<JoinResponse> {
        val response = service.join(inviteCode, request)
        return ResponseEntity.created(URI.create("/api/v1/boards/${response.boardId}/participants/${response.participantId}"))
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(response)
    }

    @GetMapping("/boards/{boardId}/participants")
    @Operation(summary = "참여자 목록", description = "본인 출발지는 상세 정보를, 타인 출발지는 registered 여부만 반환합니다.")
    @SecurityRequirement(name = "participantToken")
    @RateLimit(permits = 60, windowSeconds = 60, key = RateLimitKey.PARTICIPANT, scope = RateLimitScope.PARTICIPANT_GLOBAL)
    fun participants(@PathVariable boardId: String, @Parameter(hidden = true) @CurrentParticipant principal: ParticipantPrincipal) = service.list(boardId, principal)

    @PatchMapping("/boards/{boardId}/participants/me")
    @Operation(summary = "내 참여자 정보 수정", description = "닉네임 또는 암호화 저장되는 출발지를 변경합니다. 활성 지역 찾기 작업이 있으면 출발지 변경을 거부합니다.")
    @SecurityRequirement(name = "participantToken")
    @ApiResponse(responseCode = "200", description = "수정 성공")
    @ApiResponse(responseCode = "409", description = "종료된 보드 또는 활성 지역 찾기 작업")
    @RequiresBoardOpen
    @RateLimit(permits = 60, windowSeconds = 60, key = RateLimitKey.PARTICIPANT, scope = RateLimitScope.PARTICIPANT_GLOBAL)
    fun patchMe(@PathVariable boardId: String, @Parameter(hidden = true) @CurrentParticipant principal: ParticipantPrincipal, @Valid @RequestBody request: PatchMeRequest) = service.patchMe(boardId, principal, request)
}
