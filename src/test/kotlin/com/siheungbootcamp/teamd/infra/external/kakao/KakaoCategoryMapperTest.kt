package com.siheungbootcamp.teamd.infra.external.kakao

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** 리뷰 지적사항: 완전 일치만으로는 API명세서 6.1의 예시조차 매핑에 실패했다. */
class KakaoCategoryMapperTest {
    @Test
    fun `API명세서 6_1 예시 카테고리는 RESTAURANT로 분류된다`() {
        assertEquals("RESTAURANT", KakaoCategoryMapper.map("음식점 > 일식 > 돈까스,우동"))
    }

    @Test
    fun `테이블에 없는 임의의 소분류도 최상위 토큰 기준으로 분류된다`() {
        assertEquals("RESTAURANT", KakaoCategoryMapper.map("음식점 > 베트남음식"))
        assertEquals("CULTURE", KakaoCategoryMapper.map("문화시설 > 전시관"))
    }

    @Test
    fun `구체적인 예외는 최상위 토큰보다 우선한다`() {
        assertEquals("CAFE", KakaoCategoryMapper.map("음식점 > 카페"))
        assertEquals("BAR", KakaoCategoryMapper.map("음식점 > 주점"))
    }

    @Test
    fun `대분류 자체가 없거나 매핑에 없으면 ETC다`() {
        assertEquals("ETC", KakaoCategoryMapper.map("숙박"))
        assertEquals("ETC", KakaoCategoryMapper.map(null))
        assertEquals("ETC", KakaoCategoryMapper.map(""))
    }
}
