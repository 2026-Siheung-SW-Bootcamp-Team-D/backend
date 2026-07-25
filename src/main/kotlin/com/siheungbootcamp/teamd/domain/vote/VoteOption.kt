package com.siheungbootcamp.teamd.domain.vote

import com.siheungbootcamp.teamd.domain.place.Place
import jakarta.persistence.*

/**
 * 투표의 후보 장소를 나타낸다.
 *
 * 같은 투표 내에서 같은 장소 중복을 방지한다.
 */
@Entity
@Table(name = "vote_option", uniqueConstraints = [UniqueConstraint(columnNames = ["vote_id", "place_id"])])
class VoteOption(
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "vote_id") val vote: Vote,
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "place_id") val place: Place,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set
}
