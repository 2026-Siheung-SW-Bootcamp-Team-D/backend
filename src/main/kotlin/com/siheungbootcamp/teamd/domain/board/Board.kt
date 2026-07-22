package com.siheungbootcamp.teamd.domain.board

import com.siheungbootcamp.teamd.global.id.IdPrefix
import com.siheungbootcamp.teamd.global.id.PublicId
import com.siheungbootcamp.teamd.global.persistence.BaseEntity
import jakarta.persistence.*
import java.time.Instant
import java.time.LocalDate

/** 약속의 기간과 참여 가능 상태, 재노출해야 하는 초대 코드 원문을 보존한다. */
@Entity
@Table(name = "board")
class Board(
    @Column(name = "public_id", nullable = false, unique = true) val publicId: String = PublicId.generate(IdPrefix.BOARD),
    @Column(nullable = false) var name: String,
    @Column(name = "date_start", nullable = false) var dateStart: LocalDate,
    @Column(name = "date_end", nullable = false) var dateEnd: LocalDate,
    var purpose: String?,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: BoardStatus = BoardStatus.COLLECTING,
    @Column(name = "invite_code", nullable = false, unique = true) val inviteCode: String,
    @Column(name = "invite_expires_at", nullable = false) var inviteExpiresAt: Instant,
    @Column(name = "public_token", unique = true) var publicToken: String? = null,
) : BaseEntity() {
    fun update(name: String?, start: LocalDate?, end: LocalDate?, purpose: String?) {
        name?.let { this.name = it }; start?.let { dateStart = it }; end?.let { dateEnd = it }
        if (purpose != null) this.purpose = purpose
    }
    fun confirm() { status = BoardStatus.CONFIRMED }
    fun close() { status = BoardStatus.CLOSED }
}

enum class BoardStatus { COLLECTING, CONFIRMED, CLOSED }
