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
 * 다른 외부 API 연동 단계가 재사용하는 레퍼런스 구현이므로 provider 이름을 파라미터로 받는다.
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

        repeat(3) { attempt ->
            try {
                // onStatus를 모든 상태코드에 대해 no-op으로 등록해 4xx/5xx도 예외 없이 toEntity로 받는다.
                // 그래야 429의 Retry-After 헤더와 5xx/기타 4xx를 구분해서 직접 분기할 수 있다.
                val entity = restClient.get()
                    .uri(url)
                    .headers { httpHeaders ->
                        headers.forEach { (key, value) -> httpHeaders.set(key, value) }
                    }
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus({ true }) { _, _ -> }
                    .toEntity(String::class.java)

                val duration = Duration.between(startTime, Instant.now())
                val status = entity.statusCode

                if (status.is2xxSuccessful) {
                    val body = entity.body ?: throw BusinessException(ErrorCode.EXTERNAL_BAD_RESPONSE)
                    quotaManager.incrementCount(provider)
                    logger.info("external_api_call provider=$provider attempt=${attempt + 1} status=${status.value()} duration_ms=${duration.toMillis()}")
                    return body
                }

                if (status.value() == 429) {
                    val retryAfterSeconds = entity.headers.getFirst("Retry-After")?.toLongOrNull()
                    logger.warn("external_api_call provider=$provider attempt=${attempt + 1} status=429 retries_remaining=${2 - attempt}")
                    if (attempt < 2) {
                        val waitMs = retryAfterSeconds?.let { it * 1000 } ?: ((1L shl attempt) * 1000) // 1s, 2s, 4s
                        Thread.sleep(waitMs)
                        return@repeat
                    }
                    // 3회 재시도를 모두 429로 소진하면 아래로 빠져나가 EXTERNAL_UNAVAILABLE로 수렴한다.
                } else if (status.is5xxServerError) {
                    logger.warn("external_api_call provider=$provider attempt=${attempt + 1} status=${status.value()} error=server_error")
                    throw BusinessException(ErrorCode.EXTERNAL_UNAVAILABLE)
                } else {
                    logger.warn("external_api_call provider=$provider attempt=${attempt + 1} status=${status.value()} error=bad_response")
                    throw BusinessException(ErrorCode.EXTERNAL_BAD_RESPONSE)
                }
            } catch (e: RestClientException) {
                // 상태코드 기반 분기는 위에서 이미 처리했으므로, 여기 걸리는 건 타임아웃 등 순수 통신 오류다.
                logger.warn("external_api_call provider=$provider attempt=${attempt + 1} error=timeout retries_remaining=${2 - attempt}")
                if (attempt < 2) {
                    val waitMs = (1L shl attempt) * 1000 // 1s, 2s, 4s
                    Thread.sleep(waitMs)
                } else {
                    throw BusinessException(ErrorCode.EXTERNAL_UNAVAILABLE)
                }
            }
        }

        logger.warn("external_api_call provider=$provider error=unavailable retry_count=3")
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
