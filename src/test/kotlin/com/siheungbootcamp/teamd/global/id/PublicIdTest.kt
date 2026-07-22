package com.siheungbootcamp.teamd.global.id

import kotlin.test.Test
import kotlin.test.assertTrue

class PublicIdTest {
    @Test
    fun `모든 public id 접두사는 고정된 ULID를 생성한다`() {
        IdPrefix.entries.forEach { prefix ->
            assertTrue(PublicId.generate(prefix).matches(Regex("${prefix.value}[0-9A-HJKMNP-TV-Z]{26}")))
        }
    }
}
