package com.siheungbootcamp.teamd.infra.external.kakao

/**
 * Kakao Local API의 카테고리 문자열을 내부 카테고리(8종)로 매핑한다.
 *
 * 매핑 실패 시 기본값 ETC를 반환한다.
 * 이 매핑 테이블은 한 파일에 집중되어 있어 변경 시 일관성을 유지할 수 있다.
 */
object KakaoCategoryMapper {
    private val mapping = mapOf(
        // RESTAURANT: 음식점, 식당, 카페 아님
        "음식점" to "RESTAURANT",
        "음식점 > 한식" to "RESTAURANT",
        "음식점 > 일식" to "RESTAURANT",
        "음식점 > 중식" to "RESTAURANT",
        "음식점 > 양식" to "RESTAURANT",
        "음식점 > 기타" to "RESTAURANT",
        "음식점 > 한식 > 돈까스,우동" to "RESTAURANT",

        // CAFE: 카페, 음료점, 카페 전문점
        "카페" to "CAFE",
        "카페 > 카페" to "CAFE",
        "음식점 > 카페" to "CAFE",

        // PLAY: 공원, 놀이공원, 스포츠, 레져
        "공원" to "PLAY",
        "스포츠" to "PLAY",
        "레져" to "PLAY",
        "관광지" to "PLAY",

        // BAR: 주점, 술집, 펍, 바
        "음식점 > 주점" to "BAR",
        "주점" to "BAR",
        "펍" to "BAR",
        "바" to "BAR",

        // CULTURE: 문화시설, 박물관, 미술관, 도서관, 영화관
        "문화시설" to "CULTURE",
        "박물관" to "CULTURE",
        "미술관" to "CULTURE",
        "도서관" to "CULTURE",
        "영화관" to "CULTURE",

        // ATTRACTION: 관광명소, 명소, 유적지
        "관광지 > 관광명소" to "ATTRACTION",
        "관광지 > 유적지" to "ATTRACTION",
        "명소" to "ATTRACTION",

        // TRANSIT: 역, 버스정류소, 터미널, 공항, 항구
        "지하철역" to "TRANSIT",
        "기차역" to "TRANSIT",
        "버스정류소" to "TRANSIT",
        "터미널" to "TRANSIT",
        "공항" to "TRANSIT",
        "항구" to "TRANSIT",
        "역" to "TRANSIT",
    )

    fun map(kakaoCategory: String?): String {
        if (kakaoCategory.isNullOrBlank()) return "ETC"
        return mapping[kakaoCategory] ?: "ETC"
    }
}
