package com.siheungbootcamp.teamd.domain.departure

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

/**
 * P5 출발 안내 엔드포인트 계약 테스트.
 *
 * 테스트 환경: Job Executor 비활성화 (app.job.enabled=false)
 * 출발 계산은 Job 폴러 대신 테스트에서 직접 호출해 검증한다.
 *
 * 검증 사항:
 * - V5-1: E2E: 출발지 등록 → 계산 요청 → READY
 * - V5-2: 계산은 명시적 POST로만 발생
 * - V5-3: 중복 요청이 작업을 늘리지 않음
 * - V5-4: READY 재요청은 외부 미호출
 * - V5-5: 출발지 없음 → 422 ORIGIN_REQUIRED
 * - V5-6: 확정 코스 없음 → 409
 * - V5-9: 권장 출발시각 계산식
 * - V5-13: 좌표가 로그·응답에 없음
 */
@SpringBootTest
@TestPropertySource(properties = ["app.job.enabled=false"])
class P5DepartureContractTest {

    @Test
    @Disabled("실제 테스트는 P5 fixture와 TMAP stub이 준비된 후 구현")
    fun `placeholder test`() {
        // 구현은 다음 단계에서: BoardFixture, ParticipantFixture, TMAP mock
    }
}
