# P1 — 보드 · 초대 · 참여자

> 선행: P0 · 후행: P2, P6 · 병렬: I0(컨테이너화), 외부 어댑터 골격과 동시 진행 가능
> 엔드포인트: 1~8 (API명세서 5절)

---

## 이 단계의 의미

**인증 체계가 실제로 도는 첫 단계**다. 여기서 참여 토큰을 발급하지 못하면 이후 어떤 API도 손으로 테스트할 수 없다. 그래서 P2보다 반드시 먼저 끝낸다.

---

## 작업 목록

### T1-1. 도메인 엔티티
`domain/board/`

- `Board` — `publicId`, `name`, `dateStart`, `dateEnd`, `purpose`, `status`, `inviteCode`, `inviteExpiresAt`, `publicToken`
  - 상태 전이 메서드: `confirm()`, `close()`. `BoardStatus` = `COLLECTING` / `CONFIRMED` / `CLOSED`
  - `publicToken`은 최초 확정 시(P4) 채워지므로 여기서는 nullable로만 둔다
- `Participant` — `publicId`, `board`, `nickname`, `role(HOST/MEMBER)`, `tokenHash`, `avatarColor`, `active`, `origin*` 4필드
  - 의도가 드러나는 메서드: `rename()`, `changeOrigin()`, `deactivate()`
  - JPA 엔티티는 일반 `class`, `data class` 금지 (AGENTS.md)

### T1-2. 초대 코드
- 코드 생성: 8~10자 URL-safe 랜덤 (혼동 문자 제외)
- **원문을 `board.invite_code`(unique)에 저장**하고 조회는 그 컬럼으로 직접 한다
  - 이유: 엔드포인트 4(`GET /invitation`)가 호스트에게 코드·URL 원문을 다시 보여줘야 한다. HMAC은 역변환이 불가능해 이 화면을 만들 수 없다
  - 보호는 **추측 불가능한 난수 + 만료 + IP당 30회/분 rate limit**으로 한다
- 만료: `invite_expires_at`이 지나면 `404 INVITE_NOT_FOUND` (만료와 부재를 구분하지 않는다)
- 로그에는 코드 원문을 남기지 않는다 (P0 마스킹 필터)

### T1-3. 엔드포인트

