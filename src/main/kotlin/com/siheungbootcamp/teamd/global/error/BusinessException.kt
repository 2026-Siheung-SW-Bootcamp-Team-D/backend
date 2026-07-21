package com.siheungbootcamp.teamd.global.error

/**
 * 규칙을 어긴 요청을 일반적인 서버 오류와 구분하기 위한 예외다.
 *
 * 도메인 서비스는 이 예외에 [ErrorCode]와 필요한 공개 정보만 담아 던진다.
 * [GlobalExceptionHandler]가 이를 명세에 맞는 HTTP 오류 응답으로 바꾼다.
 */
class BusinessException(
    val errorCode: ErrorCode,
    val details: Map<String, Any?> = emptyMap(),
) : RuntimeException(errorCode.message)
