package com.siheungbootcamp.teamd.global.crypto

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse

class OriginCipherTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

    @Test
    fun `좌표 평문은 AES GCM으로 왕복되고 매번 다른 암호문을 만든다`() {
        val cipher = OriginCipher(CryptoProperties(key))
        val plaintext = "126.7341,37.3399".toByteArray()

        val first = cipher.encrypt(plaintext)
        val second = cipher.encrypt(plaintext)

        assertContentEquals(plaintext, cipher.decrypt(first))
        assertContentEquals(plaintext, cipher.decrypt(second))
        assertFalse(first.contentEquals(second))
    }
}
