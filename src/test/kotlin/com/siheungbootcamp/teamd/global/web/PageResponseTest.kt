package com.siheungbootcamp.teamd.global.web

import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class PageResponseTest {
    @Test
    fun `Spring의 0-base page를 API의 1-base 계약으로 변환한다`() {
        val response = PageResponse.from(PageImpl(listOf("a"), PageRequest.of(1, 20), 21))

        assertEquals(2, response.page.number)
        assertEquals(20, response.page.size)
        assertEquals(21, response.page.totalItems)
        assertEquals(2, response.page.totalPages)
    }
}
