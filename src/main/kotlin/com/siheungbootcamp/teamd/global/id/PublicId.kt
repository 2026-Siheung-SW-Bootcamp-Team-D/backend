package com.siheungbootcamp.teamd.global.id

import java.security.SecureRandom

/** 외부에 노출할 자원 ID를 고정 접두사와 ULID로 생성한다. */
object PublicId {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()

    fun generate(prefix: IdPrefix): String {
        val bytes = ByteArray(16)
        val timestamp = System.currentTimeMillis()
        repeat(6) { index -> bytes[index] = (timestamp ushr (40 - index * 8)).toByte() }
        ByteArray(10).also(random::nextBytes).copyInto(bytes, 6)
        return prefix.value + encode(bytes)
    }

    private fun encode(bytes: ByteArray): String {
        var buffer = 0
        var bits = 2
        val result = StringBuilder(26)
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                result.append(ALPHABET[(buffer ushr bits) and 31])
            }
        }
        return result.toString()
    }
}
