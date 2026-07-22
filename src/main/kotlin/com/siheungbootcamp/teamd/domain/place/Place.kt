package com.siheungbootcamp.teamd.domain.place

import com.siheungbootcamp.teamd.domain.board.Board
import com.siheungbootcamp.teamd.domain.board.Participant
import com.siheungbootcamp.teamd.global.id.IdPrefix
import com.siheungbootcamp.teamd.global.id.PublicId
import com.siheungbootcamp.teamd.global.persistence.BaseEntity
import jakarta.persistence.*
import java.time.Instant

/** 보드 내 장소를 나타내고 제안자·좌표·카테고리·출처를 기록한다. soft delete를 지원한다. */
@Entity
@Table(name = "place")
class Place(
    @Column(name = "public_id", nullable = false, unique = true) val publicId: String = PublicId.generate(IdPrefix.PLACE),
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "board_id") val board: Board,
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "proposer_id") val proposer: Participant,
    @Column(nullable = false) val name: String,
    @Column(nullable = false) val lon: Double,
    @Column(nullable = false) val lat: Double,
    @Column(name = "address_name") var addressName: String?,
    @Column(name = "road_address_name") var roadAddressName: String?,
    @Column(name = "internal_category", nullable = false) val internalCategory: String,
    @Column(nullable = true) val provider: String?,
    @Column(name = "provider_place_id") var providerPlaceId: String?,
    @Column(name = "provider_place_url") var providerPlaceUrl: String?,
    @Column(nullable = false) val source: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: PlaceStatus = PlaceStatus.ACTIVE,
    @Column(name = "deleted_at") var deletedAt: Instant? = null,
) : BaseEntity() {
    fun softDelete() {
        deletedAt = Instant.now()
        status = PlaceStatus.ARCHIVED
    }
}

enum class PlaceStatus { ACTIVE, ARCHIVED }
