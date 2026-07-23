package com.siheungbootcamp.teamd.domain.place

import jakarta.persistence.*

/** 참여자가 장소에 좋아요를 표현하는 관계를 기록한다. 복합 키(place_id, participant_id)로 중복을 방지한다. */
@Entity
@Table(name = "place_like")
class PlaceLike(
    @EmbeddedId val id: PlaceLikeId = PlaceLikeId(),
)

/** 장소와 참여자의 복합 키. */
@Embeddable
data class PlaceLikeId(
    @Column(name = "place_id") val placeId: Long = 0,
    @Column(name = "participant_id") val participantId: Long = 0,
)
