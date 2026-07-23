# P6 Candidate Board Completion and Area Fallback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 이미 완료된 P0~P5 동작을 보존하면서 공동 후보 보드에 좋아요·현재 선택·초대 정보 재확인을 추가하고, ODsay·JTS·Kakao만 사용하는 지역 탐색 fallback을 완성한다.

**Architecture:** P6는 기존 테이블과 API를 삭제하거나 이름을 바꾸지 않는 additive change다. `place_like`와 보드의 선택 포인터만 V2 migration으로 추가하고, 기존 참여 토큰이 같은 보드의 활성 참여자인지만 검사해 신규 기능 권한을 통일한다. 지역 작업은 기존 `JobExecutor`와 `area_search_job`/`area_candidate`를 재사용하되 TMAP 평가 없이 `ODsay 도달권 → JTS 교집합 → Kakao 기준점 1~3개`까지만 수행한다.

**Tech Stack:** Kotlin 2.3.21, Java 21, Spring Boot 4.1, Spring MVC, Spring Data JPA, PostgreSQL, Flyway, JTS, ODsay, Kakao Local, MockMvc, Testcontainers

## Global Constraints

- 기존 P0~P5 API·테이블·테스트를 제거하거나 되돌리지 않는다.
- 적용된 `V1__baseline.sql`은 수정하지 않고 `V2__candidate_board_phase6.sql`만 추가한다.
- 신규 P6 기능은 `HOST` 여부를 검사하지 않고 같은 보드의 활성 참여자인지만 검사한다.
- 기존 코스·투표·출발 안내의 HOST 계약은 이번 단계에서 바꾸지 않는다.
- 한 참여자는 서로 다른 여러 장소에 좋아요할 수 있고, 같은 장소에는 하나만 가진다.
- 현재 선택 장소는 보드당 0개 또는 1개이며 모든 참여자가 지정·변경·해제할 수 있다.
- 선택 충돌은 board 행 잠금 후 마지막 커밋이 이기는 방식으로 처리하고 변경자·시각을 기록한다.
- 모든 참여자는 기존 참여 코드와 초대 링크를 언제든 조회할 수 있다.
- 지역 제안은 ODsay, JTS, Kakao Local만 사용하며 TMAP을 호출하지 않는다.
- 허용 이동 시간은 `30`, `45`, `60`분이다.
- 장거리 공통 영역 해결, 네이버 링크 해석, 외부 리뷰·별점 수집은 범위 밖이다.

---

## 0. 변경 경계

### 유지하는 기존 구현

- `domain/vote`, `domain/course`, `domain/departure` 전체
- `BoardStatus.COLLECTING/CONFIRMED/CLOSED`
- 기존 보드 생성·수정·종료 계약
- 기존 댓글 작성자/장소 제안자 소유권 계약
- `DepartureJobExecutor`, `TmapTransitClient`
- `V1__baseline.sql`

### P6에서 추가·수정하는 파일

- Modify: `build.gradle.kts` — JTS 의존성 1개 추가
- Create: `src/main/resources/db/migration/V2__candidate_board_phase6.sql`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/board/Board.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/board/BoardDtos.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/board/BoardController.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/board/BoardService.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/board/BoardRepositories.kt`
- Create: `src/main/kotlin/com/siheungbootcamp/teamd/domain/place/PlaceLike.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/place/PlaceDtos.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/place/PlaceController.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/place/PlaceRepositories.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/place/PlaceService.kt`
- Create: `src/main/kotlin/com/siheungbootcamp/teamd/domain/area/AreaSearchJob.kt`
- Create: `src/main/kotlin/com/siheungbootcamp/teamd/domain/area/AreaCandidate.kt`
- Create: `src/main/kotlin/com/siheungbootcamp/teamd/domain/area/AreaDtos.kt`
- Create: `src/main/kotlin/com/siheungbootcamp/teamd/domain/area/AreaRepositories.kt`
- Create: `src/main/kotlin/com/siheungbootcamp/teamd/domain/area/AreaController.kt`
- Create: `src/main/kotlin/com/siheungbootcamp/teamd/domain/area/AreaService.kt`
- Create: `src/main/kotlin/com/siheungbootcamp/teamd/domain/area/AreaJobExecutor.kt`
- Create: `src/main/kotlin/com/siheungbootcamp/teamd/domain/area/GeometryService.kt`
- Create: `src/main/kotlin/com/siheungbootcamp/teamd/infra/external/odsay/OdsayIsochroneClient.kt`
- Create: `src/main/kotlin/com/siheungbootcamp/teamd/infra/external/odsay/OdsayProperties.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/global/config/ExternalApiConfig.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/global/error/ErrorCode.kt`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-local.yml`
- Modify: `src/main/resources/application-prod.yml`

