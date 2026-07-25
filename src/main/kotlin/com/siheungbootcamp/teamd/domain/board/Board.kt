package com.siheungbootcamp.teamd.domain.board

import com.siheungbootcamp.teamd.global.id.IdPrefix
import com.siheungbootcamp.teamd.global.id.PublicId
import com.siheungbootcamp.teamd.global.persistence.BaseEntity
import jakarta.persistence.*
import java.time.Instant

/** 참여 가능 상태와 재노출해야 하는 초대 코드 원문을 보존한다. 참여자의 공동 선택 장소를 기록한다. */
@Entity
@Table(name = "board")
class Board(
    @Column(name = "public_id", nullable = false, unique = true) val publicId: String = PublicId.generate(IdPrefix.BOARD),
    @Column(nullable = false) var name: String,
    var purpose: String?,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: BoardStatus = BoardStatus.COLLECTING,
    @Column(name = "invite_code", nullable = false, unique = true) val inviteCode: String,
    @Column(name = "invite_expires_at", nullable = false) var inviteExpiresAt: Instant,
    @Column(name = "public_token", unique = true) var publicToken: String? = null,
    @Column(name = "selected_place_id") var selectedPlaceId: Long? = null,
    @Column(name = "selected_by_participant_id") var selectedByParticipantId: Long? = null,
    @Column(name = "selected_at") var selectedAt: Instant? = null,
) : BaseEntity() {
    fun update(name: String?, purpose: String?) {
        name?.let { this.name = it }
        if (purpose != null) this.purpose = purpose
    }
    fun confirm() { status = BoardStatus.CONFIRMED }
    fun close() { status = BoardStatus.CLOSED }

    fun select(placeId: Long, participantId: Long, now: Instant) {
        selectedPlaceId = placeId
        selectedByParticipantId = participantId
        selectedAt = now
    }

    fun clearSelection(participantId: Long, now: Instant) {
        selectedPlaceId = null
        selectedByParticipantId = participantId
        selectedAt = now
    }
}

enum class BoardStatus { COLLECTING, CONFIRMED, CLOSED }