| # | 엔드포인트 | 핵심 규칙 |
|---:|---|---|
| 1 | `POST /boards` | 인증 없음. 보드 + HOST 참여자를 **한 트랜잭션에서** 생성하고 `participantToken` 반환. `name` 2~40자, `dateEnd >= dateStart` |
| 2 | `GET /boards/{boardId}` | 참여자. 보드 범위 검증 |
| 3 | `PATCH /boards/{boardId}` | 호스트. 정보 수정 + `status: CLOSED` 종료 |
| 4 | `GET /boards/{boardId}/invitation` | 호스트. 코드·URL·만료시각. **재발급 API 없음** |
| 5 | `GET /invitations/{inviteCode}` | 인증 없음. `boardId`, `boardName`, `participantCount`, `joinable`, `expiresAt`. IP당 30회/분 |
| 6 | `POST /invitations/{inviteCode}/participants` | 인증 없음. `201` + `participantToken`. 닉네임 1~20자, **중복 허용** |
| 7 | `GET /boards/{boardId}/participants` | 참여자. **타인 출발지는 `registered`만**, 본인은 label+좌표까지. 페이지네이션 없음 |
| 8 | `PATCH /boards/{boardId}/participants/me` | 참여자. 닉네임/출발지 수정. `origin.source` ∈ {`KAKAO_KEYWORD`,`KAKAO_ADDRESS`,`MANUAL_PIN`}. 출발지 변경 시 해당 참여자 출발 안내 `STALE`(P5 연동 지점). **이 참여자가 대상인 활성 지역 찾기 작업이 있으면 출발지 변경을 `409 RESOURCE_CONFLICT`로 거부**(P6 좌표 일관성 잠금, 리뷰 결정 #1) |

### T1-4. 보드 종료(CLOSED) 가드
`CLOSED` 보드에 대한 **모든 쓰기 요청**은 `409 RESOURCE_CONFLICT` (API명세서 15-7).
→ if문을 흩뿌리지 말고 **`@RequiresBoardOpen` 어노테이션 + 인터셉터**로 한 곳에서 처리한다. 이후 P2~P6가 그대로 재사용한다.

### T1-4b. 출발지 변경 잠금 훅 (P6 좌표 일관성)
`area_search_job` 테이블은 P0 baseline에 이미 존재하므로 P6 로직 없이도 조회할 수 있다. `PATCH participants/me`의 출발지 변경 경로에서 활성 작업(`QUEUED`/`RUNNING`/`RETRY_WAIT`)을 확인해 있으면 `409 RESOURCE_CONFLICT`. P1에서는 항상 false(작업 생성 API가 아직 없음)지만 검사 지점을 지금 만들어 두어 P6가 붙었을 때 자동으로 동작하게 한다.

> ⚠️ **단순 `exists` 조회는 잠금이 아니다.** 아래 race가 가능하다: ① PATCH가 "활성 작업 없음" 확인 → ② POST가 기존 좌표로 검증하고 job 생성 → ③ PATCH가 좌표 변경 → 둘 다 커밋. 그러면 "생성 시점 좌표 고정"이 깨진다. 따라서 **참여자 행 잠금(`SELECT … FOR UPDATE`)** 으로 두 경로를 직렬화한다 (리뷰 결정, 2차 blocker #1).

**잠금 순서 계약 (두 경로 공통)**

| 경로 | 순서 |
|---|---|
| 출발지 PATCH (엔드포인트 8) | ① 본인 `participant` 행 `SELECT … FOR UPDATE` → ② 그 참여자가 대상인 활성 job 조회 → ③ 있으면 `409`, 없으면 좌표 변경 → ④ 커밋 |
| 지역 찾기 POST (P6 엔드포인트 25) | ① 대상 참여자 행들을 **ID 오름차순**으로 `SELECT … FOR UPDATE` → ② 출발지 검증 → ③ job `QUEUED` 생성 → ④ 커밋 |

- **두 경로 모두 대상 참여자 행을 먼저 잠근다.** 다수 참여자는 항상 ID 정렬 순서로 잠가 데드락을 피한다
- 한 참여자가 서로 다른 트랜잭션에 동시에 들어오면 행 잠금으로 한쪽이 대기 → 먼저 커밋한 쪽이 이기고 다른 쪽은 갱신된 상태를 본다
- P1에서는 PATCH 쪽 잠금·검사 지점만 구현하고, POST 쪽 잠금은 P6에서 같은 계약으로 구현한다

### T1-5. 출발지 STALE 전파 훅
P5가 아직 없으므로 `DepartureStaleNotifier` 인터페이스만 정의하고 no-op 구현을 등록한다. P5에서 실구현으로 교체한다. (병렬 작업 충돌 방지)

### T1-6. Rate limit 적용
- 참여 토큰 전체 60회/분
- 초대 코드 확인 IP당 30회/분

---

## ✅ 검증 게이트

| # | 항목 | 방법 |
|---:|---|---|
| V1-1 | E2E: 생성 → 초대 확인 → 참여 → 보드 조회 | 계약 테스트 1개로 전 흐름 (API명세서 16절 1단계) |
| V1-2 | 발급 토큰으로 보호 API 접근 성공 | `POST /boards` 응답 토큰으로 `GET /boards/{id}` → 200 |
| V1-3 | **다른 보드 토큰으로 접근 시 404** | 보드 A 토큰으로 보드 B 조회 → `404 RESOURCE_NOT_FOUND` (403 아님) |
| V1-4 | 호스트 전용 API를 MEMBER가 호출 → 403 | `PATCH /boards/{id}` → `403 FORBIDDEN` |
| V1-5 | 만료 초대 코드 → `404 INVITE_NOT_FOUND` | 만료 시각을 과거로 세팅 후 호출 |
| V1-6 | 타인 출발지 좌표 미노출 | 참여자 목록 응답 JSON에 타인의 `lon`/`lat`/`label` 키가 **아예 없음**을 assert |
| V1-7 | CLOSED 보드 쓰기 차단 | 종료 후 `PATCH /participants/me` → `409 RESOURCE_CONFLICT` |
| V1-8 | 토큰이 응답·로그에 1회만 등장 | 생성/참여 응답 외 모든 응답에 `participantToken` 없음. 로그에 secret 미출력 확인 |
| V1-9 | 빌드 그린 | `./gradlew build` |

---

## 리스크

| 리스크 | 대응 |
|---|---|
| 보드 범위 검증을 컨트롤러마다 중복 구현 | P0 공통 검증기를 반드시 경유. PR 리뷰 체크 항목 |
| 초대 코드가 추측 가능해 무단 참여 | 8~10자 URL-safe 난수(충분한 엔트로피) + 만료 + IP당 30회/분 제한. 순번·짧은 숫자 코드 금지 |
| `POST /boards`의 보드+HOST 생성이 분리 트랜잭션 | 서비스 메서드 하나에 `@Transactional` |
