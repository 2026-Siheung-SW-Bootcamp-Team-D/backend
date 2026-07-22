# P6 — 만나기 좋은 지역 찾기 (ODsay + JTS + 비동기 작업)

> 선행: P1(출발지), **P5(Job Executor 뼈대)**, I1(ODsay 고정 IP 등록 — 운영 검증용)
> 엔드포인트: 25~26 (API명세서 9절)
> **가장 복잡한 단계. 마지막에 한다.**

---

## 왜 마지막인가

1. 외부 API 3종(ODsay·Kakao·TMAP)을 한 작업 안에서 순차 호출한다
2. 4단계 파이프라인 + 진행률 + 재시도 + 재시작 복구가 전부 필요하다
3. **ODsay는 고정 IP 등록이 선행**되어야 운영에서 동작한다(I1 산출물)

단 **선행 없이 미리 할 수 있는 부분이 있다** → Wave 2 레인 C: JTS 폴리곤 유틸과 ODsay 어댑터를 **DB·Job 연동 없이 단위 테스트로** 먼저 완성해 둔다.

---

## 작업 목록

### T6-1. JTS 폴리곤 유틸 (선행 없음, 조기 착수 가능)
`org.locationtech.jts:jts-core` 추가.

- GeoJSON(Polygon/MultiPolygon) ↔ JTS Geometry 변환
- N개 도달권의 **교집합** 계산
- 면적(km²) 계산 — WGS84 좌표를 그대로 면적 계산에 쓰지 말고 적절한 투영 또는 구면 면적 공식 사용
- 결과 조각 중 **면적 상위 3개만** 반환
- 포함 여부 판정(후보 좌표가 교집합 안에 있는가)
- **PostGIS 사용 금지** — 연산은 앱, 저장은 JSONB (ERD 4절)

### T6-2. ODsay 도달권 어댑터 (선행 없음, 조기 착수 가능)
- 입력: 출발 좌표, `durationMin`(30/45/60)
- 출력: WGS84 GeoJSON 폴리곤
- P2 공통 기반의 429 백오프·오류 매핑 재사용
- **고정 IP 필요**: 로컬 개발에서는 stub, 운영 검증은 VM에서만 (아키텍처 1절)

### T6-3. `AreaSearchJob` 도메인
ERD 2.6·2.7. 상태 `QUEUED` → `RUNNING` → (`RETRY_WAIT`) → `SUCCEEDED`/`FAILED`

- `snapshot jsonb` — **대상 참여자 ID 목록만** 저장한다. 예: `{"participantIds":["ptc_01H...","ptc_02H..."]}`
  - `durationMin`은 `duration_min` 컬럼에 있으므로 snapshot에 중복 저장하지 않는다
  - **출발 좌표를 복사하지 않는다.** 좌표는 암호화 저장 대상인데 여기에 복사하면 암호화를 우회하는 두 번째 저장소가 생긴다 (ERD 설계 원칙 6)
  - 좌표는 실행 시점에 `participant.origin_ciphertext`에서 복호화해 읽고 **메모리에서만** 사용한다

