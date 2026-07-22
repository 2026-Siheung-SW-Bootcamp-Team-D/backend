package com.siheungbootcamp.teamd.domain.board

import com.siheungbootcamp.teamd.global.auth.ParticipantPrincipal
import com.siheungbootcamp.teamd.global.auth.ParticipantRole
import com.siheungbootcamp.teamd.global.error.BusinessException
import com.siheungbootcamp.teamd.global.error.ErrorCode
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Testcontainers
@SpringBootTest(properties = [
    "app.auth.token-pepper=test-pepper",
    "app.crypto.origin-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
])
class BoardClosureConcurrencyTest(
    @Autowired private val boards: BoardRepository,
    @Autowired private val participants: ParticipantRepository,
    @Autowired private val service: BoardService,
    @Autowired transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)

    @Test
    fun `보드 종료가 먼저 잠그면 참여 생성은 대기 후 CLOSED를 보고 거부된다`() {
        val fixture = fixture()
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { executor ->
            val closing = executor.submit {
                transactions.executeWithoutResult {
                    val board = boards.findByPublicIdForUpdate(fixture.board.publicId)!!
                    locked.countDown()
                    release.await(3, TimeUnit.SECONDS)
                    board.close()
                }
            }
            locked.await(3, TimeUnit.SECONDS)
            val joining = executor.submit<ErrorCode?> {
                try {
                    service.join(fixture.board.inviteCode, JoinRequest("동시 참여자"))
                    null
                } catch (exception: BusinessException) {
                    exception.errorCode
                }
            }
            assertFailsWith<TimeoutException> { joining.get(200, TimeUnit.MILLISECONDS) }
            release.countDown()
            closing.get(3, TimeUnit.SECONDS)
            assertEquals(ErrorCode.RESOURCE_CONFLICT, joining.get(3, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `보드 종료가 먼저 잠그면 내 정보 수정은 대기 후 CLOSED를 보고 거부된다`() {
        val fixture = fixture()
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { executor ->
            val closing = executor.submit {
                transactions.executeWithoutResult {
                    val board = boards.findByPublicIdForUpdate(fixture.board.publicId)!!
                    locked.countDown()
                    release.await(3, TimeUnit.SECONDS)
                    board.close()
                }
            }
            locked.await(3, TimeUnit.SECONDS)
            val patching = executor.submit<ErrorCode?> {
                try {
                    service.patchMe(fixture.board.publicId, fixture.principal, PatchMeRequest(nickname = "동시 수정"))
                    null
                } catch (exception: BusinessException) {
                    exception.errorCode
                }
            }
            assertFailsWith<TimeoutException> { patching.get(200, TimeUnit.MILLISECONDS) }
            release.countDown()
            closing.get(3, TimeUnit.SECONDS)
            assertEquals(ErrorCode.RESOURCE_CONFLICT, patching.get(3, TimeUnit.SECONDS))
        }
    }

    private fun fixture(): Fixture {
        val board = boards.saveAndFlush(Board(
            name = "동시성 보드",
            dateStart = LocalDate.of(2099, 1, 1),
            dateEnd = LocalDate.of(2099, 1, 1),
            purpose = null,
            inviteCode = "L${System.nanoTime().toString().takeLast(9)}",
            inviteExpiresAt = Instant.parse("2099-02-01T00:00:00Z"),
        ))
        val participant = participants.saveAndFlush(Participant(
            board = board,
            nickname = "호스트",
            role = ParticipantRole.HOST,
            tokenHash = "hash",
            avatarColor = "#4A90E2",
        ))
        return Fixture(board, ParticipantPrincipal(requireNotNull(participant.id), board.publicId, ParticipantRole.HOST))
    }

    private data class Fixture(val board: Board, val principal: ParticipantPrincipal)

    companion object {
        @Container @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }
}
