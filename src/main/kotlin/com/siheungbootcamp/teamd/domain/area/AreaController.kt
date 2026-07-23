package com.siheungbootcamp.teamd.domain.area

import com.siheungbootcamp.teamd.global.auth.CurrentParticipant
import com.siheungbootcamp.teamd.global.auth.ParticipantPrincipal
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.net.URI

/**
 * 지역 탐색 엔드포인트.
 *
 * POST /api/v1/boards/{boardId}/area-search-jobs: 작업 생성 (202 ACCEPTED)
 * GET  /api/v1/boards/{boardId}/area-search-jobs/{jobId}: 작업 조회 (200 OK)
 */
@RestController
@RequestMapping("/api/v1/boards/{boardId}/area-search-jobs")
class AreaController(
    private val areaService: AreaService,
) {
    @PostMapping
    fun createAreaSearchJob(
        @PathVariable boardId: String,
        @CurrentParticipant principal: ParticipantPrincipal,
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
    fun getAreaSearchJob(
        @PathVariable boardId: String,
        @PathVariable jobId: String,
        @CurrentParticipant principal: ParticipantPrincipal,
    ): ResponseEntity<GetAreaSearchJobResponse> {
        val response = areaService.getAreaSearchJob(
            boardId = boardId,
            participantId = principal.participantId,
            jobId = jobId,
        )
        return ResponseEntity.ok(response)
    }
}
