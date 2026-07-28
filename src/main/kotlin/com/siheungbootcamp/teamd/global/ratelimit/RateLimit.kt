package com.siheungbootcamp.teamd.global.ratelimit

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RateLimit(
    val permits: Int,
    val windowSeconds: Long,
    val key: RateLimitKey,
    val scope: RateLimitScope = RateLimitScope.ENDPOINT,
    val bucket: RateLimitBucket = RateLimitBucket.GENERAL,
)

enum class RateLimitKey { PARTICIPANT, BOARD, IP }
enum class RateLimitScope { ENDPOINT, PARTICIPANT_GLOBAL }
enum class RateLimitBucket { GENERAL, EXTERNAL, LIVE_LOCATION }
