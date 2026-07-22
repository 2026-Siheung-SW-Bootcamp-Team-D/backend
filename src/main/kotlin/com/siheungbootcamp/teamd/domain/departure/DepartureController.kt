package com.siheungbootcamp.teamd.domain.departure

import com.siheungbootcamp.teamd.global.auth.CurrentParticipant
import com.siheungbootcamp.teamd.global.auth.ParticipantPrincipal
import com.siheungbootcamp.teamd.global.ratelimit.RateLimit
import com.siheungbootcamp.teamd.global.ratelimit.RateLimitKey
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.support.ServletUriComponentsBuilder

/**
 * 개인 출발 안내 엔드포인트 (32, 33).
 *
 * 32: GET /departure-guide - 현재 계산 상태 조회
 * 33: POST /departure-calculations - 계산 요청 (비동기, 202 Accepted 반환)
 */
@RestController
@RequestMapping("/api/v1/boards/{boardId}/participants/me")
@Tag(name = "Departure", description = "개인 출발 안내")
class DepartureController(
    private val departureService: DepartureService,
) {

    @GetMapping("/departure-guide")
    @Operation(summary = "출발 안내 상태 조회", description = "현재 계산 상태와 결과를 조회한다")
    @SecurityRequirement(name = "participantToken")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "404", description = "다른 보드의 토큰")
    fun getDepartureGuide(
        @PathVariable boardId: String,
        @Parameter(hidden = true) @CurrentParticipant principal: ParticipantPrincipal,
    ): ResponseEntity<GetDepartureGuideResponse> {
        val result = departureService.getDepartureGuide(boardId, principal)
        return ResponseEntity.ok(
            GetDepartureGuideResponse(
                status = result.status,
                courseVersion = result.courseVersion,
                firstMeeting = result.firstMeeting?.let {
                    FirstMeetingDto(
                        placeId = it.placeId,
                        name = it.name,
                        scheduledAt = it.scheduledAt
                    )
                },
                transit = result.transit?.let {
                    TransitDto(
                        totalSeconds = it.totalSeconds,
                        transferCount = it.transferCount,
                        fare = FareDto(amount = it.fare.amount, currency = it.fare.currency),
                        totalWalkSeconds = it.totalWalkSeconds
                    )
                },
                recommendedDepartureAt = result.recommendedDepartureAt,
                calculatedAt = result.calculatedAt,
                basis = result.basis
            )
        )
    }

    @PostMapping("/departure-calculations")
    @Operation(summary = "출발 안내 계산 요청", description = "TMAP을 통해 출발 시간을 계산하도록 요청한다 (비동기, 202 Accepted 반환)")
    @SecurityRequirement(name = "participantToken")
    @ApiResponse(responseCode = "202", description = "계산 요청 접수, Location 헤더로 /departure-guide 링크 제공")
    @ApiResponse(responseCode = "200", description = "이미 계산된 READY 결과 반환")
    @ApiResponse(responseCode = "422", description = "출발지 미등록 (ORIGIN_REQUIRED)")
    @ApiResponse(responseCode = "409", description = "확정 코스 또는 첫 만남 없음 (RESOURCE_CONFLICT)")
    @ApiResponse(responseCode = "404", description = "다른 보드의 토큰")
    @ApiResponse(responseCode = "429", description = "참여자당 5회/시간 제한 초과")
    @RateLimit(permits = 5, windowSeconds = 3600, key = RateLimitKey.PARTICIPANT)
    fun requestCalculation(
        @PathVariable boardId: String,
        @Parameter(hidden = true) @CurrentParticipant principal: ParticipantPrincipal,
    ): ResponseEntity<PostDepartureCalculationsResponse> {
        val result = departureService.requestCalculation(boardId, principal)

        return if (result.status == "READY") {
            // 이미 계산된 결과 반환 (200)
            val guide = departureService.getDepartureGuide(boardId, principal)
            ResponseEntity.ok(
                PostDepartureCalculationsResponse(
                    status = result.status,
                    courseVersion = result.courseVersion,
                    firstMeeting = guide.firstMeeting?.let {
                        FirstMeetingDto(
                            placeId = it.placeId,
                            name = it.name,
                            scheduledAt = it.scheduledAt
                        )
                    },
                    transit = guide.transit?.let {
                        TransitDto(
                            totalSeconds = it.totalSeconds,
                            transferCount = it.transferCount,
                            fare = FareDto(amount = it.fare.amount, currency = it.fare.currency),
                            totalWalkSeconds = it.totalWalkSeconds
                        )
                    },
                    recommendedDepartureAt = guide.recommendedDepartureAt,
                    calculatedAt = guide.calculatedAt,
                    basis = guide.basis
                )
            )
        } else {
            // 계산 중 (202)
            val uri = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/../departure-guide")
                .build()
                .toUri()

            ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .header("Location", uri.toString())
                .header("Retry-After", "2")
                .body(
                    PostDepartureCalculationsResponse(
                        status = result.status,
                        courseVersion = result.courseVersion
                    )
                )
        }
    }
}

data class GetDepartureGuideResponse(
    val status: String,
    val courseVersion: Int?,
    val firstMeeting: FirstMeetingDto?,
    val transit: TransitDto?,
    val recommendedDepartureAt: String?,
    val calculatedAt: String?,
    val basis: String?,
)

data class PostDepartureCalculationsResponse(
    val status: String,
    val courseVersion: Int,
    val firstMeeting: FirstMeetingDto? = null,
    val transit: TransitDto? = null,
    val recommendedDepartureAt: String? = null,
    val calculatedAt: String? = null,
    val basis: String? = null,
)

data class FirstMeetingDto(
    val placeId: String,
    val name: String,
    val scheduledAt: String,
)

data class TransitDto(
    val totalSeconds: Int,
    val transferCount: Int,
    val fare: FareDto,
    val totalWalkSeconds: Int,
)

data class FareDto(
    val amount: Int,
    val currency: String,
)
