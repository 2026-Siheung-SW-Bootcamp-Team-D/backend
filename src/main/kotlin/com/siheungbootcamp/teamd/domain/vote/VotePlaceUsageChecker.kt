package com.siheungbootcamp.teamd.domain.vote

import com.siheungbootcamp.teamd.domain.place.PlaceUsageChecker
import com.siheungbootcamp.teamd.domain.place.PlaceUsage
import org.springframework.stereotype.Component

/**
 * 열린 투표가 참조하는 장소의 삭제를 방지한다.
 *
 * PlaceService.delete()에서 모든 PlaceUsageChecker 구현체를 순회하며 호출된다.
 * 투표가 장소를 사용 중이면 usage 정보를 반환하고, 그렇지 않으면 null을 반환한다.
 *
 * 레거시 API 노출 여부와 무관하게 기존 투표 데이터의 FK 참조를 보호한다.
 */
@Component
class VotePlaceUsageChecker(
    private val options: VoteOptionRepository,
) : PlaceUsageChecker {
    override fun findUsage(placeId: Long): PlaceUsage? {
        // 이 장소를 옵션으로 참조하는 열린 투표를 찾는다
        val openVoteOptions = options.findOpenVotesByPlaceId(placeId)

        if (openVoteOptions.isNotEmpty()) {
            val vote = openVoteOptions.first().vote
            return PlaceUsage(
                details = mapOf("voteId" to vote.publicId),
            )
        }
        return null
    }
}