### P6 테스트 파일

- Create: `src/test/kotlin/com/siheungbootcamp/teamd/domain/place/P6CandidateBoardContractTest.kt`
- Create: `src/test/kotlin/com/siheungbootcamp/teamd/domain/area/GeometryServiceTest.kt`
- Create: `src/test/kotlin/com/siheungbootcamp/teamd/domain/area/P6AreaContractTest.kt`
- Create: `src/test/kotlin/com/siheungbootcamp/teamd/infra/external/odsay/OdsayStubServer.kt`
- Modify: `src/test/kotlin/com/siheungbootcamp/teamd/infra/external/kakao/KakaoStubServer.kt`
- Modify: `src/test/kotlin/com/siheungbootcamp/teamd/FoundationIntegrationTest.kt`

---

### Task 1: 기존 동작 회귀선과 additive migration

**Files:**
- Create: `src/main/resources/db/migration/V2__candidate_board_phase6.sql`
- Modify: `src/test/kotlin/com/siheungbootcamp/teamd/FoundationIntegrationTest.kt`

**Interfaces:**
- Produces: `board.selected_place_id`, `board.selected_by_participant_id`, `board.selected_at`, `place_like`
- Preserves: V1의 13개 테이블과 모든 FK

- [ ] **Step 1: 현재 전체 테스트로 P0~P5 회귀선 확인**

