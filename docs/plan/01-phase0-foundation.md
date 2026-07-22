# P0 — 기반 공사 (Foundation)

> 선행: 없음 · 후행: **모든 단계** · 병렬 가능: ❌ (전부 직렬, 한 사람이 끝내고 머지)
> 목표: "도메인 코드를 한 줄도 안 썼지만, 쓰기만 하면 되는 상태"

---

## 왜 이 단계가 먼저인가

현재 H2 인메모리 + 인증 없음 상태다. 여기서 도메인부터 만들면 나중에 스키마·인증·public_id 규약을 전부 갈아엎게 된다. P0 산출물은 이후 모든 단계가 공유하므로 **다른 작업과 병렬로 하지 않는다.**

---

## 작업 목록

### T0-1. 빌드 의존성 교체
`build.gradle.kts` 수정.

| 추가 | 용도 |
|---|---|
| `org.postgresql:postgresql` (runtimeOnly) | 운영 DB 드라이버 |
| `org.flywaydb:flyway-core`, `flyway-database-postgresql` | 스키마 버전 관리 |
| `org.springframework.boot:spring-boot-starter-security` | Bearer 토큰 필터·권한 (로그인용 아님) |
| `org.testcontainers:postgresql`, `spring-boot-testcontainers` (test) | 실제 PostgreSQL 대상 테스트 |
| `io.micrometer:micrometer-registry-prometheus` | 메트릭 (I3에서 활용) |

| 제거 | 이유 |
|---|---|
| `spring-boot-h2console`, `com.h2database:h2` | PostgreSQL 전용 전환. JSONB·부분 인덱스 동작 차이 때문에 **H2 병행 금지** |

> JTS(`org.locationtech.jts:jts-core`)는 P6에서 추가한다. 지금 넣으면 미사용 의존성이 된다.

> **외부 HTTP 클라이언트는 `RestClient`** 를 쓴다. Spring Framework 7에 내장되어 있고 `spring-boot-starter-webmvc`에 이미 포함되므로 **의존성을 추가하지 않는다.** `WebClient`는 WebFlux 스택을 끌어들이므로 사용하지 않는다.

### T0-2. 로컬 개발 환경
- `docker-compose.local.yml` — PostgreSQL 16, 포트 5432, 볼륨 1개
- `application-local.yml` — 로컬 DB 접속, `flyway.enabled=true`, 개발용 더미 키
- `application.yml` — 공통(Jackson, UTC 타임존, Actuator 노출 범위)
- `application-prod.yml` — **값이 아니라 환경변수 참조만** (`${DB_PASSWORD}` 형태)
- `.env` (git 무시) + `.env.example` (**변수 이름만**, 실제 키 금지)
  - 변수: `DB_PASSWORD`, `TOKEN_PEPPER`, `ORIGIN_ENC_KEY`, `KAKAO_REST_KEY`, `ODSAY_KEY`, `TMAP_APP_KEY`
  - `docker-compose.local.yml`의 `env_file`로 주입
- `README.md`에 로컬 기동 절차 추가

> 애플리케이션은 로컬·운영 모두 **환경변수만 읽는다.** 값을 어디서 가져오는지(`.env` vs Secret Manager)는 실행 환경의 책임이며 코드에 분기를 만들지 않는다.

### T0-3. Flyway 베이스라인 마이그레이션
`src/main/resources/db/migration/V1__baseline.sql` **한 파일**에 ERD_v1.0 전체 스키마를 작성한다.
단계별로 쪼개지 않는 이유: 아직 배포 전이라 V1~V10 분할은 리뷰만 어렵게 한다. **최초 운영 배포 이후부터는 반드시 새 버전 파일로만 변경**한다.

포함 대상 (ERD 2·3절 전체):
- 테이블: `board`, `participant`, `place`, `place_comment`, `vote`, `vote_option`, `vote_ballot`, `area_search_job`, `area_candidate`, `course_draft`, `course`, `course_stop`, `departure_calculation`
- 공통 컬럼: `id bigint generated always as identity`, `created_at`/`updated_at timestamptz not null default now()`
- 전 FK 컬럼 인덱스 (PostgreSQL은 자동 생성하지 않음)
- 부분 unique 인덱스 2개: `vote(board_id) where status='OPEN'`, `area_search_job(board_id) where status in ('QUEUED','RUNNING','RETRY_WAIT')`
- ERD 3절 조회 패턴 인덱스 전부
- ENUM 타입·트리거·PostGIS **사용 금지** (ERD 4절)

