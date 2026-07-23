package com.siheungbootcamp.teamd.domain.area

import com.siheungbootcamp.teamd.global.auth.CurrentParticipant
import com.siheungbootcamp.teamd.global.auth.ParticipantPrincipal
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.net.URI

/**
 * P6 지역 탐색 엔드포인트.
 *
 * POST /api/v1/boards/{boardId}/area-search-jobs: 작업 생성 (202 ACCEPTED)
 * GET  /api/v1/boards/{boardId}/area-search-jobs/{jobId}: 작업 조회 (200 OK)
 */
@RestController
@RequestMapping("/api/v1/boards/{boardId}/area-search-jobs")
@Tag(name = "P6 지역 탐색", description = "여러 참여자의 도달권 교집합을 바탕으로 만나기 좋은 지역을 비동기로 탐색합니다.")
class AreaController(
    private val areaService: AreaService,
) {
    @PostMapping
    @Operation(summary = "지역 탐색 작업 생성", description = "같은 보드의 활성 참여자가 요청할 수 있습니다. 같은 입력의 활성 작업이 있으면 재사용합니다.")
    @SecurityRequirement(name = "participantToken")
    @ApiResponse(responseCode = "202", description = "작업 접수, Location 헤더로 조회 위치 안내")
    @ApiResponse(responseCode = "400", description = "대상 참여자 1명 이하")
    @ApiResponse(responseCode = "422", description = "출발지 미등록 참여자 포함")
    fun createAreaSearchJob(
        @PathVariable boardId: String,
        @Parameter(hidden = true) @CurrentParticipant principal: ParticipantPrincipal,
        @RequestBody request: CreateAreaSearchJobRequest,
    ): ResponseEntity<CreateAreaSearchJobResponse> {
        val response = areaService.createAreaSearchJob(
            boardId = boardId,
            participantId = principal.participantId,
            request = request,
        )
        return ResponseEntity
            .accepted()
            .location(URI.create("/api/v1/boards/$boardId/area-search-jobs/${response.jobId}"))
            .body(response)
    }

    @GetMapping("/{jobId}")
    @Operation(summary = "지역 탐색 작업 조회", description = "진행 상태·결과·오류 코드를 폴링으로 조회합니다.")
    @SecurityRequirement(name = "participantToken")
    @ApiResponse(responseCode = "200", description = "조회 성공 (진행 중이면 result가 null)")
    @ApiResponse(responseCode = "404", description = "작업이 없거나 다른 보드")
    fun getAreaSearchJob(
        @PathVariable boardId: String,
        @PathVariable jobId: String,
        @Parameter(hidden = true) @CurrentParticipant principal: ParticipantPrincipal,
    ): ResponseEntity<GetAreaSearchJobResponse> {
        val response = areaService.getAreaSearchJob(
            boardId = boardId,
            participantId = principal.participantId,
            jobId = jobId,
        )
        return ResponseEntity.ok(response)
    }
}
