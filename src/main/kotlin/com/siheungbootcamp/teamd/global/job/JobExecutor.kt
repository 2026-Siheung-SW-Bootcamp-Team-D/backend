package com.siheungbootcamp.teamd.global.job

/**
 * 백그라운드 작업을 실행하는 통용 인터페이스.
 *
 * 각 Job Executor 구현은:
 * 1. @Scheduled 폴러에서 처리 대상 행을 조회
 * 2. 짧은 트랜잭션으로 상태를 "실행 중"으로 변경
 * 3. 트랜잭션 끝내기
 * 4. 외부 API 호출 (DB 트랜잭션 없음)
 * 5. 결과를 짧은 트랜잭션으로 저장
 *
 * 이 패턴으로 외부 API 호출 중 DB 커넥션을 점유하지 않는다.
 */
interface JobExecutor {
    /**
     * 한 건의 작업을 처리한다.
     * 호출자가 폴링 루프를 관리하고, 구현체는 한 건만 처리하고 반환한다.
     */
    fun processOne(): Boolean
}
