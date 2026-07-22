package com.siheungbootcamp.teamd.global.error

import com.siheungbootcamp.teamd.global.web.RequestIdFilter
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.http.converter.HttpMessageNotReadableException

/**
 * 컨트롤러와 서비스에서 발생한 예외를 API 명세의 오류 JSON으로 변환하는 공통 처리기다.
 *
 * 컨트롤러마다 try-catch를 반복하지 않아도 되고, 내부 예외 정보가 외부로 새는 것을 막는다.
 * 예상하지 못한 예외의 상세 내용은 서버 로그에만 남기고 클라이언트에는 안전한 안내만 반환한다.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val logger = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(
        exception: BusinessException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> = response(exception.errorCode, exception.details, request)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationException(
        exception: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        val details = exception.bindingResult.fieldErrors.associate { error ->
            error.field to (error.defaultMessage ?: "유효하지 않은 값입니다.")
        }
        return response(ErrorCode.INVALID_ARGUMENT, details, request)
    }

    /** JSON 필드 누락·날짜 파싱·enum 불일치는 내부 오류가 아니라 잘못된 클라이언트 입력이다. */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableMessage(
        exception: HttpMessageNotReadableException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> = response(ErrorCode.INVALID_ARGUMENT, emptyMap(), request)

    @ExceptionHandler(Exception::class)
    fun handleUnexpectedException(
        exception: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        logger.error("Unexpected server error. requestId={}", requestId(request), exception)
        return response(ErrorCode.INTERNAL_ERROR, emptyMap(), request)
    }

    private fun response(
        errorCode: ErrorCode,
        details: Map<String, Any?>,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> = ResponseEntity
        .status(errorCode.status)
        .body(ErrorResponse.from(errorCode, details, requestId(request)))

    private fun requestId(request: HttpServletRequest): String =
        request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)?.toString() ?: "unknown"
}
