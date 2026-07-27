package com.siheungbootcamp.teamd.domain.area

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import kotlin.test.assertEquals

@Testcontainers
class V5AreaSuggestionRankMigrationTest {
    @Test
    fun `area suggestion은 10위까지 저장할 수 있다`() {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    insert into board(public_id,name,status,invite_code,invite_expires_at)
                    values ('board_rank_10','rank test','COLLECTING','invite_rank_10',now() + interval '1 day')
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into area_search_job(public_id,board_id,status,duration_min,snapshot)
                    values ('job_rank_10',(select id from board where public_id='board_rank_10'),'SUCCEEDED',60,'{}')
                    """.trimIndent(),
                )
                val inserted = statement.executeUpdate(
                    """
                    insert into area_suggestion(public_id,job_id,name,lon,lat,provider_place_id,metrics,reasons,rank)
                    values ('suggestion_rank_10',(select id from area_search_job where public_id='job_rank_10'),
                            '열 번째 후보',127.0,37.5,'rank-10','{}','{}',10)
                    """.trimIndent(),
                )
                assertEquals(1, inserted)
            }
        }
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }
}
