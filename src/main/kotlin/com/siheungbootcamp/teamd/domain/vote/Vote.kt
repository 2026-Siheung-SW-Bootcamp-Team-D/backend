package com.siheungbootcamp.teamd.domain.vote

import com.siheungbootcamp.teamd.domain.board.Board
import com.siheungbootcamp.teamd.global.id.IdPrefix
import com.siheungbootcamp.teamd.global.id.PublicId
import com.siheungbootcamp.teamd.global.persistence.BaseEntity
import jakarta.persistence.*
import java.time.Instant

/**
 * 보드의 장소 투표를 나타낸다.
 *
 * 보드당 열린 투표는 최대 1개다. status는 OPEN 또는 CLOSED이고, anonymous 플래그는 생성 시 고정된다.
 */
@Entity
@Table(name = "vote")
class Vote(
    @Column(name = "public_id", nullable = false, unique = true) val publicId: String = PublicId.generate(IdPrefix.VOTE),
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "board_id") val board: Board,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: VoteStatus = VoteStatus.OPEN,
    @Column(name = "max_selections", nullable = false) val maxSelections: Int,
    @Column(nullable = false) val anonymous: Boolean,
    @Column(name = "closes_at", nullable = false) val closesAt: Instant,
) : BaseEntity() {
    @OneToMany(mappedBy = "vote", cascade = [CascadeType.ALL], orphanRemoval = true)
    private val _options: MutableList<VoteOption> = mutableListOf()

    val options: List<VoteOption> get() = _options.toList()

    fun addOption(option: VoteOption) {
        _options.add(option)
    }

    fun close() {
        status = VoteStatus.CLOSED
    }

    fun isClosed(): Boolean = status == VoteStatus.CLOSED

    fun isExpired(now: Instant = Instant.now()): Boolean = now.isAfter(closesAt)
}

enum class VoteStatus { OPEN, CLOSED }
