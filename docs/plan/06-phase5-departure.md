# P5 — 개인 출발 안내 (TMAP + Job Executor)

> 선행: **P4(확정 코스)**, P1(출발지) · 후행: P6가 이 단계의 Job 구조를 재사용
> 엔드포인트: 32~33 (API명세서 11절)

---

## 이 단계의 의미

**비동기 작업 실행기(Job Executor)를 처음 만드는 단계**다. P6(지역 찾기)가 훨씬 복잡하지만 같은 뼈대를 쓰므로, **더 단순한 P5에서 뼈대를 먼저 검증**한다. 순서를 뒤집지 말 것.

---

## 작업 목록

### T5-1. TMAP Transit 어댑터
`infra/external/tmap/` — P2의 외부 호출 공통 기반 재사용

- 입력: 출발 좌표, 도착 좌표, 도착 희망 시각
- 사용 응답: `totalSeconds`, `transferCount`, `fare.amount(KRW)`, `totalWalkSeconds`
- 경로 없음 → 도메인 결과 `UNAVAILABLE` (예외 아님). **`422 ROUTE_UNAVAILABLE`을 반환하지 않는다** — 계산은 `202` 응답 이후 백그라운드에서 돌기 때문에 그 시점에 HTTP 오류를 줄 대상이 없다. `GET /departure-guide`의 상태로만 수렴한다
- **동시성 1로 시작** — 개발용 TMAP 키의 호출 제한 때문 (아키텍처 4절 규칙 5)

### T5-2. Job Executor 뼈대 (`global/job/`) ⭐ P6와 공유
아키텍처 4절 "구현 규칙" 1~9번을 구현한다.

- `@Scheduled(fixedDelay = 1000)` 폴러 (단일 프로세스·단일 VM 전제)
- 외부 호출 전용 실행 풀: **동시성 1**
- **외부 API 호출 중에는 DB 트랜잭션을 열어두지 않는다** — 상태 저장은 짧은 트랜잭션으로 쪼갠다
- 재시작 복구: 부팅 시 남아 있는 `CALCULATING` 행을 다시 처리 대상으로 삼는다
- 테스트에서 끌 수 있도록 프로퍼티 스위치(`app.job.enabled`)

**기술 선택 (오버엔지니어링 방지선)**

| 쓰는 것 | 안 쓰는 것 |
|---|---|
| Spring 내장 `@Scheduled` + DB 상태 컬럼 폴링 | Quartz, Spring Batch |
| 단일 스레드 `ThreadPoolTaskExecutor` | Redis / RabbitMQ / Kafka 등 메시지 큐 |
| `RestClient` (동기 호출) | WebFlux·리액티브 스택 |
| Java 21 가상 스레드는 **웹 요청 처리에만** (`spring.threads.virtual.enabled=true`) | 작업 실행기까지 가상 스레드로 바꾸는 튜닝 |

작업 큐를 DB 한 테이블로 대신하는 것이 이 규모에서 가장 단순하다. 별도 인프라를 추가하지 않는다.

### T5-3. `DepartureCalculation` 도메인
ERD 2.10. `unique(participant_id, course_id)`

| 상태 | 저장 형태 |
|---|---|
| `NOT_REQUESTED` | **행 없음** (별도 상태값을 저장하지 않는다) |
| `CALCULATING` | 대기와 실행을 함께 의미. 별도 job ID를 노출하지 않는다 |
| `READY` | 결과 필드 채움 |
| `STALE` | 출발지·코스 변경 시 |
| `UNAVAILABLE` | 경로 없음 |
| `FAILED` | 짧은 인메모리 재시도 후에도 실패 |

### T5-4. 엔드포인트 33 — `POST .../departure-calculations`
사전 조건 검사 (순서대로)
1. 출발지 미등록 → `422 ORIGIN_REQUIRED`
2. 현재 확정 코스 없음 → `409 RESOURCE_CONFLICT`
3. 첫 만남 장소·시각 없음 → `409 RESOURCE_CONFLICT`

