package com.siheungbootcamp.teamd.domain.departure

import com.siheungbootcamp.teamd.domain.board.DepartureStaleNotifier
import com.siheungbootcamp.teamd.domain.board.DepartureStaleBoardNotifier
import org.springframework.stereotype.Component

/**
 * P1에서 호출: 참여자의 출발지가 변경되면 해당 참여자의 모든 출발 계산 결과를 STALE로 표시한다.
 */
@Component
class RealDepartureStaleNotifier(
    private val departureRepository: DepartureCalculationRepository,
) : DepartureStaleNotifier {
    override fun markStale(participantId: Long) {
        departureRepository.markStaleByParticipantId(participantId)
    }
}

/**
 * P4에서 호출: 코스가 확정되면 해당 보드의 모든 참여자의 출발 계산 결과를 STALE로 표시한다.
 */
@Component
class RealDepartureStaleBoardNotifier(
    private val departureRepository: DepartureCalculationRepository,
) : DepartureStaleBoardNotifier {
    override fun markStaleByCourse(boardId: Long) {
        departureRepository.markStaleByCourseBoard(boardId)
    }
}
