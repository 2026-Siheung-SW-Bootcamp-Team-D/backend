package com.siheungbootcamp.teamd.global.error

/**
 * 실패한 API 요청이 항상 같은 모양으로 반환되도록 만드는 응답 DTO다.
 *
 * 클라이언트는 [ErrorBody.code]로 오류를 처리하고, [ErrorBody.message]는 화면 안내에 사용한다.
 * requestId는 같은 요청의 서버 로그를 찾을 때 사용하는 추적 번호다.
 */
data class ErrorResponse(
    val error: ErrorBody,
) {
    data class ErrorBody(
        val code: String,
        val message: String,
        val details: Map<String, Any?>,
        val requestId: String,
    )

    companion object {
        fun from(errorCode: ErrorCode, details: Map<String, Any?>, requestId: String): ErrorResponse =
            ErrorResponse(
                error = ErrorBody(
                    code = errorCode.name,
                    message = errorCode.message,
                    details = details,
                    requestId = requestId,
                ),
            )
    }
}
