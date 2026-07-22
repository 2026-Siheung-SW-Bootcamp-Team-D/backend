package com.siheungbootcamp.teamd.global.ratelimit

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RateLimit(val permits: Int, val windowSeconds: Long, val key: RateLimitKey)

enum class RateLimitKey { PARTICIPANT, BOARD, IP }
