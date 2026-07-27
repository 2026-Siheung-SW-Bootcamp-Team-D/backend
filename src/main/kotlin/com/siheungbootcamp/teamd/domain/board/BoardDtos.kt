package com.siheungbootcamp.teamd.domain.board

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSetter
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import java.time.Instant
data class CreateBoardRequest(
    @field:Size(min = 2, max = 40) val name: String,
    @field:Size(max = 100) val purpose: String?,
    @field:Size(min = 1, max = 20) val creatorNickname: String,
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
class PatchMeRequest(
    @field:Size(min = 1, max = 20) val nickname: String? = null,
) {
    @field:Valid
    var origin: OriginRequest? = null
        @JsonSetter("origin") set(value) {
            field = value
            originProvided = true
        }

    @get:JsonIgnore
    var originProvided: Boolean = false
        private set
}
data class SelectPlaceRequest(@field:NotBlank val placeId: String)

data class BoardSummary(val boardId: String, val name: String, val purpose: String?, val status: BoardStatus, val timezone: String = "Asia/Seoul")
data class CreatedParticipant(val participantId: String, val nickname: String, val role: String, val avatarColor: String)
data class InvitationResponse(val inviteCode: String, val inviteUrl: String, val expiresAt: Instant)
data class CreateBoardResponse(
    val board: BoardSummary,
    val creatorParticipant: CreatedParticipant,
    val invitation: InvitationResponse,
    val participantToken: String,
)
data class BoardCounts(val participants: Long, val places: Long, val comments: Long)
data class BoardResponse(
    val boardId: String,
    val name: String,
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

data class LiveLocationRequest(
    @field:DecimalMin("-90.0") @field:DecimalMax("90.0") val lat: Double,
    @field:DecimalMin("-180.0") @field:DecimalMax("180.0") val lon: Double,
    @field:DecimalMin("0.0") @field:DecimalMax("5000.0") val accuracyMeters: Double? = null,
)

data class LiveLocationResponse(
    val participantId: String,
    val nickname: String,
    val avatarColor: String,
    val lat: Double,
    val lon: Double,
    val accuracyMeters: Double?,
    val updatedAt: Instant,
)

data class LiveLocationListResponse(val items: List<LiveLocationResponse>)
