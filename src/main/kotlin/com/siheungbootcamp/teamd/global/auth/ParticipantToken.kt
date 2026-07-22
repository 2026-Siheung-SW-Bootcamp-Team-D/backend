package com.siheungbootcamp.teamd.global.auth

import java.security.SecureRandom
import java.util.Base64

/** 참여자 공개 ID와 복구할 수 없는 비밀값을 묶는 인증 토큰이다. */
class ParticipantToken private constructor(
    val participantPublicId: String,
    val secret: String,
) {
    val value: String = "$participantPublicId.$secret"

    companion object {
        private val secureRandom = SecureRandom()
        private val publicIdPattern = Regex("ptc_[0-9A-HJKMNP-TV-Z]{26}")

        fun generate(participantPublicId: String): ParticipantToken {
            require(publicIdPattern.matches(participantPublicId))
            val bytes = ByteArray(32).also(secureRandom::nextBytes)
            return ParticipantToken(participantPublicId, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
        }

        fun parse(value: String): ParticipantToken? {
            val separator = value.indexOf('.')
            if (separator <= 0 || separator == value.lastIndex) return null
            val publicId = value.substring(0, separator)
            val secret = value.substring(separator + 1)
            if (!publicIdPattern.matches(publicId) || secret.length < 43) return null
            return ParticipantToken(publicId, secret)
        }
    }
}
