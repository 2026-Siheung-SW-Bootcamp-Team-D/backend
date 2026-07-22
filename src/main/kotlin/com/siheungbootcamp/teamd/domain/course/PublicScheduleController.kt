package com.siheungbootcamp.teamd.domain.course

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 엔드포인트 34. 인증 없이 호출되는 공개 일정 조회다.
 *
 * `SecurityConfig`가 이 경로를 이미 permitAll로 열어 두었다. 토큰 존재 여부를 숨기기 위해
 * CLOSED 보드와 없는 토큰을 똑같이 404로 응답한다.
 */
@RestController
@RequestMapping("/api/v1/public/schedules")
@Tag(name = "P4 코스·공개 일정", description = "코스 초안을 편집하고 확정한 뒤 공개 일정을 조회합니다.")
class PublicScheduleController(private val service: CourseService) {

    @GetMapping("/{publicToken}")
    @Operation(summary = "공개 일정 조회", description = "인증 없이 현재 확정 코스만 반환합니다. 참여자·출발지·토큰·댓글·투표·boardId는 포함하지 않습니다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "404", description = "토큰이 없거나 보드가 CLOSED (구분 없이 동일하게 반환)")
    fun get(@PathVariable publicToken: String): ResponseEntity<PublicScheduleResponse> =
        ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-cache").body(service.publicSchedule(publicToken))
}