### T0-4. 공통 영속 계층
- `global/persistence/BaseEntity.kt` — `@MappedSuperclass`, id/createdAt/updatedAt, `@EntityListeners(AuditingEntityListener::class)`
- `@EnableJpaAuditing` 설정
- `global/id/PublicId.kt` — ULID 생성 + 접두사(`brd_`, `ptc_`, `plc_`, `cmt_`, `vot_`, `job_`, `arc_`, `crs_`) 유틸. 접두사는 enum으로 고정
- JSONB 매핑 유틸 (`stops`, `snapshot`, `result`, `metrics`, `reasons`, `progress`) — Hibernate 6 `@JdbcTypeCode(SqlTypes.JSON)`

### T0-5. 인증·인가 기반
API명세서 2.1절 구현.

- `global/auth/ParticipantToken.kt` — `participantPublicId.secret` 파싱/생성. secret은 256비트 `SecureRandom`
- `global/auth/TokenHasher.kt` — `HMAC-SHA-256(serverPepper, secret)`. pepper는 `@ConfigurationProperties`로 주입
  - **적용 대상은 참여 토큰 secret뿐이다.** 초대 코드와 공개 토큰은 원문으로 저장한다 — 호스트 초대 화면과 공유 링크에서 원문을 다시 보여줘야 하므로 단방향 해시를 쓸 수 없다 (ERD 설계 원칙 5)
- `global/auth/ParticipantAuthFilter.kt` — Bearer 헤더 → 참여자 조회 → 해시 비교 → `SecurityContext`에 `ParticipantPrincipal(participantId, boardId, role)` 저장
- `SecurityConfig.kt` — stateless, CSRF off, 인증 불필요 경로 화이트리스트(`POST /boards`, `GET /invitations/{code}`, `POST /invitations/{code}/participants`, `GET /public/schedules/{token}`, `/actuator/health`)
- `@CurrentParticipant` argument resolver
- **보드 범위 검증기**: 경로 `{boardId}`와 principal의 boardId가 다르면 `404 RESOURCE_NOT_FOUND` (403이 아님 — 보드 존재 여부 노출 방지)
- 호스트 전용 검사 유틸 → 위반 시 `403 FORBIDDEN`

> 인증 실패는 전부 `401 AUTHENTICATION_REQUIRED`로 수렴시킨다. "형식 오류"와 "해시 불일치"를 구분해 응답하지 않는다.

### T0-6. 출발지 좌표 암호화
- `global/crypto/OriginCipher.kt` — AES-GCM. 키는 Secret Manager/환경변수 주입, 96bit 랜덤 IV를 ciphertext 앞에 붙여 `bytea` 한 컬럼에 저장
- 복호화는 **본인 요청 경로에서만** 호출. 타인 조회는 `origin_ciphertext is not null` → `registered: true/false`만

### T0-7. 공통 웹 규약 보강
기존 `global/error`, `RequestIdFilter`를 명세에 맞게 확장한다.

