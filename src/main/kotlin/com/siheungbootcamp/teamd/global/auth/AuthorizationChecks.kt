package com.siheungbootcamp.teamd.global.auth

import com.siheungbootcamp.teamd.global.error.BusinessException
import com.siheungbootcamp.teamd.global.error.ErrorCode
import org.springframework.stereotype.Component

/** 교차 보드 존재 여부를 숨기고 호스트 역할을 공통 오류 계약으로 검사한다. */
@Component
class AuthorizationChecks {
    fun requireBoard(principal: ParticipantPrincipal, boardId: String) {
        if (principal.boardId != boardId) throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
    }

    fun requireHost(principal: ParticipantPrincipal) {
        if (principal.role != ParticipantRole.HOST) throw BusinessException(ErrorCode.FORBIDDEN)
    }
}
