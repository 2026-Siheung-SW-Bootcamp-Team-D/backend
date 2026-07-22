package com.siheungbootcamp.teamd.global.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ParticipantTokenTest {
    @Test
    fun `생성한 토큰은 public id와 256비트 secret으로 왕복된다`() {
        val generated = ParticipantToken.generate("ptc_01J00000000000000000000000")
        val parsed = ParticipantToken.parse(generated.value)

        assertEquals("ptc_01J00000000000000000000000", parsed?.participantPublicId)
        assertTrue(requireNotNull(parsed).secret.length >= 43)
    }

    @Test
    fun `token hasher는 같은 pepper와 secret만 일치시킨다`() {
        val secret = "secret"
        val first = TokenHasher(AuthProperties("first-pepper")).hash(secret)

        assertTrue(TokenHasher(AuthProperties("first-pepper")).matches(secret, first))
        assertFalse(TokenHasher(AuthProperties("other-pepper")).matches(secret, first))
        assertNotEquals(first, TokenHasher(AuthProperties("other-pepper")).hash(secret))
    }
}
