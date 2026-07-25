# P4 — 코스 초안 · 확정 코스 · 공개 일정

> 선행: P2 · 후행: **P5(필수)** · 병렬: P3, I2와 동시 진행 가능
> 엔드포인트: 27~31, 34 (API명세서 10·12절)

---

## 이 단계의 의미

**MVP 결과물이 처음 눈에 보이는 단계**다. 그리고 P5(출발 안내)는 확정 코스의 `FIRST_MEETING`이 있어야 시작조차 못 하므로 P5 전에 반드시 끝나야 한다.
기술적 난이도는 **낙관적 잠금(If-Match/ETag)** 하나에 몰려 있다.

---

## 작업 목록

### T4-1. 코스 초안 (27~28)
`domain/course/`

- `CourseDraft` 엔티티 — 보드당 1행, `version int`, `stops jsonb` (ERD 2.8)
- 27 `GET /course-draft` — 초안이 없어도 `200` + `{version: 0, stops: []}`. 응답 헤더 `ETag: "draft-{version}"`
- 28 `PUT /course-draft` — **호스트만**, 전체 교체, `version + 1`

**검증 규칙 (전부 애플리케이션 계층)**
1. 장소 1~10개
2. `orderIndex`는 1부터 연속, 중복 없음
3. **1번만 `FIRST_MEETING`**, 나머지는 `MEAL`/`CAFE`/`PLAY`/`ETC`
4. `scheduledAt`은 직전 stop보다 늦음
5. 보드에 등록된 **ACTIVE 장소만** (삭제된 장소 placeId면 `400 INVALID_ARGUMENT`) — 이 규칙은 **저장 시점(PUT/확정)에만** 적용된다. 이미 저장된 초안이 참조하는 장소가 **나중에** 삭제되는 것은 막지 않는다(usage checker는 확정 코스만 보호). `GET /course-draft`는 그런 스톱도 숨기지 않고 그대로 반환하되 `placeDeleted: boolean`으로 표시해 FE가 구분하게 한다. `legs`는 삭제 여부와 무관하게 항상 전체 스톱 기준으로 계산한다(2026-07-23 코드 리뷰 반영, `10-fe-contract-sync.md` 통보 이력 참고)
6. `If-Match` 불일치 → **`412 VERSION_MISMATCH` + 최신 `ETag` 반환**
7. `If-Match` 헤더 자체가 없으면 `400 INVALID_ARGUMENT` (조용한 덮어쓰기 금지)

**응답의 `legs`** — 직선거리 기반 추정
- `straightDistanceMeters`: Haversine
- `estimatedWalkMinutes = round(straightDistanceMeters / 70)`
- `estimated: true` 고정. **실제 도보 경로가 아니며 외부 API를 호출하지 않는다**
- `LegEstimator` 유틸로 분리 → 확정 코스 조회·공개 일정에서 재사용

### T4-2. 확정 코스 (29~31)
- `Course`, `CourseStop` 엔티티 (ERD 2.9). `unique(board_id, version)`, `unique(course_id, order_index)`
- 29 `POST /courses` — **호스트만**. 요청 `{draftVersion}`

**한 트랜잭션에서 수행할 것 (순서 중요)**
1. `draftVersion`이 현재 초안과 다르면 → `409 RESOURCE_CONFLICT`
2. 초안 stops를 다시 검증 (그 사이 장소가 삭제됐을 수 있다)
3. 새 `Course` 생성 — `version = 보드 내 최대 version + 1`. **기존 확정 버전 보존**
4. `CourseStop` 생성 (스냅샷 없이 Place FK 참조)
5. 보드 상태 → `CONFIRMED`
6. **최초 확정이면 공개 토큰 생성** — 추측 불가능한 난수를 `board.public_token`(unique)에 **원문 저장**. 공유 링크를 언제든 다시 보여줘야 하므로 해시하지 않는다
7. **모든 참여자의 출발 안내를 `STALE`로 변경** (P5의 `departure_calculation` 대상. P5 전에는 대상 행이 없어 no-op)
8. 출발 안내 API는 **자동 호출하지 않는다** (MVP 완료 기준 5)

