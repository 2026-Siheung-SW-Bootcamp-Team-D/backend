package com.siheungbootcamp.teamd.domain.board

import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import java.time.Instant
import java.time.LocalDate

data class DateRangeRequest(@field:NotNull val start: LocalDate, @field:NotNull val end: LocalDate)
data class CreateBoardRequest(
    @field:Size(min = 2, max = 40) val name: String,
    @field:Valid val dateRange: DateRangeRequest,
    @field:Size(max = 100) val purpose: String?,
    @field:Size(min = 1, max = 20) val hostNickname: String,
)
data class PatchBoardRequest(
    @field:Size(min = 2, max = 40) val name: String? = null,
    @field:Size(max = 100) val purpose: String? = null,
)
data class JoinRequest(@field:Size(min = 1, max = 20) val nickname: String)
data class OriginRequest(
    @field:Size(min = 1, max = 100) val label: String,
    @field:DecimalMin("-180.0") @field:DecimalMax("180.0") val lon: Double,
    @field:DecimalMin("-90.0") @field:DecimalMax("90.0") val lat: Double,
    val source: OriginSource,
    @field:Size(max = 100) val providerPlaceId: String? = null,
)
data class PatchMeRequest(@field:Size(min = 1, max = 20) val nickname: String? = null, @field:Valid val origin: OriginRequest? = null)
data class SelectPlaceRequest(@field:NotBlank val placeId: String)

data class DateRangeResponse(val start: LocalDate, val end: LocalDate)
data class BoardSummary(val boardId: String, val name: String, val status: BoardStatus, val timezone: String = "Asia/Seoul", val dateRange: DateRangeResponse)
data class CreatedParticipant(val participantId: String, val nickname: String, val role: String, val participantToken: String)
data class InvitationResponse(val inviteCode: String, val inviteUrl: String, val expiresAt: Instant)
data class CreateBoardResponse(val board: BoardSummary, val participant: CreatedParticipant, val invitation: InvitationResponse)
data class BoardCounts(val participants: Long, val places: Long, val comments: Long)
data class BoardResponse(
    val boardId: String,
    val name: String,
    val dateRange: DateRangeResponse,
    val purpose: String?,
    val status: BoardStatus,
    val timezone: String = "Asia/Seoul",
    val counts: BoardCounts,
    val updatedAt: Instant,
    val selectedPlaceId: String? = null,
    val selectedByParticipantId: String? = null,
    val selectedAt: Instant? = null,
)
data class InvitePreviewResponse(val boardId: String, val boardName: String, val participantCount: Long, val joinable: Boolean, val expiresAt: Instant)
data class JoinResponse(val boardId: String, val participantId: String, val nickname: String, val role: String, val avatarColor: String, val participantToken: String)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class OriginResponse(val registered: Boolean, val label: String? = null, val lon: Double? = null, val lat: Double? = null)
data class ParticipantResponse(val participantId: String, val nickname: String, val role: String, val avatarColor: String, val origin: OriginResponse)
data class ParticipantListResponse(val items: List<ParticipantResponse>)
