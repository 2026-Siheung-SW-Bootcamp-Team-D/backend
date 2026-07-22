# P3 — 댓글 · 투표

> 선행: P2 · 후행: 없음(리프) · 병렬: **P4, I2와 동시 진행 가능**
> 엔드포인트: 16~24 (API명세서 7.4·8절)

---

## 이 단계의 의미

외부 API 의존이 **전혀 없는** 순수 도메인 단계다. 그래서 P4·I2와 안전하게 병렬 진행할 수 있다. 난이도는 낮지만 **투표 교체(PUT)의 트랜잭션 처리**와 **부분 unique 인덱스 경쟁 상태**가 함정이다.

---

## 작업 목록

### T3-1. 댓글 (16~19)
`domain/comment/`

- `PlaceComment` 엔티티 (ERD 2.4). `body`는 공백 제거 후 1~500자, soft delete
- 16 `GET .../comments` — 페이지네이션, 삭제 댓글 제외
- 17 `POST .../comments` — `201`. Rate limit 참여자당 20회/분
- 18 `PATCH .../comments/{id}` — **작성자만**
- 19 `DELETE .../comments/{id}` — **작성자 또는 호스트**, `204`
- 일반 텍스트로 저장하고 **서버에서 HTML로 렌더링하지 않는다** (API명세서 15-3). 서버는 저장·반환만, 렌더링 안전은 FE 책임

### T3-2. 댓글 수 반영
P2의 `GET /places` 응답 `commentCount`를 실제 값으로 채운다.
→ N+1 방지: `place_id IN (...)` 단일 집계 쿼리로 카운트를 가져와 매핑. 엔티티 컬렉션 순회 금지.
→ `sort=COMMENTS` 정렬도 이 집계를 사용한다.

### T3-3. 투표 (20~24)
`domain/vote/`

- `Vote`, `VoteOption`, `VoteBallot` 엔티티 (ERD 2.5)
- 20 `POST /votes` — **호스트만**. 후보 2~10개, `maxSelections`는 1 이상 후보 수 이하, `anonymous`는 생성 시 고정, `closesAt` 필수
  - **보드당 열린 투표 1개**: 부분 unique 인덱스가 1차 방어. 위반 예외를 `409 RESOURCE_CONFLICT`로 변환한다 (선조회 후 삽입만으로는 경쟁 상태를 못 막는다)
  - 후보는 해당 보드의 `ACTIVE` 장소만
- 21 `GET /votes` — `status=OPEN|CLOSED` 필터 + 페이지네이션
- 22 `GET /votes/{voteId}` — 집계 포함. **`anonymous=true`면 투표자 신원을 응답에 절대 포함하지 않는다** (집계 수치만)
- 23 `PUT .../ballots/me` — 내 표 전체 교체
  - **한 트랜잭션 안에서** `(vote_id, participant_id)` 전체 delete → insert
  - 빈 배열 = 투표 취소
  - 같은 본문 반복 호출 시 같은 결과 (멱등)
  - `placeIds` 개수 > `maxSelections` → `400 INVALID_ARGUMENT`
  - 마감(`closesAt` 경과) 또는 `CLOSED` → `409 RESOURCE_CONFLICT`
- 24 `PATCH /votes/{voteId}` — 호스트 조기 종료. **이미 CLOSED에 같은 요청 → `200 OK`로 현재 투표 반환** (멱등, 409 아님)

### T3-4. `VotePlaceUsageChecker`
P2의 `PlaceUsageChecker` 구현체를 **새 파일로** 추가. 열린 투표가 참조하는 장소 삭제 시 `409 PLACE_IN_USE`.

---

## ✅ 검증 게이트

| # | 항목 | 방법 |
|---:|---|---|
| V3-1 | E2E: 댓글 작성 → 투표 생성 → 참여 → 종료 | 계약 테스트 (16절 3단계) |
| V3-2 | 댓글 권한 | 타인 수정 → `403`, 호스트 삭제 → `204`, 작성자 삭제 → `204` |
| V3-3 | 삭제 댓글이 목록에서 제외 | soft delete 후 목록 count 감소 |
| V3-4 | **열린 투표 2개 생성 불가** | 동시 요청 2건 → 하나만 `201`, 나머지 `409 RESOURCE_CONFLICT` |
| V3-5 | 투표 PUT 멱등 | 같은 본문 3회 → ballot 행 수 동일 |
| V3-6 | 투표 취소 | 빈 배열 PUT → 내 ballot 0행, 집계 반영 |
| V3-7 | `maxSelections` 초과 거부 | `400 INVALID_ARGUMENT` |
| V3-8 | **익명 투표에서 신원 미노출** | `anonymous=true` 상세 응답 JSON에 `participantId` 키 부재 assert |
| V3-9 | 마감 후 투표 시도 → `409` | `closesAt`을 과거로 두고 PUT |
| V3-10 | 투표된 장소 삭제 → `409 PLACE_IN_USE` | `details`에 voteId 포함 |
| V3-11 | 목록 조회 N+1 없음 | 쿼리 카운트 assert (장소 20개 조회 시 쿼리 수 상수) |
| V3-12 | 빌드 그린 | `./gradlew build` |

---

## 리스크

| 리스크 | 대응 |
|---|---|
| 선조회 후 insert로 열린 투표 중복 발생 | 부분 unique 인덱스 + 예외 변환. **V3-4를 동시 실행 테스트로 작성** |
| ballot 교체 중 부분 실패로 표 유실 | 단일 `@Transactional` 메서드. delete 후 flush → insert |
| 익명 투표 신원 유출 | 응답 DTO를 익명/실명 **분리 타입**으로 만들어 컴파일 시점에 차단 |
