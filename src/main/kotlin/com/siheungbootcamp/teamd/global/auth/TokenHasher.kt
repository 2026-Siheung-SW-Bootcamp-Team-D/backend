package com.siheungbootcamp.teamd.global.auth

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@ConfigurationProperties("app.auth")
data class AuthProperties(val tokenPepper: String) {
    init {
        require(tokenPepper.isNotBlank()) { "TOKEN_PEPPER must not be blank" }
    }
}

/** 참여 토큰 secret만 HMAC으로 바꿔 DB 유출 시 원문을 보호한다. */
@Component
class TokenHasher(private val properties: AuthProperties) {
    fun hash(secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(properties.tokenPepper.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return HexFormat.of().formatHex(mac.doFinal(secret.toByteArray(StandardCharsets.UTF_8)))
    }

    fun matches(secret: String, expectedHash: String): Boolean = MessageDigest.isEqual(
        hash(secret).toByteArray(StandardCharsets.US_ASCII),
        expectedHash.toByteArray(StandardCharsets.US_ASCII),
    )
}