- `ErrorCode`에 API명세서 2.4절 전체 코드 등록 (`INVALID_ARGUMENT`, `URL_QUERY_NOT_ALLOWED`, `AUTHENTICATION_REQUIRED`, `FORBIDDEN`, `RESOURCE_NOT_FOUND`, `INVITE_NOT_FOUND`, `RESOURCE_CONFLICT`, `JOB_ALREADY_RUNNING`, `PLACE_IN_USE`, `VERSION_MISMATCH`, `ORIGIN_REQUIRED`, `RATE_LIMITED`, `EXTERNAL_BAD_RESPONSE`, `EXTERNAL_UNAVAILABLE`, `QUOTA_EXCEEDED`)
  - `ROUTE_UNAVAILABLE`은 등록하지 않는다 — 경로 없음은 출발 안내의 `UNAVAILABLE` 상태로만 표현한다 (리뷰 결정 #5)
- 페이지네이션 공통 응답 `PageResponse<T>` (`items` + `page{number,size,totalItems,totalPages}`, size 기본 20 / 최대 50, page 1-base)
- `Cache-Control: private, no-store` 인터셉터 (인증 경로 전체)
- CORS: 프로필별 허용 오리진 목록만. **운영 `*` 금지**
- 로그 마스킹: 경로의 초대 코드·공개 토큰 마스킹, 토큰·좌표·검색어 원문 로깅 금지

### T0-8. Rate limit 골격
API명세서 2.6절 표. 외부 의존성 없이 인메모리 토큰 버킷으로 시작한다(단일 VM이라 분산 카운터 불필요).
키: 참여자별 / 보드별 / IP별. 초과 시 `429 RATE_LIMITED` + `Retry-After`.

> P0에서는 **어노테이션 + 인터셉터 골격**만. 항목별 실제 한도는 해당 API 구현 단계에서 붙인다.

---

## ✅ 검증 게이트 (P0 완료 조건)

| # | 검증 항목 | 방법 |
|---:|---|---|
| V0-1 | Docker PostgreSQL 기동 후 앱이 뜬다 | `docker compose -f docker-compose.local.yml up -d` 후 `./gradlew bootRun --args='--spring.profiles.active=local'` |
| V0-2 | Flyway V1 적용, 전 테이블·인덱스 생성 | `flyway_schema_history` 1행 + `\d+`로 부분 unique 인덱스 2개 확인 |
| V0-3 | health check 성공 | `GET /actuator/health` → `200 {"status":"UP"}` (DB 컴포넌트 포함) |
| V0-4 | 인증 없는 요청이 401로 수렴 | 보호 경로에 토큰 없이 / 잘못된 토큰 → 둘 다 `401 AUTHENTICATION_REQUIRED` |
| V0-5 | 토큰 해시 왕복 | 단위 테스트: 생성 secret의 HMAC이 저장값과 일치, 다른 pepper면 불일치 |
| V0-6 | 좌표 암복호 왕복 | 단위 테스트: 복호화 결과가 원본과 일치, 같은 입력의 ciphertext가 매번 다름(IV 랜덤) |
| V0-7 | 오류 응답 계약 | 계약 테스트: `error.code/message/details/requestId` 구조 + 응답 헤더 `X-Request-Id`와 본문 requestId 동일 |
| V0-8 | Testcontainers 테스트 통과 | `./gradlew test` |
| V0-9 | H2 흔적 제거 | `grep -ri "h2" build.gradle.kts src/` 결과 없음 |
| V0-10 | 빌드 그린 | `./gradlew build` |

---

## 산출물

```
build.gradle.kts (수정)
docker-compose.local.yml
src/main/resources/
  application.yml / application-local.yml / application-prod.yml
  db/migration/V1__baseline.sql
src/main/kotlin/com/siheungbootcamp/teamd/global/
  persistence/BaseEntity.kt, JpaAuditingConfig.kt
  id/PublicId.kt, IdPrefix.kt
  auth/ParticipantToken.kt, TokenHasher.kt, ParticipantAuthFilter.kt,
       ParticipantPrincipal.kt, CurrentParticipant.kt, SecurityConfig.kt
  crypto/OriginCipher.kt
  web/PageResponse.kt, CacheControlInterceptor.kt, MaskingFilter.kt
  ratelimit/RateLimit.kt, RateLimitInterceptor.kt
  error/ErrorCode.kt (확장)
```

## 리스크

| 리스크 | 대응 |
|---|---|
| Spring Boot 4.1 + Security 설정 API가 예제와 다름 | 최신 문서 확인 후 최소 설정만. 커스텀 필터 하나 + stateless |
| Flyway V1이 커서 리뷰 누락 | ERD 2절 표와 1:1 대조 체크리스트를 PR에 첨부 |
| Hibernate 6 JSONB 매핑 시행착오 | `course_draft.stops` 하나로 먼저 왕복 테스트를 통과시킨 뒤 나머지 적용 |