**입력 좌표 일관성 — 변경 잠금으로 보장 (리뷰 결정 #1)**

작업이 4개 phase를 도는 몇 분 사이에 출발지가 바뀌면 한 결과 안에서 단계별 기준이 섞인다. 이를 좌표 복사 대신 **변경 잠금**으로 막는다.

- 보드에 활성 지역 찾기 작업(`QUEUED`/`RUNNING`/`RETRY_WAIT`)이 있는 동안, 그 작업 대상 참여자의 출발지 변경(엔드포인트 8)을 **`409 RESOURCE_CONFLICT`로 거부**한다 (P1의 `PATCH participants/me`에서 검사)
- 따라서 어느 phase에서 읽어도 좌표가 동일하고, 재시작 후 재실행도 같은 입력을 쓴다 — 좌표를 저장하지 않고 일관성을 얻는다
- 보드당 활성 작업은 하나뿐이므로 잠금 판정이 단순하다
- `progress jsonb` — `{phase, done, total}`
- `result jsonb` — 교집합 GeoJSON + 요약
- `AreaCandidate` — `metrics jsonb`, `reasons jsonb`, `rank 1~3`

### T6-4. 엔드포인트 25 — `POST /area-search-jobs`
**호스트 전용.** 검증 순서

**잠금 순서 (좌표 고정의 핵심, 2차 blocker #1)**: 검증 전에 **대상 참여자 행들을 ID 오름차순으로 `SELECT … FOR UPDATE`** 로 잠근다 → 출발지 검증 → job 생성 → 커밋. 이렇게 해야 동시에 들어온 출발지 `PATCH`(엔드포인트 8, 같은 행을 `FOR UPDATE`)와 직렬화되어 "생성 시점 좌표 고정"이 실제로 성립한다. 단순 exists 조회로는 막을 수 없다. (잠금 계약 전문은 계획 02 T1-4b)

1. `durationMin` ∈ {30, 45, 60}, 대상 **최소 2명**
2. 출발지 미등록자 포함 → `422 ORIGIN_REQUIRED`
3. 같은 보드의 활성 작업(`QUEUED`/`RUNNING`/`RETRY_WAIT`) 존재 → **`409 JOB_ALREADY_RUNNING`**
   - 부분 unique 인덱스 위반 예외를 변환하는 방식으로 처리 (선조회만으로는 경쟁 상태를 못 막는다)
4. 일일 예산 초과 → `503 QUOTA_EXCEEDED`

응답 `202` + `Location` + `Retry-After: 2` + `estimatedExternalCalls{odsay, kakaoLocal, tmapTransit}`
Rate limit: **보드당 3회/시간**

### T6-5. 작업 실행 파이프라인 (P5의 Job Executor 재사용)
`SELECT ... FOR UPDATE SKIP LOCKED`로 **한 작업만 선점** → 즉시 `RUNNING` 저장하고 트랜잭션 종료 → 이후 외부 호출은 트랜잭션 밖에서.

선점 직후 대상 참여자의 출발지를 복호화한다. **출발지 변경은 잠겨 있지만(위), 참여자 비활성화 등으로 좌표가 사라진 예외 상황이면 작업을 `FAILED`로 종료**한다(사유 `ORIGIN_REQUIRED`). 이미 `202`를 반환한 뒤이므로 HTTP 오류가 아니라 작업 종료 상태로 처리한다.

| phase | 내용 | 실패 시 |
|---|---|---|
| `ISOCHRONE` | 참여자 수만큼 ODsay 도달권 | 재시도 소진 → `EXTERNAL_UNAVAILABLE` |
| `INTERSECTION` | JTS 교집합, 면적 상위 3조각 | 교집합 없음 → `NO_INTERSECTION` |
| `HUB_COLLECTION` | Kakao Local로 역·기차역·터미널·시청·시장 후보 수집 | 후보 없음 → `NO_HUB_FOUND` |
| `TRANSIT_EVALUATION` | **최대 6개 후보** × 참여자 수 TMAP Transit 평가 | 일부 참여자 경로 없음 → **작업은 계속**, `unreachableCount` 증가 |

- 각 phase 종료 시 `progress`를 짧은 트랜잭션으로 갱신
- 평가 지표: `avgSeconds`, `maxSeconds`, `transferAvg`, `unreachableCount`
- 상위 3개를 `rank` 1~3으로 저장하고 `reasons` 문자열 배열 생성(예: "평균 이동시간이 가장 짧음")
- 재시도: `RETRY_WAIT` + `next_retry_at` 저장 (P5의 인메모리 재시도와 **다른 모델**임에 주의)
- 재시작 복구: 오래된 `RUNNING`을 `QUEUED`로 되돌린다

### T6-6. 엔드포인트 26 — `GET /area-search-jobs/{jobId}`
- 진행 중: `status` + `progress`, `result: null`, `error: null`
- 성공: `result{durationMin, intersection{type, coordinates, areaKm2, usedPieces}, candidates[...]}`
- 실패: `error.code` ∈ {`NO_INTERSECTION`, `NO_HUB_FOUND`, `EXTERNAL_UNAVAILABLE`, `ORIGIN_REQUIRED`}
  - `ORIGIN_REQUIRED`의 두 얼굴 구분(2차 blocker #2): **POST 접수 시점 검증 실패는 HTTP `422 ORIGIN_REQUIRED`** (동기 응답), **접수 후 실행 시점에 좌표가 사라진 불변식 소실은 job의 `FAILED` + `error.code: ORIGIN_REQUIRED`** (비동기 종료 상태). 같은 코드지만 전달 채널이 다르다
- FE는 2초 간격 폴링 (SSE·푸시 없음 — MVP 완료 기준 10)

---

## ✅ 검증 게이트

| # | 항목 | 방법 |
|---:|---|---|
| V6-1 | E2E: 생성 → 폴링 → 성공 | 계약 테스트 (16절 6단계). 외부 3종 stub |
| V6-2 | 교집합 없음 → `NO_INTERSECTION` | 겹치지 않는 폴리곤 stub |
| V6-3 | 외부 장애 → `EXTERNAL_UNAVAILABLE` | ODsay stub 500 고정 |
| V6-4 | 허브 없음 → `NO_HUB_FOUND` | Kakao stub 빈 결과 |
| V6-5 | **활성 작업 중복 불가** | 동시 POST 2건 → 하나 `202`, 하나 `409 JOB_ALREADY_RUNNING` |
| V6-6 | 출발지 미등록 포함 → `422 ORIGIN_REQUIRED` | |
| V6-7 | 대상 1명 → `400 INVALID_ARGUMENT` | |
| V6-8 | **호출량 상한 준수** | 후보 6개 초과 평가 안 함, TMAP 호출 수 ≤ 참여자수×6 |
| V6-9 | 일부 경로 없음에도 작업 성공 | 한 참여자만 경로 없음 stub → `SUCCEEDED`, `unreachableCount ≥ 1` |
| V6-10 | JTS 교집합·면적 정확도 | 단위 테스트: 알려진 사각형 2개의 교집합 면적 |
| V6-11 | 재시작 복구 | `RUNNING` 행을 남기고 재기동 → `QUEUED`로 복구 |
| V6-12 | 진행률 단계 순서 | `ISOCHRONE → INTERSECTION → HUB_COLLECTION → TRANSIT_EVALUATION` |
| V6-13 | **snapshot에 좌표 없음** | `area_search_job.snapshot` JSONB에 `lon`/`lat` 키가 존재하지 않음을 assert |
| V6-16 | **활성 작업 중 출발지 변경 차단** | 작업 활성 상태에서 대상 참여자 `PATCH participants/me` 출발지 변경 → `409 RESOURCE_CONFLICT`, 종료 후 변경 성공 |
| V6-17 | **동시 POST/PATCH 직렬화** | 지역 찾기 POST와 출발지 PATCH를 동시에 실행 → 하나가 먼저 확정되고, job이 생성됐다면 그 PATCH는 `409`. 두 순서 모두 테스트해 좌표 고정이 깨지지 않음을 검증 (통합 테스트) |
| V6-18 | **비동기 ORIGIN_REQUIRED** | POST 검증 실패 → 동기 `422 ORIGIN_REQUIRED`. 접수 후 좌표 소실 → job `FAILED` + `error.code: ORIGIN_REQUIRED` |
| V6-14 | **운영 스모크: ODsay 고정 IP** | 운영 VM에서 실제 ODsay 호출 성공 (I1의 IP 등록 후) |
| V6-15 | 빌드 그린 | `./gradlew build` |

---

## 리스크

| 리스크 | 대응 |
|---|---|
| ODsay 고정 IP 미등록으로 운영에서만 실패 | I1에서 IP 등록을 **먼저** 끝내고 V6-14를 배포 직후 수행 |
| 외부 호출 폭증으로 예산 초과 | `estimatedExternalCalls`를 생성 시점에 계산해 예산 검사, 보드당 3회/시간 제한 |
| WGS84 좌표로 면적을 계산해 값이 틀림 | V6-10 단위 테스트에 실제 km² 기대값 명시 |
| 4단계 파이프라인이 한 클래스에 뭉침 | phase별 클래스 분리 + 각 phase 단위 테스트 |
| 장시간 작업이 트랜잭션을 점유 | 선점·진행률·결과 저장을 **각각 별개 짧은 트랜잭션**으로 |
