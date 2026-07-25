package com.siheungbootcamp.teamd.domain.course

import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64

/**
 * 확정 코스가 처음 생성될 때 발급하는 공개 공유 토큰을 만든다.
 *
 * 초대 코드와 마찬가지로 원문을 그대로 저장해야 공유 링크를 다시 보여줄 수 있으므로
 * 해시하지 않는다(ERD 설계원칙, 00-INDEX.md 8절 결정 1). 초대 코드보다 긴 128비트 난수를 써서
 * URL에 그대로 노출돼도 추측이 사실상 불가능하게 한다.
 */
@Component
class PublicTokenGenerator {
    private val random = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return "pub_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
