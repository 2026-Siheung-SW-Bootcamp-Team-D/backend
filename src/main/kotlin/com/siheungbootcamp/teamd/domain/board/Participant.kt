package com.siheungbootcamp.teamd.domain.board

import com.siheungbootcamp.teamd.global.auth.ParticipantRole
import com.siheungbootcamp.teamd.global.id.IdPrefix
import com.siheungbootcamp.teamd.global.id.PublicId
import com.siheungbootcamp.teamd.global.persistence.BaseEntity
import jakarta.persistence.*

/** 보드 안의 익명 참여자와 복구 불가능한 토큰 해시, 암호화된 출발지를 보존한다. */
@Entity
@Table(name = "participant")
class Participant(
    @Column(name = "public_id", nullable = false, unique = true) val publicId: String = PublicId.generate(IdPrefix.PARTICIPANT),
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "board_id") val board: Board,
    @Column(nullable = false) var nickname: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val role: ParticipantRole,
    @Column(name = "token_hash", nullable = false) val tokenHash: String,
    @Column(name = "avatar_color", nullable = false) val avatarColor: String,
    @Column(nullable = false) var active: Boolean = true,
    @Column(name = "origin_label") var originLabel: String? = null,
    @Column(name = "origin_ciphertext") var originCiphertext: ByteArray? = null,
    @Enumerated(EnumType.STRING) @Column(name = "origin_source") var originSource: OriginSource? = null,
    @Column(name = "origin_provider_place_id") var originProviderPlaceId: String? = null,
) : BaseEntity() {
    fun rename(value: String) { nickname = value }
    fun deactivate() { active = false }
    fun changeOrigin(label: String, ciphertext: ByteArray, source: OriginSource, providerPlaceId: String?) {
        originLabel = label; originCiphertext = ciphertext; originSource = source; originProviderPlaceId = providerPlaceId
    }
}

enum class OriginSource { KAKAO_KEYWORD, KAKAO_ADDRESS, MANUAL_PIN }
