package com.siheungbootcamp.teamd

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.scheduling.annotation.EnableScheduling

class TeamdApplicationSchedulingTest {
    @Test
    fun `애플리케이션이 scheduled task 처리를 활성화한다`() {
        assertTrue(TeamdApplication::class.java.isAnnotationPresent(EnableScheduling::class.java))
    }
}
