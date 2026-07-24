package com.siheungbootcamp.teamd.domain.area

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import kotlin.test.assertEquals

@Testcontainers
class P7AreaMigrationTest {
    @Test
    fun `V3는 기존 area candidate를 canonical suggestion으로 보존한다`() {
        migrateTo("2")
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    insert into board(public_id,name,date_start,date_end,status,invite_code,invite_expires_at)
                    values ('board_migration','migration','2099-01-01','2099-01-02','COLLECTING','invite_migration',now() + interval '1 day')
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into area_search_job(public_id,board_id,status,duration_min,snapshot)
                    values ('job_migration',(select id from board where public_id='board_migration'),'SUCCEEDED',45,'{}')
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into area_candidate(public_id,job_id,name,lon,lat,provider_place_id,metrics,reasons,rank)
                    values ('candidate_migration',(select id from area_search_job where public_id='job_migration'),
                            '정왕역',126.7421,37.3517,'123456789','{}','{}',1)
                    """.trimIndent(),
                )
            }
        }

        migrateTo("3")

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    select count(*), min(provider), min(center_distance_m), min(provider_place_id)
                    from area_suggestion where public_id='candidate_migration'
                    """.trimIndent(),
                ).use { result ->
                    result.next()
                    assertEquals(1, result.getInt(1))
                    assertEquals("KAKAO", result.getString(2))
                    assertEquals(0, result.getInt(3))
                    assertEquals("123456789", result.getString(4))
                }
            }
        }
    }

    private fun migrateTo(version: String) {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .target(MigrationVersion.fromVersion(version))
            .load()
            .migrate()
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }
}
