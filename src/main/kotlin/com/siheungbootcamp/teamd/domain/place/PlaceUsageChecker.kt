package com.siheungbootcamp.teamd.domain.place

/**
 * 장소가 투표·코스 등에서 사용 중인지 확인하는 인터페이스.
 *
 * P2에서는 인터페이스만 정의하고 구현체는 없다.
 * P3(투표), P4(코스)가 각자 구현체를 추가한다.
 *
 * 삭제 서비스는 이 체커들을 순회해서 하나라도 사용 중이면 PLACE_IN_USE 오류를 반환한다.
 */
interface PlaceUsageChecker {
    /**
     * 주어진 placeId가 사용 중인지 확인한다.
     * @return 사용 중이면 PlaceUsage, 미사용이면 null
     */
    fun findUsage(placeId: Long): PlaceUsage?
}

/**
 * 장소 사용 정보를 담는 간단한 데이터 홀더.
 */
data class PlaceUsage(
    val details: Map<String, Any?> = emptyMap(),
)
