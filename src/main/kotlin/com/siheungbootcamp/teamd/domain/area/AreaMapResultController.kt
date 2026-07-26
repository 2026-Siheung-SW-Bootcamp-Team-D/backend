package com.siheungbootcamp.teamd.domain.area

import com.siheungbootcamp.teamd.global.auth.CurrentParticipant
import com.siheungbootcamp.teamd.global.auth.ParticipantPrincipal
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 보드 메인 지도에서 기존 지역 탐색의 공통 영역을 다시 읽는 전용 엔드포인트다.
 * 작업 상세 경로와 분리해, 클라이언트가 과거 job ID를 저장하거나 개별 도달권을 받을 필요가 없게 한다.
 */
@RestController
@RequestMapping("/api/v1/boards/{boardId}/area-search-results")
@Tag(name = "P6 지역 탐색", description = "여러 참여자의 도달권 교집합을 바탕으로 만나기 좋은 지역을 비동기로 탐색합니다.")
class AreaMapResultController(
    private val areaService: AreaService,
) {
    @GetMapping
    @Operation(summary = "메인 지도용 공통 영역 조회", description = "보드의 시간별 최신 성공 지역 탐색 결과에서 공통 도달 영역만 반환합니다.")
    @SecurityRequirement(name = "participantToken")
    @ApiResponse(responseCode = "200", description = "시간별 최신 성공 결과 조회")
    fun getAreaSearchMapResults(
        @PathVariable boardId: String,
        @Parameter(hidden = true) @CurrentParticipant principal: ParticipantPrincipal,
    ): ResponseEntity<AreaSearchMapResultsResponse> = ResponseEntity.ok(
        areaService.getAreaSearchMapResults(boardId, principal.participantId),
    )
}
