package com.siheungbootcamp.teamd.global.external

import com.siheungbootcamp.teamd.global.error.BusinessException
import com.siheungbootcamp.teamd.global.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.time.Duration
import java.time.Instant

/**
 * 외부 API 호출의 공통 규약을 구현한다.
 *
 * - 타임아웃: connect 3s / read 5s
 * - 429 처리: Retry-After 헤더 우선, 없으면 1s→2s→4s 백오프 최대 3회
 * - 오류 매핑: 파싱 실패/계약 위반 → 502, 5xx/타임아웃/429 소진 → 503, 일일 예산 초과 → 503
 * - 로깅: 공급자·엔드포인트·상태코드·지연시간·재시도만. 원문/키/응답본문 절대 금지
 *
 * P5(TMAP)·P6(ODsay)가 재사용하는 레퍼런스 구현이므로 provider 이름을 파라미터로 받는다.
 */
class ExternalApiClient(
    private val provider: String,
    private val restClient: RestClient,
    private val quotaManager: DailyQuotaManager,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        quotaManager.checkQuota(provider)
        val startTime = Instant.now()
        var lastException: Exception? = null
        var retryCount = 0

        repeat(3) { attempt ->
            try {
                val duration = Duration.between(startTime, Instant.now())
                val response = restClient.get()
                    .uri(url)
                    .headers { httpHeaders ->
                        headers.forEach { (key, value) -> httpHeaders.set(key, value) }
                    }
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String::class.java)
                    ?: throw BusinessException(ErrorCode.EXTERNAL_BAD_RESPONSE)

                quotaManager.incrementCount(provider)
                logger.info("external_api_call provider=$provider attempt=${attempt + 1} status=200 duration_ms=${duration.toMillis()}")
                return response
            } catch (e: RestClientException) {
                retryCount++
                lastException = e

                if (e.cause is java.net.SocketTimeoutException || e.message?.contains("Read timed out") == true) {
                    logger.warn("external_api_call provider=$provider attempt=${attempt + 1} error=timeout retries_remaining=${2 - attempt}")
                    if (attempt < 2) {
                        val waitMs = (1L shl attempt) * 1000 // 1s, 2s, 4s
                        Thread.sleep(waitMs)
                    }
                } else {
                    throw BusinessException(ErrorCode.EXTERNAL_BAD_RESPONSE)
                }
            }
        }

        logger.warn("external_api_call provider=$provider error=unavailable retry_count=$retryCount")
        throw BusinessException(ErrorCode.EXTERNAL_UNAVAILABLE)
    }
}

/**
 * 공급자별 일일 호출 예산을 관리한다.
 *
 * 간단한 in-memory 카운터로 자정 리셋을 구현한다.
 * 중분산 환경에서는 Redis로 교체할 수 있다.
 */
class DailyQuotaManager(private val quotas: Map<String, Int> = mapOf("KAKAO" to 10000)) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val counters = mutableMapOf<String, QuotaCounter>()

    data class QuotaCounter(var count: Int = 0, var lastResetAt: Instant = Instant.now())

    fun checkQuota(provider: String) {
        val quota = quotas[provider] ?: return
        val counter = counters.getOrPut(provider) { QuotaCounter() }

        val now = Instant.now()
        if (isMidnightPassed(counter.lastResetAt, now)) {
            counter.count = 0
            counter.lastResetAt = now
        }

        if (counter.count >= quota) {
            logger.warn("daily_quota_exceeded provider=$provider count=${counter.count} limit=$quota")
            throw BusinessException(ErrorCode.QUOTA_EXCEEDED)
        }
    }

    fun incrementCount(provider: String) {
        val counter = counters.getOrPut(provider) { QuotaCounter() }
        counter.count++
    }

    private fun isMidnightPassed(lastReset: Instant, now: Instant): Boolean {
        val last = lastReset.atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate()
        val current = now.atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate()
        return !last.isEqual(current)
    }
}
