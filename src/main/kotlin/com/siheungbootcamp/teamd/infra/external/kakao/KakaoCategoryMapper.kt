package com.siheungbootcamp.teamd.infra.external.kakao

/**
 * Kakao Local API의 카테고리 문자열을 내부 카테고리(8종)로 매핑한다.
 *
 * Kakao의 category_name은 "대분류 > 중분류 > 소분류"처럼 계층이 깊고 다양해서
 * 완전 일치만으로는 실제 응답 대부분을 놓친다(예: "음식점 > 일식 > 돈까스,우동").
 * 그래서 구체적인 예외만 [overrides]로 먼저 잡고, 나머지는 " > "로 나눈 첫 토큰(대분류)을
 * [topLevel]에서 찾는 방식으로 분류한다. 둘 다 실패하면 ETC.
 */
object KakaoCategoryMapper {
    // 대분류만으로는 잘못 분류되는 예외 케이스를 대분류보다 먼저 확인한다.
    private val overrides = mapOf(
        "음식점 > 카페" to "CAFE",
        "음식점 > 주점" to "BAR",
        "관광지 > 관광명소" to "ATTRACTION",
        "관광지 > 유적지" to "ATTRACTION",
    )

    private val topLevel = mapOf(
        "음식점" to "RESTAURANT",
        "카페" to "CAFE",
        "공원" to "PLAY",
        "스포츠" to "PLAY",
        "스포츠,레저" to "PLAY",
        "레저" to "PLAY",
        "레져" to "PLAY",
        "주점" to "BAR",
        "펍" to "BAR",
        "바" to "BAR",
        "문화시설" to "CULTURE",
        "박물관" to "CULTURE",
        "미술관" to "CULTURE",
        "도서관" to "CULTURE",
        "영화관" to "CULTURE",
        "관광지" to "ATTRACTION",
        "명소" to "ATTRACTION",
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
        overrides[kakaoCategory]?.let { return it }
        val topToken = kakaoCategory.substringBefore(" > ").trim()
        return topLevel[topToken] ?: "ETC"
    }
}
