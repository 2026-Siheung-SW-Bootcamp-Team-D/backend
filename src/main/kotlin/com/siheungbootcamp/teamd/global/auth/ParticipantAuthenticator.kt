package com.siheungbootcamp.teamd.global.auth

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

interface ParticipantAuthenticator {
    fun authenticate(token: ParticipantToken): ParticipantPrincipal?
}

/** P1 엔티티가 생기기 전에도 V1 participant 테이블로 토큰을 검증할 수 있는 최소 저장소 경계다. */
@Component
class JdbcParticipantAuthenticator(
    private val jdbcClient: JdbcClient,
    private val tokenHasher: TokenHasher,
) : ParticipantAuthenticator {
    override fun authenticate(token: ParticipantToken): ParticipantPrincipal? {
        val record = jdbcClient.sql(
            """
            select p.id, b.public_id as board_public_id, p.role, p.token_hash
            from participant p join board b on b.id = p.board_id
            where p.public_id = :publicId and p.active = true
            """.trimIndent(),
        ).param("publicId", token.participantPublicId)
            .query { rs, _ ->
                AuthRecord(rs.getLong("id"), rs.getString("board_public_id"), rs.getString("role"), rs.getString("token_hash"))
            }.optional().orElse(null) ?: return null
        if (!tokenHasher.matches(token.secret, record.tokenHash)) return null
        return ParticipantPrincipal(record.id, record.boardId, ParticipantRole.valueOf(record.role))
    }

    private data class AuthRecord(val id: Long, val boardId: String, val role: String, val tokenHash: String)
}
