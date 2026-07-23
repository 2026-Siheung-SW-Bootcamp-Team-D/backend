package com.siheungbootcamp.teamd.domain.area

import com.siheungbootcamp.teamd.domain.board.Board
import com.siheungbootcamp.teamd.domain.board.BoardRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.random.Random

/**
 * AreaSearchJob 동시성 테스트.
 *
 * FOR UPDATE SKIP LOCKED 패턴이 정확히 작동하는지 검증한다:
 * - 여러 스레드가 동시에 같은 작업을 claim할 때
 * - 정확히 하나의 스레드만 성공하고 나머지는 null을 받아야 한다.
 */
@Testcontainers
@SpringBootTest(properties = [
    "app.auth.token-pepper=test-pepper",
    "app.crypto.origin-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
    "app.board.frontend-base-url=https://example.app",
    "app.kakao.rest-key=test-kakao-key",
    "app.odsay.api-key=test-odsay-key",
    "app.job.enabled=false",
])
class AreaConcurrencyTest(
    @Autowired private val jobRepository: AreaSearchJobRepository,
    @Autowired private val jobStateWriter: AreaJobStateWriter,
    @Autowired private val boardRepository: BoardRepository,
) {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun dynamicProperties(registry: DynamicPropertyRegistry) {
            // PostgreSQL 컨테이너는 @ServiceConnection으로 자동 연결
        }
    }

    @Test
    fun `FOR UPDATE SKIP LOCKED로 여러 스레드가 같은 작업을 동시에 claim할 때 정확히 하나만 성공한다`() {
        // 1-0. 보드 생성 (외래 키 제약 때문에 필수)
        val board = Board(
            name = "Concurrency Test Board",
            dateStart = LocalDate.of(2099, 1, 1),
            dateEnd = LocalDate.of(2099, 1, 2),
            purpose = "test",
            inviteCode = "test-invite-${Random.nextInt(100000)}",
            inviteExpiresAt = Instant.now().plusSeconds(86400),
        )
        val savedBoard = boardRepository.save(board)

        // 1. QUEUED 상태의 작업 하나 생성
        val job = AreaSearchJob(
            publicId = "test-job-${System.currentTimeMillis()}",
            boardId = savedBoard.id!!,
            durationMin = 45,
            snapshotJson = """{"participants":["p1","p2"]}""",
        )
        jobRepository.save(job)

        // 2. 여러 스레드가 동시에 같은 작업을 claim하도록 스핀
        val threadCount = 3
        val executor: ExecutorService = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(1)  // 모든 스레드가 동시에 시작하도록 동기화
        val results = mutableListOf<AreaSearchJob?>()
        val resultLock = Any()

        repeat(threadCount) {
            executor.submit {
                try {
                    // 모든 스레드가 동시에 시작하도록 대기
                    latch.await()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }

                // 각 스레드가 claimNextJob() 호출 (각각 자신의 트랜잭션에서)
                val claimed = jobStateWriter.claimNextJob()
                synchronized(resultLock) {
                    results.add(claimed)
                }
            }
        }

        // 모든 스레드 준비 완료 → 시작 신호
        latch.countDown()
        executor.shutdown()

        // 스레드 완료 대기
        val terminated = executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)
        if (!terminated) {
            executor.shutdownNow()
            throw AssertionError("스레드 실행 타임아웃")
        }

        // 3. 검증
        // - 정확히 하나의 스레드만 null이 아닌 작업을 받아야 함
        val successfulClaims = results.filterNotNull()
        val failedClaims = results.filter { it == null }

        assertEquals(
            1,
            successfulClaims.size,
            "정확히 1개의 스레드만 작업을 claim해야 하는데 ${successfulClaims.size}개가 성공함"
        )
        assertEquals(
            threadCount - 1,
            failedClaims.size,
            "${threadCount - 1}개의 스레드는 null을 받아야 함"
        )

        // 클레임한 작업이 RUNNING 상태인지 확인
        val claimedJob = successfulClaims[0]
        assertNotNull(claimedJob)
        assertEquals("RUNNING", claimedJob.status, "클레임한 작업의 상태는 RUNNING이어야 함")

        // DB에서 다시 조회해서 상태 확인
        val dbJob = jobRepository.findByPublicId(job.publicId)
        assertNotNull(dbJob)
        assertEquals("RUNNING", dbJob.status, "DB의 작업 상태도 RUNNING이어야 함")
    }

    @Test
    fun `RETRY_WAIT에서 nextRetryAt이 지난 작업도 정확히 하나의 스레드만 claim한다`() {
        // 1-0. 보드 생성 (외래 키 제약 때문에 필수)
        val board = Board(
            name = "Retry Test Board",
            dateStart = LocalDate.of(2099, 1, 1),
            dateEnd = LocalDate.of(2099, 1, 2),
            purpose = "test",
            inviteCode = "retry-invite-${Random.nextInt(100000)}",
            inviteExpiresAt = Instant.now().plusSeconds(86400),
        )
        val savedBoard = boardRepository.save(board)

        // 1. RETRY_WAIT 상태의 작업 생성 (nextRetryAt은 이미 지난 시간)
        val job = AreaSearchJob(
            publicId = "retry-job-${System.currentTimeMillis()}",
            boardId = savedBoard.id!!,
            durationMin = 30,
            snapshotJson = """{"participants":["p1"]}""",
        )
        job.status = "RETRY_WAIT"
        job.nextRetryAt = Instant.now().minusSeconds(60)  // 60초 전 (재시도 가능)
        job.retryCount = 1
        jobRepository.save(job)

        // 2. 여러 스레드가 동시에 이 작업을 claim
        val threadCount = 4
        val executor: ExecutorService = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(1)
        val results = mutableListOf<AreaSearchJob?>()
        val resultLock = Any()

        repeat(threadCount) {
            executor.submit {
                try {
                    latch.await()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }

                val claimed = jobStateWriter.claimNextJob()
                synchronized(resultLock) {
                    results.add(claimed)
                }
            }
        }

        latch.countDown()
        executor.shutdown()
        executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)

        // 3. 검증
        val successfulClaims = results.filterNotNull()
        assertEquals(
            1,
            successfulClaims.size,
            "RETRY_WAIT 작업도 정확히 1개의 스레드만 claim해야 함"
        )

        val claimedJob = successfulClaims[0]
        assertEquals("RUNNING", claimedJob.status)
        assertEquals(1, claimedJob.retryCount, "재시도 횟수는 유지되어야 함")
    }
}
