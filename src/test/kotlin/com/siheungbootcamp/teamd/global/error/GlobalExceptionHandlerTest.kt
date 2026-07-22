package com.siheungbootcamp.teamd.global.error

import com.siheungbootcamp.teamd.global.web.RequestIdFilter
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

class GlobalExceptionHandlerTest {

    private val mockMvc: MockMvc = MockMvcBuilders.standaloneSetup(FailingController())
        .apply {
            setControllerAdvice(GlobalExceptionHandler())
            addFilter<StandaloneMockMvcBuilder>(RequestIdFilter())
        }
        .build()

    @Test
    fun `business exception is returned using the API error contract`() {
        mockMvc.perform(
            get("/test/business-error")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "c1b17953-1f0c-4dfa-8f8e-0a41a5db5fd5"),
        )
            .andExpect(status().isConflict)
            .andExpect(header().string("X-Request-Id", "c1b17953-1f0c-4dfa-8f8e-0a41a5db5fd5"))
            .andExpect(jsonPath("$.error.code").value("RESOURCE_CONFLICT"))
            .andExpect(jsonPath("$.error.message").value("현재 상태와 요청이 충돌했습니다."))
            .andExpect(jsonPath("$.error.details.resource").value("board"))
            .andExpect(jsonPath("$.error.requestId").value("c1b17953-1f0c-4dfa-8f8e-0a41a5db5fd5"))
    }

    @RestController
    private class FailingController {

        @GetMapping("/test/business-error")
        fun throwBusinessException(): Nothing = throw BusinessException(
            errorCode = ErrorCode.RESOURCE_CONFLICT,
            details = mapOf("resource" to "board"),
        )
    }
}
