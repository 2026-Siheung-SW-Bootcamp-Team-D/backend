package com.siheungbootcamp.teamd.domain.board

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.security.SecureRandom

/** 혼동 문자를 뺀 URL-safe 난수 초대 코드를 만든다. 원문은 호스트에게 재노출해야 하므로 해시하지 않는다. */
@Component
class InviteCodeGenerator {
    private val random = SecureRandom()
    private val alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    fun generate(): String = buildString { repeat(10) { append(alphabet[random.nextInt(alphabet.length)]) } }
}

interface DepartureStaleNotifier { fun markStale(participantId: Long) }
interface DepartureStaleBoardNotifier { fun markStaleByCourse(boardId: Long) }

/** P6 작업이 참여자의 기존 좌표를 사용 중인지 JSONB snapshot에서 확인하는 작은 경계다. */
@Component
class ActiveAreaSearchJobChecker(private val jdbcClient: JdbcClient) {
    fun exists(participantPublicId: String): Boolean = jdbcClient.sql(
        "select exists(select 1 from area_search_job where status in ('QUEUED','RUNNING','RETRY_WAIT') and snapshot @> cast(:snapshot as jsonb))",
    ).param("snapshot", "[\"$participantPublicId\"]").query(Boolean::class.java).single()
}
