package com.siheungbootcamp.teamd.global.crypto

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@ConfigurationProperties("app.crypto")
data class CryptoProperties(val originKey: String)

/** 개인 출발지 평문을 AES-GCM으로 인증 암호화하며 IV를 암호문 앞에 저장한다. */
@Component
class OriginCipher(properties: CryptoProperties) {
    private val key = SecretKeySpec(Base64.getDecoder().decode(properties.originKey), "AES")
    private val random = SecureRandom()

    init {
        require(key.encoded.size == 32) { "ORIGIN_ENC_KEY must be a base64 encoded 256-bit key" }
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(plaintext)
    }

    fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > 28) { "Invalid encrypted origin" }
        val iv = ciphertext.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext.copyOfRange(12, ciphertext.size))
    }
}