Run:

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`. 실패하면 P6 작업을 시작하지 않고 기존 실패를 별도 기록한다.

- [ ] **Step 2: V2 migration 기대 테스트 작성**

`FoundationIntegrationTest`에 다음 검증을 추가한다.

```kotlin
@Test
fun `P6 migration은 기존 테이블을 보존하고 공동 후보 컬럼만 추가한다`() {
    assertEquals(2, jdbcClient.sql(
        "select count(*) from flyway_schema_history where success=true"
    ).query(Int::class.java).single())
    assertEquals(3, jdbcClient.sql(
        """
        select count(*) from information_schema.columns
        where table_name='board'
          and column_name in ('selected_place_id','selected_by_participant_id','selected_at')
        """.trimIndent()
    ).query(Int::class.java).single())
    assertEquals(1, jdbcClient.sql(
        "select count(*) from information_schema.tables where table_name='place_like'"
    ).query(Int::class.java).single())
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run:

```bash
./gradlew test --tests '*FoundationIntegrationTest'
```

Expected: migration 개수 또는 신규 컬럼 assertion 실패.

- [ ] **Step 4: V2 migration 추가**

```sql
alter table board add column selected_place_id bigint references place(id);
alter table board add column selected_by_participant_id bigint references participant(id);
alter table board add column selected_at timestamptz;

create table place_like (
    place_id bigint not null references place(id),
    participant_id bigint not null references participant(id),
    created_at timestamptz not null default now(),
    primary key (place_id, participant_id)
);

create index idx_place_like_participant on place_like(participant_id);
```

같은 보드 소속과 `ACTIVE` 장소 여부는 서비스 트랜잭션에서 검증한다. 기존 테이블·컬럼은 삭제하지 않는다.

- [ ] **Step 5: migration 테스트 통과 확인**

Run:

```bash
./gradlew test --tests '*FoundationIntegrationTest'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: 커밋**

```bash
git add src/main/resources/db/migration/V2__candidate_board_phase6.sql src/test/kotlin/com/siheungbootcamp/teamd/FoundationIntegrationTest.kt
git commit -m "P6 공동 후보 상태를 additive migration으로 준비한다"
```

---

### Task 2: 공동 좋아요·현재 선택·초대 재확인

**Files:**
- Create: `src/main/kotlin/com/siheungbootcamp/teamd/domain/place/PlaceLike.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/place/PlaceDtos.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/place/PlaceController.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/place/PlaceRepositories.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/place/PlaceService.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/board/Board.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/board/BoardDtos.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/board/BoardController.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/board/BoardService.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/board/BoardRepositories.kt`
- Test: `src/test/kotlin/com/siheungbootcamp/teamd/domain/place/P6CandidateBoardContractTest.kt`

**Interfaces:**
- Produces: `PUT/DELETE /api/v1/boards/{boardId}/places/{placeId}/likes/me`
- Produces: `PUT/DELETE /api/v1/boards/{boardId}/selected-place`
- Changes: `GET /api/v1/boards/{boardId}/invitation` 권한을 HOST에서 같은 보드 참여자로 완화
- Produces response fields: `likeCount`, `likedByMe`, `selected`, `selectedByParticipantId`, `selectedAt`

- [ ] **Step 1: HTTP 계약 실패 테스트 작성**

테스트는 아래 시나리오를 각각 독립 테스트로 만든다.

```kotlin
@Test
fun `한 참여자는 서로 다른 두 장소에 좋아요할 수 있고 같은 요청은 멱등적이다`() {
    putLike(memberToken, placeA).andExpect { status { isNoContent() } }
    putLike(memberToken, placeA).andExpect { status { isNoContent() } }
    putLike(memberToken, placeB).andExpect { status { isNoContent() } }
    assertPlace(memberToken, placeA, likeCount = 1, likedByMe = true)
    assertPlace(memberToken, placeB, likeCount = 1, likedByMe = true)
}

@Test
fun `일반 참여자가 선택 장소를 바꾸고 해제할 수 있다`() {
    select(memberToken, placeA).andExpect { status { isOk() } }
    select(otherMemberToken, placeB).andExpect { status { isOk() } }
    assertSelected(placeB, changedBy = otherMemberId)
    clearSelection(memberToken).andExpect { status { isNoContent() } }
}

@Test
fun `일반 참여자가 초대 코드를 다시 조회할 수 있다`() {
    mockMvc.get("/api/v1/boards/$boardId/invitation") {
        bearer(memberToken)
    }.andExpect {
        status { isOk() }
        jsonPath("$.inviteCode") { value(inviteCode) }
    }
}
```

- [ ] **Step 2: 신규 계약이 실패하는지 확인**

Run:

```bash
./gradlew test --tests '*P6CandidateBoardContractTest'
```

Expected: endpoint 없음 또는 `403`.

- [ ] **Step 3: 저장 모델과 repository 구현**

`PlaceLike`는 복합 키 대신 단순 엔티티 ID를 새로 만들지 않는다. Spring Data repository는 아래 연산만 노출한다.

```kotlin
@Embeddable
data class PlaceLikeId(
    @Column(name = "place_id") val placeId: Long = 0,
    @Column(name = "participant_id") val participantId: Long = 0,
)

@Entity
@Table(name = "place_like")
class PlaceLike(
    @EmbeddedId val id: PlaceLikeId,
)

interface PlaceLikeRepository : JpaRepository<PlaceLike, PlaceLikeId> {
    fun existsByPlaceIdAndParticipantId(placeId: Long, participantId: Long): Boolean
    fun countByPlaceId(placeId: Long): Long
    fun deleteByPlaceIdAndParticipantId(placeId: Long, participantId: Long): Long
}
```

`Board`에는 의도가 드러나는 변경 메서드만 추가한다.

```kotlin
fun select(placeId: Long, participantId: Long, now: Instant) {
    selectedPlaceId = placeId
    selectedByParticipantId = participantId
    selectedAt = now
}

fun clearSelection(participantId: Long, now: Instant) {
    selectedPlaceId = null
    selectedByParticipantId = participantId
    selectedAt = now
}
```

`BoardRepository`에는 `findByPublicIdForUpdate`를 추가해 선택 변경을 직렬화한다.

- [ ] **Step 4: 서비스 권한과 endpoint 구현**

신규 P6 서비스 메서드에서 공통으로 다음 조건만 검사한다.

```kotlin
private fun requireBoardParticipant(boardId: String, principal: ParticipantPrincipal) {
    if (principal.boardId != boardId) {
        throw BusinessException(ErrorCode.FORBIDDEN)
    }
}
```

- 좋아요 PUT은 이미 존재하면 그대로 성공한다.
- 좋아요 DELETE는 없어도 성공한다.
- 선택 PUT은 board 행 잠금 후 같은 보드의 `ACTIVE` 장소인지 검사한다.
- 선택 DELETE는 board 행 잠금 후 항상 변경자·시각을 기록한다.
- invitation 조회에서는 기존 `requireHost` 호출만 제거하고 보드 일치 검사는 유지한다.
- 기존 P0~P5 endpoint의 HOST 검사는 바꾸지 않는다.

- [ ] **Step 5: 계약 테스트 통과 확인**

Run:

```bash
./gradlew test --tests '*P6CandidateBoardContractTest' --tests '*P1ContractTest' --tests '*PlaceContractTest'
```

Expected: 신규 테스트와 기존 보드·장소 계약 모두 통과.

- [ ] **Step 6: 커밋**

```bash
git add src/main/kotlin/com/siheungbootcamp/teamd/domain/board src/main/kotlin/com/siheungbootcamp/teamd/domain/place src/test/kotlin/com/siheungbootcamp/teamd/domain/place/P6CandidateBoardContractTest.kt
git commit -m "P6 후보 좋아요와 공동 선택을 추가한다"
```

---

### Task 3: JTS 교집합과 ODsay 도달권 어댑터

**Files:**
- Modify: `build.gradle.kts`
- Create: `src/main/kotlin/com/siheungbootcamp/teamd/domain/area/GeometryService.kt`
- Create: `src/main/kotlin/com/siheungbootcamp/teamd/infra/external/odsay/OdsayIsochroneClient.kt`
- Create: `src/main/kotlin/com/siheungbootcamp/teamd/infra/external/odsay/OdsayProperties.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/global/config/ExternalApiConfig.kt`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-local.yml`
- Modify: `src/main/resources/application-prod.yml`
- Test: `src/test/kotlin/com/siheungbootcamp/teamd/domain/area/GeometryServiceTest.kt`
- Test: `src/test/kotlin/com/siheungbootcamp/teamd/infra/external/odsay/OdsayStubServer.kt`

**Interfaces:**
- Produces: `OdsayIsochroneClient.fetch(lon: Double, lat: Double, durationMin: Int): Geometry`
- Produces: `GeometryService.intersectLargest(inputs: List<Geometry>, limit: Int = 3): List<Geometry>`
- Produces: `GeometryService.contains(area: Geometry, lon: Double, lat: Double): Boolean`

- [ ] **Step 1: GeometryService 실패 테스트 작성**

```kotlin
@Test
fun `두 도달권의 교집합에서 면적이 큰 조각 세 개까지만 반환한다`() {
    val result = service.intersectLargest(listOf(leftMultiPolygon, rightPolygon), limit = 3)
    assertTrue(result.size <= 3)
    assertTrue(result.zipWithNext().all { (a, b) -> a.area >= b.area })
    assertTrue(result.all { it.isValid && !it.isEmpty })
}
```

면적은 EPSG:5179 투영 또는 검증된 구면 면적 계산 중 하나만 선택한다. 단순 `geometry.area`를 km²로 표기하지 않는다.

- [ ] **Step 2: 테스트 실패 확인**

Run:

```bash
./gradlew test --tests '*GeometryServiceTest'
```

Expected: JTS 의존성 또는 클래스 없음으로 실패.

- [ ] **Step 3: JTS와 GeometryService 구현**

`build.gradle.kts`:

```kotlin
implementation("org.locationtech.jts:jts-core:1.20.0")
```

서비스는 입력을 `GeometryFixer.fix`, `buffer(0)` 순으로 정규화하고 `reduce(Geometry::intersection)` 후 polygon 조각을 면적 내림차순으로 반환한다.

- [ ] **Step 4: ODsay stub 계약 테스트 작성**

정확한 요청은 다음으로 고정한다.

```text
GET /v1/api/searchPubTransIsochrone
  ?x={lon}
  &y={lat}
  &searchTime={30|45|60}
  &searchMethod=4
  &apiKey={server-side-key}
```

테스트는 `200 Polygon`, `200 MultiPolygon`, `429 후 성공`, `500 재시도 소진`, malformed body를 검증한다.

- [ ] **Step 5: ODsay client와 설정 구현**

`ExternalApiConfig`에 ODSAY quota/client를 추가하되 기존 Kakao/TMAP bean을 변경하지 않는다. `apiKey`는 환경변수 `ODSAY_API_KEY`로만 주입한다.

- [ ] **Step 6: 단위 테스트 통과 확인**

Run:

```bash
./gradlew test --tests '*GeometryServiceTest' --tests '*Odsay*'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: 커밋**

```bash
git add build.gradle.kts src/main/kotlin/com/siheungbootcamp/teamd/domain/area/GeometryService.kt src/main/kotlin/com/siheungbootcamp/teamd/infra/external/odsay src/main/kotlin/com/siheungbootcamp/teamd/global/config/ExternalApiConfig.kt src/main/resources src/test/kotlin/com/siheungbootcamp/teamd/domain/area/GeometryServiceTest.kt src/test/kotlin/com/siheungbootcamp/teamd/infra/external/odsay
git commit -m "P6 ODsay 도달권과 JTS 교집합을 검증한다"
```

---

### Task 4: 단순화된 지역 제안 작업

**Files:**
- Create: `src/main/kotlin/com/siheungbootcamp/teamd/domain/area/AreaSearchJob.kt`
- Create: `src/main/kotlin/com/siheungbootcamp/teamd/domain/area/AreaCandidate.kt`
- Create: `src/main/kotlin/com/siheungbootcamp/teamd/domain/area/AreaDtos.kt`
- Create: `src/main/kotlin/com/siheungbootcamp/teamd/domain/area/AreaRepositories.kt`
- Create: `src/main/kotlin/com/siheungbootcamp/teamd/domain/area/AreaController.kt`
- Create: `src/main/kotlin/com/siheungbootcamp/teamd/domain/area/AreaService.kt`
- Create: `src/main/kotlin/com/siheungbootcamp/teamd/domain/area/AreaJobExecutor.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/global/error/ErrorCode.kt`
- Modify: `src/test/kotlin/com/siheungbootcamp/teamd/infra/external/kakao/KakaoStubServer.kt`
- Test: `src/test/kotlin/com/siheungbootcamp/teamd/domain/area/P6AreaContractTest.kt`

**Interfaces:**
- Produces: `POST /api/v1/boards/{boardId}/area-search-jobs`
- Produces: `GET /api/v1/boards/{boardId}/area-search-jobs/{jobId}`
- Consumes: `JobExecutor`, `OriginCipher`, `OdsayIsochroneClient`, `GeometryService`, `KakaoLocalClient.searchKeyword`
- Reuses: V1의 `area_search_job`, `area_candidate`

- [ ] **Step 1: 비동기 계약 실패 테스트 작성**

아래 테스트를 우선 작성한다.

```kotlin
@Test
fun `일반 참여자가 지역 제안을 시작하고 폴링해 세 개 이하 결과를 받는다`() {
    postArea(memberToken, 45).andExpect {
        status { isAccepted() }
        header { exists("Location") }
        jsonPath("$.estimatedExternalCalls.odsay") { value(2) }
        jsonPath("$.estimatedExternalCalls.tmapTransit") { value(0) }
    }
    runExecutorUntilIdle()
    getArea(memberToken, jobId).andExpect {
        status { isOk() }
        jsonPath("$.status") { value("SUCCEEDED") }
        jsonPath("$.result.candidates.length()") { value(3) }
    }
    assertEquals(0, tmapStub.requestCount)
}
```

추가 테스트:

- 출발지 누락 → 동기 `422 ORIGIN_REQUIRED`
- 참여자 1명 → `400 INVALID_ARGUMENT`
- 같은 입력의 활성 작업 → 기존 `jobId` 재사용
- 교집합 없음 → job `FAILED`, `NO_INTERSECTION`
- Kakao 기준점 없음 → job `FAILED`, `NO_AREA_ANCHOR`
- ODsay 실패 → job `FAILED`, `EXTERNAL_UNAVAILABLE`
- 모든 단계에서 TMAP 호출 0회

- [ ] **Step 2: 실패 확인**

Run:

```bash
./gradlew test --tests '*P6AreaContractTest'
```

Expected: area controller/executor 없음으로 실패.

- [ ] **Step 3: POST 접수 서비스 구현**

검증 순서:

1. `principal.boardId == boardId`
2. `durationMin in setOf(30, 45, 60)`
3. 활성 참여자 2명 이상
4. 대상 참여자 ID 오름차순 `FOR UPDATE`
5. 모든 대상의 `originCiphertext` 존재
6. 같은 보드 활성 job이 있으면 새 외부 호출 없이 그 job 반환
7. snapshot에는 `participantIds`만 저장하고 좌표·검색어는 저장하지 않음

응답:

```json
{
  "jobId": "job_...",
  "status": "QUEUED",
  "estimatedExternalCalls": {
    "odsay": 2,
    "kakaoLocal": 3,
    "tmapTransit": 0
  }
}
```

- [ ] **Step 4: 3단계 executor 구현**

| phase | 동작 | 외부 호출 |
|---|---|---:|
| `ISOCHRONE` | 참여자별 도달권 조회 | ODsay N회 |
| `INTERSECTION` | 교집합·면적 상위 3조각 | 0회 |
| `AREA_ANCHOR_COLLECTION` | 조각 centroid 주변 `"역"`, `"맛집"`, `"카페"` 검색 후 조각 내부·중복 제거 | Kakao 최대 9회 |

TMAP phase, 평균 이동시간, 최대 이동시간, 환승 수, 공정성 점수는 구현하지 않는다.

`area_candidate.metrics`에는 호환을 위해 다음 최소 값만 저장한다.

```json
{
  "pieceIndex": 0,
  "intersectionAreaKm2": 3.14
}
```

`reasons`에는 `"공통 도달 영역 안의 탐색 기준점"` 한 문장만 저장한다. `rank`는 면적 조각 순서와 검색 결과 안정 순서를 조합한 화면 표시 순서다.

- [ ] **Step 5: 재시도·복구 경계를 기존 JobExecutor 방식에 맞춤**

- 외부 호출 중 DB transaction을 유지하지 않는다.
- ODsay/Kakao의 `429`, `5xx`만 최대 3회 재시도한다.
- 오래된 `RUNNING`은 애플리케이션 시작 또는 첫 poll에서 `QUEUED`로 되돌린다.
- phase 종료마다 `progress`를 짧은 transaction으로 저장한다.
- 원문 외부 응답, API key, 출발 좌표를 일반 로그에 남기지 않는다.

- [ ] **Step 6: 계약 테스트 통과 확인**

Run:

```bash
./gradlew test --tests '*P6AreaContractTest' --tests '*GeometryServiceTest' --tests '*Odsay*'
```

Expected: `BUILD SUCCESSFUL`, TMAP request count 0.

- [ ] **Step 7: 커밋**

```bash
git add src/main/kotlin/com/siheungbootcamp/teamd/domain/area src/main/kotlin/com/siheungbootcamp/teamd/global/error/ErrorCode.kt src/test/kotlin/com/siheungbootcamp/teamd/domain/area src/test/kotlin/com/siheungbootcamp/teamd/infra/external/kakao/KakaoStubServer.kt
git commit -m "P6 지역 탐색 fallback을 ODsay와 Kakao로 완성한다"
```

---

### Task 5: 전체 회귀·외부 API 비용 경계·문서 완료

**Files:**
- Modify: `docs/plan/07-phase6-area-search.md` — 체크박스와 실측 결과만 갱신
- No production code unless a failing verification proves it is necessary

**Interfaces:**
- Verifies: P0~P5 preservation and P6 completion

- [x] **Step 1: 정적 비용 경계 확인**

Run:

```bash
rg -n "TmapTransitClient|TRANSIT_EVALUATION|avgSeconds|maxSeconds|unreachableCount" src/main/kotlin/com/siheungbootcamp/teamd/domain/area
```

Expected: 결과 0건.

**실측**: 0건. `domain/area` 패키지 전체에 TMAP 관련 참조 없음 확인 (2026-07-23).

- [x] **Step 2: P6 핵심 테스트 실행**

```bash
./gradlew test --tests '*P6CandidateBoardContractTest' --tests '*P6AreaContractTest' --tests '*GeometryServiceTest' --tests '*Odsay*'
```

Expected: `BUILD SUCCESSFUL`.

**실측**: `BUILD SUCCESSFUL`. P6CandidateBoardContractTest(3), P6AreaContractTest(8), GeometryServiceTest(7), OdsayIsochroneClientIntegrationTest(5) 전부 통과.

- [x] **Step 3: 기존 Phase 회귀 테스트 실행**

```bash
./gradlew test --tests '*P1ContractTest' --tests '*PlaceContractTest' --tests '*P3CommentContractTest' --tests '*P3VoteContractTest' --tests '*P4CourseContractTest' --tests '*P5DepartureContractTest'
```

Expected: `BUILD SUCCESSFUL`. 기존 HOST·투표·코스·출발 계약이 깨지지 않아야 한다.

**실측**: `BUILD SUCCESSFUL`. 기존 P1/P3/P4/P5 계약 테스트에 회귀 없음. (참고: 이 저장소의 실제 테스트 클래스명은 `P3VoteContractTest`/`P3CommentContractTest`이며 `P4CourseContractTest`/`P5DepartureContractTest`는 매칭되는 클래스가 없어 --tests 필터가 조용히 스킵됨 — P4/P5 관련 계약은 전체 회귀(Step 4)의 `./gradlew build`로 커버됨.)

- [x] **Step 4: 전체 빌드**

```bash
./gradlew build
```

Expected: `BUILD SUCCESSFUL`.

**실측**: `BUILD SUCCESSFUL` (전체 테스트 스위트 포함, P0~P6 전 범위 회귀 없음, 2026-07-23).

- [ ] **Step 5: 운영 ODsay smoke — `Not-tested`**

운영 고정 IP가 등록된 VM에서 참여자 2명, 45분으로 작업을 1회 실행한다.

검증:

- ODsay 응답 200
- Kakao 기준점 1개 이상
- TMAP 호출 증가량 0
- 로그에 API key와 출발 좌표 원문 없음

**실측**: `Not-tested`. 이 작업은 로컬 코드 저장소 에이전트 세션에서 수행되어 운영 VM·실제 ODsay 키에 접근할 수 없다. I1-10(ODsay 고정 IP 등록)은 완료된 것으로 확인됐으나, 실제 운영 스모크 실행은 VM 접근 권한이 있는 사람이 별도로 수행해야 한다. 로컬 stub 기반 검증(Step 1~4)은 전부 완료했다. 구현 완료를 가짜로 표시하지 않기 위해 이 항목만 미완료로 남긴다.

- [ ] **Step 6: 최종 커밋**

```bash
git add docs/plan/07-phase6-area-search.md
git commit -m "P6 검증 결과와 외부 API 비용 경계를 기록한다"
```

---

## P6 완료 조건 — 실측 결과 (2026-07-23)

1. [x] 기존 P0~P5 전체 테스트가 그대로 통과한다. (`./gradlew build` BUILD SUCCESSFUL)
2. [x] 일반 참여자가 여러 장소에 좋아요할 수 있다. (P6CandidateBoardContractTest)
3. [x] 일반 참여자가 현재 선택 장소를 지정·변경·해제할 수 있다. (P6CandidateBoardContractTest)
4. [x] 마지막 선택 변경자와 시각이 조회된다. (BoardResponse.selectedByParticipantId/selectedAt)
5. [x] 일반 참여자가 참여 코드와 초대 링크를 다시 조회할 수 있다. (GET invitation, HOST 제한 완화)
6. [x] 일반 참여자가 지역 제안 작업을 시작할 수 있다. (P6AreaContractTest)
7. [x] 지역 제안은 1~3개 탐색 기준점을 반환한다. (GeometryService.intersectLargest limit=3)
8. [x] P6 area 패키지에서 TMAP 호출은 0회다. (정적 grep 0건 + 계약 테스트 assertion)
9. [x] 장거리·네이버 링크·리뷰 수집은 구현하지 않는다. (범위에 없음, 코드 없음)

**미완료 항목**: V6-14/Step 5 운영 ODsay smoke는 `Not-tested`(로컬 세션에서 운영 VM 접근 불가). 그 외 전 항목 로컬 stub 기반으로 검증 완료.

## 명시적으로 하지 않는 것

- P0~P5 코드 삭제 또는 이전 migration 수정
- 기존 `HOST` 역할과 과거 API의 일괄 제거
- 기존 투표·코스·출발 안내 endpoint 비활성화
- Redis, Kafka, 별도 worker, WebSocket
- 실시간 선택 변경 이벤트
- 이동시간 공정성 순위
- 참여자×후보 TMAP 전수 계산
- 네이버 자동 링크 해석
- 서울–부산 장거리 해법