동작
- 같은 참여자·코스 버전이 **이미 `CALCULATING`이면 새 작업을 만들지 않고 같은 `202` 응답**
- 같은 조합의 **저장된 `READY`가 있으면 외부 호출 없이 즉시 `200` + 출발 안내** (캐시가 아니라 도메인 결과 조회)
- 그 외 → `CALCULATING` 행 upsert + `202 Accepted` + `Location: .../departure-guide` + `Retry-After: 2`
- Rate limit: 참여자당 5회/시간

### T5-5. 엔드포인트 32 — `GET .../departure-guide`
- 행이 없으면 `status: NOT_REQUESTED`
- `READY`면 `firstMeeting`, `transit`, `recommendedDepartureAt`, `calculatedAt`, `basis: "CURRENT_TIMETABLE"`
- **`recommendedDepartureAt = 첫 만남 시각 − totalSeconds − 10분`**
- `totalWalkSeconds`는 보조 표시. **계산식에 다시 더하지 않는다**

### T5-6. STALE 전파 실구현
P1에서 no-op으로 둔 `DepartureStaleNotifier`를 실제 구현으로 교체 (BR-012).

| 트리거 | 대상 |
|---|---|
| 참여자 출발지 변경 (엔드포인트 8) | 해당 참여자의 모든 행 |
| 코스 확정 (엔드포인트 29) | 해당 보드 전 참여자의 행 |

### T5-7. 출발지 복호화 경로
`OriginCipher.decrypt`는 **Job Executor 내부와 본인 조회 경로에서만** 호출한다. 복호화된 좌표를 로그·응답·오류 details에 넣지 않는다.

---

## ✅ 검증 게이트

| # | 항목 | 방법 |
|---:|---|---|
| V5-1 | E2E: 출발지 등록 → 계산 요청 → READY | 계약 테스트 (16절 5단계). TMAP은 stub |
| V5-2 | **계산은 명시적 POST로만 발생** | 코스 확정만 수행 후 stub 호출 수 0 (MVP 완료 기준 5) |
| V5-3 | 중복 요청이 작업을 늘리지 않음 | `CALCULATING` 중 POST 3회 → stub 호출 1회, 행 1개 |
| V5-4 | READY 재요청은 외부 미호출 | READY 상태에서 POST → `200`, stub 호출 증가 없음 |
| V5-5 | 출발지 없음 → `422 ORIGIN_REQUIRED` | |
| V5-6 | 확정 코스 없음 → `409` | |
| V5-7 | 경로 없음 → `UNAVAILABLE` | stub이 경로 없음 응답 |
| V5-8 | 재시도 소진 → `FAILED` | stub 500 고정, 상태가 `FAILED`로 안착 |
| V5-9 | 권장 출발시각 계산식 | 단위 테스트: 만남 18:00, totalSeconds 1920 → 17:18 |
| V5-10 | STALE 전파 | 출발지 변경 후 `STALE`, 코스 재확정 후 전원 `STALE` |
| V5-11 | 재시작 복구 | `CALCULATING` 행을 남긴 채 재기동 → 재처리됨 |
| V5-12 | **외부 호출 중 DB 커넥션 미점유** | stub 지연 3초 상황에서 동시 API 요청이 정상 응답 |
| V5-13 | 좌표가 로그·응답에 없음 | 로그 캡처 + 응답 JSON assert |
| V5-14 | 빌드 그린 | `./gradlew build` |

---

## 리스크

| 리스크 | 대응 |
|---|---|
| `@Scheduled`가 테스트에서 돌아 플레이키 발생 | `app.job.enabled=false` 기본, 테스트는 실행기를 **직접 호출**해 결정적으로 검증 |
| 외부 호출을 트랜잭션 안에서 하다 커넥션 고갈 | V5-12로 강제 검증. 코드 리뷰 필수 항목 |
| STALE 전파를 P1/P4 코드에 하드코딩 | `DepartureStaleNotifier` 인터페이스 경유만 허용 |
| 단일 VM 가정이 깨지면 중복 실행 | MVP는 단일 VM 확정(아키텍처 14절). 다중화 시 재설계 필요함을 문서에 남긴다 |
