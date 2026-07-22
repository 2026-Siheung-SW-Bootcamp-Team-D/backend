package com.siheungbootcamp.teamd.global.web

import org.springframework.data.domain.Page

/** Spring의 0-base 페이지를 FE 계약의 1-base 응답으로 변환한다. */
data class PageResponse<T : Any>(val items: List<T>, val page: PageMetadata) {
    data class PageMetadata(val number: Int, val size: Int, val totalItems: Long, val totalPages: Int)

    companion object {
        const val DEFAULT_SIZE = 20
        const val MAX_SIZE = 50

        fun <T : Any> from(source: Page<T>) = PageResponse(
            items = source.content,
            page = PageMetadata(source.number + 1, source.size, source.totalElements, source.totalPages),
        )
    }
}
