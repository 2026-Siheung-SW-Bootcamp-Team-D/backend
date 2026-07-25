package com.siheungbootcamp.teamd.domain.vote

import com.siheungbootcamp.teamd.domain.board.Participant
import jakarta.persistence.*

/**
 * 참여자의 투표 선택을 기록한다.
 *
 * (vote_id, participant_id, option_id)의 unique constraint로 중복을 방지한다.
 * PUT 교체 시 (vote_id, participant_id) 전체를 삭제 후 다시 삽입한다.
 */
@Entity
@Table(name = "vote_ballot", uniqueConstraints = [UniqueConstraint(columnNames = ["vote_id", "participant_id", "option_id"])])
class VoteBallot(
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "vote_id") val vote: Vote,
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "participant_id") val participant: Participant,
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "option_id") val option: VoteOption,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set
}