- 30 `GET /courses/current` — 보드의 최대 version 행. 없으면 `404`
- 31 `GET /courses/{courseId}` — 특정 버전
- 두 응답 모두 순서·역할·예정시각 + `legs` 포함. 장소 정보는 **현재 Place 값**을 읽는다

### T4-3. `CoursePlaceUsageChecker`
P2 인터페이스 구현체를 새 파일로 추가. 확정 코스가 참조하는 장소 삭제 시 `409 PLACE_IN_USE` + `details: {courseId, orderIndex}`.

### T4-4. 공개 일정 (34)
`GET /public/schedules/{publicToken}` — **인증 없음**

- 토큰 조회는 `board.public_token` unique 컬럼으로 직접 검색
- 현재 확정 코스만 반환
- **응답에서 제외**: 참여자, 출발지, 참여 토큰, 댓글, 투표 상세, **내부 `boardId`**
- 보드가 `CLOSED`면 **`404 RESOURCE_NOT_FOUND`** (토큰 존재 여부 비노출)
- `updatedAt` = 코스와 참조 장소들의 `updatedAt` 중 **가장 최근 값**
- MVP에서는 `Cache-Control: no-cache`로 재검증 (ETag 미도입)
- 별도 공개 중지 상태·API 없음

---

## ✅ 검증 게이트

| # | 항목 | 방법 |
|---:|---|---|
| V4-1 | E2E: 순서 저장 → 버전 충돌 → 확정 → 공개 조회 | 계약 테스트 (16절 4단계) |
| V4-2 | **동시 PUT 중 하나만 성공** | 같은 ETag로 동시 2건 → 하나 `200`, 하나 `412 VERSION_MISMATCH` + 최신 ETag |
| V4-3 | `If-Match` 없는 PUT 거부 | `400 INVALID_ARGUMENT` |
| V4-4 | 초안 검증 규칙 6종 | 위반마다 `400` 케이스 (연속성/중복/FIRST_MEETING 2개/시각 역전/삭제된 장소/11개) |
| V4-5 | `legs` 계산식 | 단위 테스트: 알려진 두 좌표의 Haversine과 `round(d/70)` 일치 |
| V4-6 | 확정 시 이전 버전 보존 | 2회 확정 후 `course` 2행, `courses/{v1}` 조회 가능 |
| V4-7 | 확정 시 보드 상태 CONFIRMED | 보드 조회로 확인 |
| V4-8 | 최초 확정만 공개 토큰 생성 | 2회 확정 후에도 `public_token` 동일 |
| V4-9 | draftVersion 불일치 → `409` | 낡은 버전으로 확정 시도 |
| V4-10 | 코스 포함 장소 삭제 → `409 PLACE_IN_USE` | `details`에 courseId·orderIndex 포함 |
| V4-11 | **공개 응답 민감정보 부재** | 응답 JSON에서 `participant`, `origin`, `token`, `boardId`, `comment`, `vote` 키 부재 assert |
| V4-12 | CLOSED 보드 공개 조회 → `404` | 종료 후 조회 |
| V4-13 | 공개 API는 인증 없이 200 | Authorization 헤더 없이 호출 |
| V4-14 | 빌드 그린 | `./gradlew build` |

---

## 리스크

| 리스크 | 대응 |
|---|---|
| ETag 형식 불일치로 FE와 어긋남 | 형식을 `"draft-{n}"`으로 문서·테스트에 고정. [`10-fe-contract-sync.md`](10-fe-contract-sync.md)에 명시 |
| 확정 트랜잭션 중간 실패로 보드만 CONFIRMED | 단일 `@Transactional`. V4-6~V4-8로 검증 |
| 확정 후 장소가 수정되어 공개 일정이 바뀜 | **의도된 동작**(API명세서 12절). 스냅샷을 추가하지 말 것 |
| 공개 토큰 원문이 로그에 남음 | P0 마스킹 필터가 `/public/schedules/*`를 마스킹하는지 테스트 |
