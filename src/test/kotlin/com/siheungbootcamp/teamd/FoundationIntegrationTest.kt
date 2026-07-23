package com.siheungbootcamp.teamd

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.simple.JdbcClient
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(
    properties = [
        "app.auth.token-pepper=test-pepper",
        "app.crypto.origin-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
    ],
)
class FoundationIntegrationTest(
    @Autowired private val jdbcClient: JdbcClient,
    @Autowired private val mockMvc: MockMvc,
) {
    @Test
    fun `health check는 PostgreSQL DB 컴포넌트를 포함해 UP을 반환한다`() {
        mockMvc.get("/actuator/health").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
            jsonPath("$.components.db.status") { value("UP") }
        }
    }

    @Test
    fun `Flyway V1은 ERD 전체 13개 테이블과 이력 한 행을 만든다`() {
        val tables = jdbcClient.sql(
            "select count(*) from information_schema.tables where table_schema='public' and table_name <> 'flyway_schema_history'",
        ).query(Int::class.java).single()
        val migrations = jdbcClient.sql("select count(*) from flyway_schema_history where success=true")
            .query(Int::class.java).single()

        assertEquals(14, tables)
        assertEquals(3, migrations)  // V1, V2, V3
    }

    @Test
    fun `열린 투표와 활성 지역 작업은 보드별 부분 unique index를 가진다`() {
        val definitions = jdbcClient.sql(
            "select indexdef from pg_indexes where indexname in ('uq_vote_open_per_board','uq_area_job_active_per_board')",
        ).query(String::class.java).list().mapNotNull { it }

        assertEquals(2, definitions.size)
        assertTrue(definitions.any { it.contains("WHERE (status = 'OPEN'::text)") })
        assertTrue(definitions.any { it.contains("QUEUED") && it.contains("RUNNING") && it.contains("RETRY_WAIT") })
    }

    @Test
    fun `P6 migration은 기존 테이블을 보존하고 공동 후보 컬럼만 추가한다`() {
        assertEquals(3, jdbcClient.sql(  // V1, V2, V3
            "select count(*) from flyway_schema_history where success=true"
        ).query(Int::class.java).single())
        assertEquals(3, jdbcClient.sql(
            """
            select count(*) from information_schema.columns
            where table_name='board'
              and column_name in ('selected_place_id','selected_by_participant_id','selected_at')
            """.trimIndent()
        ).query(Int::class.java).single())
        assertEquals(1, jdbcClient.sql(
            "select count(*) from information_schema.tables where table_name='place_like'"
        ).query(Int::class.java).single())
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }
}
