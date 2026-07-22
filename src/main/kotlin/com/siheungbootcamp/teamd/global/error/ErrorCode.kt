package com.siheungbootcamp.teamd.global.error

import org.springframework.http.HttpStatus

/**
 * API가 실패했을 때 클라이언트에 전달할 오류의 종류를 한곳에서 정의한다.
 *
 * HTTP 상태 코드는 통신 결과를, [message]는 사용자가 이해할 수 있는 안내를 뜻한다.
 * 기능 코드에서는 문자열을 직접 작성하지 않고 이 enum 값을 사용해야 응답 형식이 흔들리지 않는다.
 */
enum class ErrorCode(
    val status: HttpStatus,
    val message: String,
) {
    INVALID_ARGUMENT(HttpStatus.BAD_REQUEST, "요청 값을 확인해 주세요."),
    URL_QUERY_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "URL 형식의 검색어는 사용할 수 없습니다."),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "이 작업을 수행할 권한이 없습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 정보를 찾을 수 없습니다."),
    INVITE_NOT_FOUND(HttpStatus.NOT_FOUND, "초대 코드를 찾을 수 없거나 만료되었습니다."),
    RESOURCE_CONFLICT(HttpStatus.CONFLICT, "현재 상태와 요청이 충돌했습니다."),
    JOB_ALREADY_RUNNING(HttpStatus.CONFLICT, "같은 작업이 이미 진행 중입니다."),
    PLACE_IN_USE(HttpStatus.CONFLICT, "코스에 포함된 장소입니다. 먼저 코스에서 제거해 주세요."),
    VERSION_MISMATCH(HttpStatus.PRECONDITION_FAILED, "다른 변경 사항이 있어 최신 초안을 다시 확인해 주세요."),
    ORIGIN_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "출발지 정보가 필요합니다."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "요청이 많습니다. 잠시 후 다시 시도해 주세요."),
    EXTERNAL_BAD_RESPONSE(HttpStatus.BAD_GATEWAY, "외부 서비스 응답을 처리할 수 없습니다."),
    EXTERNAL_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "외부 서비스를 일시적으로 사용할 수 없습니다."),
    QUOTA_EXCEEDED(HttpStatus.SERVICE_UNAVAILABLE, "오늘의 외부 서비스 사용 한도에 도달했습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."),
}
