# P7 Canonical Contract Alignment and Frontend Handoff Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:test-driven-development for every behavior change and superpowers:verification-before-completion before each commit. Execute tasks in order; do not start FE work before Task 7 passes.

**Goal:** 완료된 P0~P6 구현을 최신 `docs/specs`의 후보 장소 보드 계약에 맞추고, 프론트엔드가 임시 변환 계층 없이 연동할 수 있는 OpenAPI를 제공한다.

**Architecture:** 기존 Kotlin/Spring Boot 단일 프로세스와 PostgreSQL·Flyway·Scheduled Job 구조는 유지한다. REST 경로와 DTO는 FE 착수 전 breaking change로 canonical 계약에 맞추고, 지역 찾기는 `ODsay 참고 도달권 → JTS nullable 공통 영역 → 참여자 대표 중심 → Kakao 교통 기준점`으로 교체한다. 레거시 Vote·Course·Departure 구현은 삭제하지 않고 기본 비활성화하여 과거 회귀 테스트와 코드 이력을 보존한다.

**Tech Stack:** Kotlin 2.3.21, Java 21, Spring Boot 4.1, Spring MVC, Spring Data JPA, PostgreSQL, Flyway, JTS, Kakao Local REST, ODsay, MockMvc, Testcontainers

## Global Constraints

- 진실의 원본은 `../../../docs/specs/기능명세서_v1.3.md`, `API명세서_v1.1.md`, `ERD_v1.0.md`, `시스템아키텍처_v1.0.md`, `화면연동명세서_v1.0.md`다.
- 완료된 P0~P6 커밋과 V1·V2 migration을 수정하거나 되돌리지 않는다. DB 변경은 V3 migration으로만 수행한다.
- 신규 FE 계약은 정식 투표·코스·출발 안내를 노출하지 않는다. 좋아요와 현재 선택 장소만 사용한다.
- 개설자 역할은 이력 메타데이터일 뿐 신규 MVP 권한 판단에 사용하지 않는다.
- 모든 활성 참여자는 후보 추가·보관, 좋아요, 현재 선택, 지역 찾기, 초대 정보 조회가 가능하다.
- 댓글 수정·삭제와 출발지 수정은 본인만 가능하다.
- 공통 영역과 `durationMin`은 참고 정보다. 공통 영역 없음, 기준점 없음, 참고 시간 초과는 job 실패 또는 기준점 탈락 조건이 아니다.
- 지도 자유 탐색 좌표는 저장하지 않는다. 사용자가 검색 결과를 추가할 때만 `place`를 생성한다.
- 지역 찾기와 주변 검색에서 TMAP 호출은 0회다.
- 외부 API 실제 키를 사용하는 자동 테스트를 작성하지 않는다. Stub과 Testcontainers를 사용한다.
- Redis, Kafka, RabbitMQ, 별도 워커, WebFlux, 신규 외부 의존성을 추가하지 않는다.
- 환승역 노선별 중복 병합과 장거리 모임 최적화는 구현하지 않는다.

---

## 0. 현재 구현 기준선과 확정된 Gap

| 영역 | 현재 구현 | P7 목표 |
|---|---|---|
| 좋아요 | `place_like`, 멱등 PUT/DELETE, 다중 장소 지원 | 유지 |
| 댓글 | CRUD 존재, DTO `body`/`authorId` | canonical `content`/`authorParticipantId` |
| 현재 선택 | 모든 참여자 PUT/DELETE, 변경자·시각 저장 | 유지 |
| 후보 보관 | 서비스는 모든 활성 참여자 허용 | 낡은 Swagger 설명과 레거시 참조 검사 정리 |
| 장소 검색 경로 | `/place-candidates`, `/address-candidates`, `/coordinate-address` | `/search/places`, `/search/addresses`, `/search/reverse-geocode` |
| 장소 DTO | flat `lon`, `lat`, `providerPlaceUrl` | `location`, `sourceUrl`, `source` |
| 검색 결과 수 | Kakao 요청·응답 최대 5개 | Kakao 최대 15개 |
| 자유 좌표 주변 검색 | 전용 API 없음 | `/search/nearby-places` |
| 지역 기준점 | 교집합 조각 centroid의 `"역"`을 내부 필터 | 참여자 대표 중심 주변 역·기차역·버스터미널 |
| 지역 결과 | `result.candidates` | `isochrones`, `commonArea`, `participantCenter`, `anchors` |
| 정상 빈 결과 | 빈 교집합·기준점 없음이 실패 가능 | `SUCCEEDED`, nullable/empty result |
| 레거시 API | Vote·Course·Departure 기본 노출 | 기본 비활성화·OpenAPI 제외 |

---

### Task 1: 회귀선과 canonical OpenAPI 실패 계약 고정

**Files:**
- Create: `src/test/kotlin/com/siheungbootcamp/teamd/contract/P7CanonicalOpenApiTest.kt`
- Create: `src/test/kotlin/com/siheungbootcamp/teamd/contract/P7CanonicalFlowContractTest.kt`
- Modify: `src/test/kotlin/com/siheungbootcamp/teamd/domain/place/PlaceContractTest.kt`
- Modify: `src/test/kotlin/com/siheungbootcamp/teamd/domain/comment/P3CommentContractTest.kt`
- Modify: `src/test/kotlin/com/siheungbootcamp/teamd/domain/area/P6AreaContractTest.kt`

**Consumes:**
- 현재 P0~P6 전체 테스트
- `GET /v3/api-docs`

**Produces:**
- 이후 Task가 만족해야 할 실패하는 canonical 계약 테스트
- 레거시 회귀 테스트 실행 기준

- [ ] **Step 1: 현재 회귀선 확인**

Run:

```bash
./gradlew test
```

Expected: 현재 테스트 전부 PASS. 실패가 있으면 P7 작업을 시작하지 않고 기존 회귀부터 복구한다.

- [ ] **Step 2: canonical OpenAPI 실패 테스트 작성**

`P7CanonicalOpenApiTest`는 기본 프로필의 `/v3/api-docs`에서 다음을 검증한다.

```kotlin
@Test
fun `canonical paths are exposed and legacy paths are hidden`() {
    val api = mockMvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk)
        .andReturn().response.contentAsString

    assertContains(api, "/api/v1/boards/{boardId}/search/places")
    assertContains(api, "/api/v1/boards/{boardId}/search/nearby-places")
    assertFalse(api.contains("/place-candidates"))
    assertFalse(api.contains("/votes"))
    assertFalse(api.contains("/course-draft"))
    assertFalse(api.contains("/departure-guide"))
}
```

- [ ] **Step 3: canonical 사용자 흐름 실패 테스트 작성**

`P7CanonicalFlowContractTest`에 최소 다음 시나리오를 작성한다.

1. 제안자가 아닌 활성 참여자가 후보를 보관하면 `204`
2. 한 참여자가 서로 다른 두 장소에 좋아요 PUT 후 둘 다 `likedByMe=true`
3. 임의 참여자가 현재 선택 장소를 지정·변경·해제
4. 공통 영역 밖 좌표로 주변 카페 검색 성공
5. 주변 검색만으로 `place` 테이블 행 수가 증가하지 않음

- [ ] **Step 4: RED 확인**

Run:

```bash
./gradlew test --tests '*P7CanonicalOpenApiTest' --tests '*P7CanonicalFlowContractTest'
```

Expected: 신규 canonical 경로·응답·주변 검색 부재로 FAIL.

- [ ] **Step 5: 기준선 커밋**

```bash
git add src/test/kotlin/com/siheungbootcamp/teamd/contract src/test/kotlin/com/siheungbootcamp/teamd/domain
git commit -m "P7 최신 FE 계약의 실패 기준을 고정한다"
```

---

### Task 2: 검색 경로와 FE DTO를 canonical 계약으로 전환

**Files:**
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/place/PlaceController.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/place/PlaceDtos.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/place/PlaceService.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/comment/CommentDtos.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/comment/CommentService.kt`
- Modify: `src/test/kotlin/com/siheungbootcamp/teamd/domain/place/PlaceContractTest.kt`
- Modify: `src/test/kotlin/com/siheungbootcamp/teamd/domain/comment/P3CommentContractTest.kt`

**Consumes:**
- `ParticipantPrincipal`
- 기존 `Place`, `PlaceComment` 저장 모델

**Produces:**
- `GET /api/v1/boards/{boardId}/search/places`
- `GET /api/v1/boards/{boardId}/search/addresses`
- `GET /api/v1/boards/{boardId}/search/reverse-geocode`
- canonical Place·Comment JSON

- [ ] **Step 1: 최종 DTO 실패 assertion 추가**

계약 테스트는 검색 결과와 장소 응답에서 다음 구조를 검증한다.

```json
{
  "providerPlaceId": "123",
  "name": "장소명",
  "location": {"lon": 126.7, "lat": 37.3},
  "sourceUrl": "https://place.map.kakao.com/123"
}
```

댓글 요청·응답은 `body` 대신 다음 이름을 사용한다.

```json
{
  "content": "같이 가고 싶어요."
}
```

```json
{
  "commentId": "cmt_...",
  "authorParticipantId": "ptc_...",
  "content": "같이 가고 싶어요."
}
```

- [ ] **Step 2: 검색 경로를 교체**

`PlaceController`의 기존 경로를 alias로 남기지 않고 다음으로 변경한다.

```kotlin
@GetMapping("/boards/{boardId}/search/places")
fun searchPlaces(@RequestParam q: String, ...)

@GetMapping("/boards/{boardId}/search/addresses")
fun searchAddresses(@RequestParam q: String, ...)

@GetMapping("/boards/{boardId}/search/reverse-geocode")
fun reverseGeocode(@RequestParam lon: Double, @RequestParam lat: Double, ...)
```

FE 착수 전 breaking change이므로 `/place-candidates`, `/address-candidates`, `/coordinate-address`는 제거한다.

- [ ] **Step 3: 공급자 중립 응답 DTO 적용**

DTO의 공통 값 객체를 추가한다.

```kotlin
data class LocationResponse(val lon: Double, val lat: Double)

data class PlaceSourceResponse(
    val sourceProvider: String,
    val providerPlaceId: String?,
    val sourceUrl: String?,
    val inputMethod: String,
)
```

DB 컬럼명은 P7에서 바꾸지 않는다. `providerPlaceUrl`은 응답에서 `sourceUrl`로 매핑한다.

- [ ] **Step 4: Comment DTO 이름 전환**

`CreateCommentRequest`, `UpdateCommentRequest`, `CommentResponse`의 JSON 필드를 canonical 이름으로 변경한다. DB의 `place_comment.body` 컬럼은 그대로 유지한다.

- [ ] **Step 5: 계약 테스트 실행**

Run:

```bash
./gradlew test --tests '*PlaceContractTest' --tests '*P3CommentContractTest' --tests '*P7CanonicalOpenApiTest'
```

Expected: PASS.

- [ ] **Step 6: 커밋**

```bash
git add src/main/kotlin/com/siheungbootcamp/teamd/domain/place src/main/kotlin/com/siheungbootcamp/teamd/domain/comment src/test/kotlin/com/siheungbootcamp/teamd/domain/place src/test/kotlin/com/siheungbootcamp/teamd/domain/comment
git commit -m "P7 장소와 댓글 계약을 프론트 연동 형태로 동결한다"
```

---

### Task 3: 주변 장소 검색과 Kakao 결과 15개 지원

**Files:**
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/place/PlaceController.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/place/PlaceDtos.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/place/PlaceService.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/infra/external/kakao/KakaoLocalClient.kt`
- Modify: `src/test/kotlin/com/siheungbootcamp/teamd/domain/place/PlaceContractTest.kt`
- Modify: `src/test/kotlin/com/siheungbootcamp/teamd/infra/external/kakao/KakaoStubServer.kt`
- Create: `src/test/kotlin/com/siheungbootcamp/teamd/domain/place/P7NearbyPlaceContractTest.kt`

**Consumes:**
- canonical `PlaceCandidateResponse`
- `KakaoLocalClient`

**Produces:**
- `GET /api/v1/boards/{boardId}/search/nearby-places`
- `KakaoLocalClient.searchCategory`
- 키워드·카테고리 검색 최대 15개

- [ ] **Step 1: 입력 검증 실패 테스트 작성**

아래를 각각 `400 INVALID_ARGUMENT`로 고정한다.

- `lon` 또는 `lat` 누락
- `q`와 `category` 동시 전달
- 둘 다 누락
- `q` 공백 제거 후 2자 미만
- `radius < 100` 또는 `radius > 5000`
- 지원하지 않는 category

- [ ] **Step 2: 정상·비저장 테스트 작성**

```kotlin
@Test
fun `common area 밖 좌표도 검색하고 place를 만들지 않는다`() {
    val before = placeRepository.count()

    getNearby(lon = 129.0, lat = 35.1, category = "CAFE", radius = 1000)
        .andExpect { status { isOk() } }
        .andExpect { jsonPath("$.items").isArray }

    assertThat(placeRepository.count()).isEqualTo(before)
}
```

- [ ] **Step 3: Kakao adapter 확장**

키워드 검색은 `size`를 매개변수로 받고 `1..15`로 제한한다.

```kotlin
fun searchKeyword(
    query: String,
    lon: Double? = null,
    lat: Double? = null,
    radius: Int? = null,
    size: Int = 15,
): List<PlaceCandidate>
```

카테고리 검색을 추가한다.

```kotlin
fun searchCategory(
    categoryGroupCode: String,
    lon: Double,
    lat: Double,
    radius: Int,
    size: Int = 15,
): List<PlaceCandidate>
```

category 매핑:

| canonical | Kakao group code |
|---|---|
| `RESTAURANT` | `FD6` |
| `CAFE` | `CE7` |
| `CULTURE` | `CT1` |
| `TOUR` | `AT4` |
| `ACCOMMODATION` | `AD5` |

`PLAY`는 Kakao category group이 없으므로 `q` 키워드 검색을 사용한다.

- [ ] **Step 4: 주변 검색 서비스·컨트롤러 구현**

```kotlin
@GetMapping("/boards/{boardId}/search/nearby-places")
fun searchNearbyPlaces(
    @PathVariable boardId: String,
    @RequestParam lon: Double,
    @RequestParam lat: Double,
    @RequestParam(required = false) category: String?,
    @RequestParam(required = false, name = "q") query: String?,
    @RequestParam(defaultValue = "1000") radius: Int,
    @CurrentParticipant principal: ParticipantPrincipal,
): PlaceCandidateResponse
```

공통 영역 포함 여부를 조회하거나 검증하는 의존성을 추가하지 않는다.

- [ ] **Step 5: 테스트 실행**

Run:

```bash
./gradlew test --tests '*P7NearbyPlaceContractTest' --tests '*PlaceContractTest' --tests '*Kakao*Test'
```

Expected: PASS, Kakao stub가 `size=15`, 좌표, radius, category group 또는 query를 확인.

- [ ] **Step 6: 커밋**

```bash
git add src/main/kotlin/com/siheungbootcamp/teamd/domain/place src/main/kotlin/com/siheungbootcamp/teamd/infra/external/kakao src/test/kotlin/com/siheungbootcamp/teamd/domain/place src/test/kotlin/com/siheungbootcamp/teamd/infra/external/kakao
git commit -m "P7 지도 자유 좌표에서 실제 장소를 탐색하게 한다"
```

---

### Task 4: 지역 저장 모델을 V3에서 canonical 이름으로 정리

**Files:**
- Create: `src/main/resources/db/migration/V3__area_suggestion_alignment.sql`
- Delete: `src/main/kotlin/com/siheungbootcamp/teamd/domain/area/AreaCandidate.kt`
- Create: `src/main/kotlin/com/siheungbootcamp/teamd/domain/area/AreaSuggestion.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/area/AreaRepositories.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/area/AreaJobStateWriter.kt`
- Create: `src/test/kotlin/com/siheungbootcamp/teamd/domain/area/P7AreaMigrationTest.kt`

**Consumes:**
- 기존 `area_candidate` 데이터
- `area_search_job.result` JSONB

**Produces:**
- `area_suggestion` 테이블
- `AreaSuggestion`
- 중심 거리·공급자 컬럼

- [ ] **Step 1: migration 실패 테스트 작성**

Testcontainers PostgreSQL에서 V1→V2→V3를 적용한 뒤 다음을 확인한다.

```sql
select count(*) from information_schema.tables where table_name = 'area_suggestion';
select count(*) from information_schema.columns
 where table_name = 'area_suggestion'
   and column_name in ('provider', 'center_distance_m');
```

기존 `area_candidate` 샘플 행을 V3 적용 전에 넣고 rename 후 보존되는지도 검증한다.

- [ ] **Step 2: V3 migration 작성**

```sql
alter table area_candidate rename to area_suggestion;
alter index idx_area_candidate_job rename to idx_area_suggestion_job;
alter table area_suggestion add column provider text not null default 'KAKAO';
alter table area_suggestion add column center_distance_m int not null default 0;
alter table area_suggestion add constraint ck_area_suggestion_rank check (rank between 1 and 3);
```

V1·V2 파일은 수정하지 않는다.

- [ ] **Step 3: 엔티티·repository 이름 전환**

`AreaCandidate`/`AreaCandidateRepository`를 `AreaSuggestion`/`AreaSuggestionRepository`로 바꾸고 컬럼을 매핑한다. 외부 REST에서는 `anchor` 용어를 사용하며 DB 엔티티 이름을 노출하지 않는다.

- [ ] **Step 4: migration·영속 테스트 실행**

Run:

```bash
./gradlew test --tests '*P7AreaMigrationTest' --tests '*FoundationIntegrationTest'
```

Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add src/main/resources/db/migration/V3__area_suggestion_alignment.sql src/main/kotlin/com/siheungbootcamp/teamd/domain/area src/test/kotlin/com/siheungbootcamp/teamd/domain/area/P7AreaMigrationTest.kt
git commit -m "P7 지역 기준점 저장 모델을 canonical 이름으로 맞춘다"
```

---

### Task 5: 지역 찾기를 참여자 중심·참고 영역 방식으로 교체

**Files:**
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/area/AreaDtos.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/area/AreaJobExecutor.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/area/AreaJobStateWriter.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/area/AreaService.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/area/GeometryService.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/infra/external/kakao/KakaoLocalClient.kt`
- Modify: `src/test/kotlin/com/siheungbootcamp/teamd/domain/area/P6AreaContractTest.kt`
- Create: `src/test/kotlin/com/siheungbootcamp/teamd/domain/area/P7AreaCenterContractTest.kt`
- Modify: `src/test/kotlin/com/siheungbootcamp/teamd/domain/area/GeometryServiceTest.kt`

**Consumes:**
- snapshot participant IDs
- `OriginCipher`
- ODsay GeoJSON
- `KakaoLocalClient.searchKeyword(size = 15)`

**Produces:**
- `result.isochrones`
- nullable `result.commonArea`
- `result.participantCenter`
- 중심 거리순 `result.anchors`

- [ ] **Step 1: 중심 우선·정상 빈 결과 실패 테스트 작성**

고정 좌표 세 개를 사용한다.

```text
한국공학대학교 산학협력관
CGV 배곧
선부광장
```

Kakao stub는 중심 근처 정왕역·시흥터미널과 먼 산본역을 함께 반환한다. 결과가 중심 거리순이며 먼 후보가 먼저 오지 않는지 검증한다.

추가 테스트:

- ODsay 교집합 없음 → `SUCCEEDED`, `commonArea=null`
- Kakao 기준점 0개 → `SUCCEEDED`, `anchors=[]`
- `durationMin=30`인데 설명값이 30분 초과 → anchor 유지
- TMAP stub 호출 0회

- [ ] **Step 2: 내부 계산 결과 타입 정의**

```kotlin
data class ParticipantCenter(val lon: Double, val lat: Double)

data class AreaComputationResult(
    val isochrones: List<AnonymousIsochrone>,
    val commonArea: JsonNode?,
    val participantCenter: ParticipantCenter,
    val anchors: List<AreaAnchorResult>,
)
```

`AnonymousIsochrone`은 `areaId`와 GeoJSON만 가지며 participant ID를 응답에 넣지 않는다.

- [ ] **Step 3: 대표 중심 계산**

복호화한 실행 시점 좌표의 산술 평균을 사용한다.

```kotlin
val centerLon = origins.map { it.lon }.average()
val centerLat = origins.map { it.lat }.average()
```

평균 중심은 추천 정답이 아니라 Kakao 검색 bias다.

- [ ] **Step 4: 공통 영역을 nullable 참고값으로 계산**

`GeometryService`는 교집합이 없을 때 예외 대신 `null`을 반환한다. 참여자별 원본 도달권 GeoJSON은 익명 `areaId`와 함께 결과 JSONB에 저장한다.

- [ ] **Step 5: 교통 기준점 수집**

대표 중심 반경 `20_000m`에서 다음 세 키워드를 각각 한 번 호출한다.

```kotlin
private val anchorQueries = listOf("지하철역", "기차역", "시외버스터미널")
```

규칙:

1. Kakao `providerPlaceId` 중복 제거
2. 공통 영역 내부 여부 검사 금지
3. Turf가 아닌 JVM 측 haversine 또는 JTS 거리 유틸로 대표 중심 거리 계산
4. `centerDistanceMeters`, 이름, providerPlaceId 순으로 결정적 정렬
5. 최대 3개

- [ ] **Step 6: job 성공 저장과 응답 DTO 변경**

성공 결과:

```json
{
  "participantCenter": {"lon": 126.7341, "lat": 37.3798},
  "isochrones": [{"areaId": "area_1", "geometry": {"type": "MultiPolygon", "coordinates": []}}],
  "commonArea": null,
  "anchors": []
}
```

`AreaJobStateWriter.markSucceeded`는 anchor가 0개여도 성공 저장한다. `AreaService.getAreaSearchJob`은 `resultJson`을 canonical DTO로 반환하며 `result.candidates`를 만들지 않는다.

- [ ] **Step 7: 외부 호출 비용 고정**

`estimatedExternalCalls`:

- ODsay: 활성 참여자 수
- Kakao Local: 최대 3
- TMAP: 0

- [ ] **Step 8: 테스트 실행**

Run:

```bash
./gradlew test --tests '*P7AreaCenterContractTest' --tests '*P6AreaContractTest' --tests '*GeometryServiceTest'
```

Expected: PASS.

- [ ] **Step 9: 커밋**

```bash
git add src/main/kotlin/com/siheungbootcamp/teamd/domain/area src/main/kotlin/com/siheungbootcamp/teamd/infra/external/kakao src/test/kotlin/com/siheungbootcamp/teamd/domain/area
git commit -m "P7 지역 찾기를 사용자 중심 탐색 시작점으로 바꾼다"
```

---

### Task 6: 신규 MVP에서 레거시 API를 기본 비활성화

**Files:**
- Create: `src/main/kotlin/com/siheungbootcamp/teamd/global/config/LegacyApiProperties.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/vote/VoteController.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/vote/VotePlaceUsageChecker.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/course/CourseController.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/course/PublicScheduleController.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/course/CoursePlaceUsageChecker.kt`
- Modify: `src/main/kotlin/com/siheungbootcamp/teamd/domain/departure/DepartureController.kt`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/kotlin/com/siheungbootcamp/teamd/domain/vote/P3VoteContractTest.kt`
- Modify: `src/test/kotlin/com/siheungbootcamp/teamd/domain/course/P4CourseContractTest.kt`
- Modify: `src/test/kotlin/com/siheungbootcamp/teamd/domain/departure/P5DepartureContractTest.kt`
- Create: `src/test/kotlin/com/siheungbootcamp/teamd/contract/P7LegacyApiIsolationTest.kt`

**Consumes:**
- 기존 레거시 코드·테이블·회귀 테스트

**Produces:**
- 기본값 `app.legacy-api-enabled=false`
- 기본 프로필에서 Vote·Course·Departure 404 및 OpenAPI 제외
- 명시적으로 enable한 회귀 프로필에서 기존 테스트 유지

- [ ] **Step 1: 기본 비활성 실패 테스트 작성**

```kotlin
@SpringBootTest(properties = ["app.legacy-api-enabled=false"])
class P7LegacyApiIsolationTest {
    @Test
    fun `vote course departure routes are absent`() {
        // 각 대표 경로가 404이고 /v3/api-docs에 없는지 검증
    }
}
```

- [ ] **Step 2: property 조건 적용**

레거시 Controller와 레거시 `PlaceUsageChecker` 구현체에 적용한다.

```kotlin
@ConditionalOnProperty(
    prefix = "app",
    name = ["legacy-api-enabled"],
    havingValue = "true",
)
```

기본 설정:

```yaml
app:
  legacy-api-enabled: false
```

레거시 계약 테스트만 다음 property를 사용한다.

```kotlin
@SpringBootTest(properties = ["app.legacy-api-enabled=true"])
```

- [ ] **Step 3: 신규 MVP 권한 설명 정리**

`PlaceController.deletePlace`의 Swagger 설명을 실제 동작과 맞춘다.

```text
같은 보드의 활성 참여자는 누구나 후보를 보관할 수 있습니다.
```

기본 프로필에서는 Vote/Course usage checker가 bean이 아니므로 레거시 참조가 후보 보관을 막지 않는다.

- [ ] **Step 4: 격리·회귀 테스트**

Run:

```bash
./gradlew test --tests '*P7LegacyApiIsolationTest' --tests '*P3VoteContractTest' --tests '*P4CourseContractTest' --tests '*P5DepartureContractTest'
```

Expected: 기본 계약은 레거시 404, opt-in 레거시 회귀는 PASS.

- [ ] **Step 5: 커밋**

```bash
git add src/main/kotlin/com/siheungbootcamp/teamd/global/config src/main/kotlin/com/siheungbootcamp/teamd/domain/vote src/main/kotlin/com/siheungbootcamp/teamd/domain/course src/main/kotlin/com/siheungbootcamp/teamd/domain/departure src/main/resources/application.yml src/test/kotlin/com/siheungbootcamp/teamd
git commit -m "P7 레거시 결정 기능을 신규 후보 보드 계약에서 격리한다"
```

---

### Task 7: 전체 검증과 FE 계약 동결

**Files:**
- Modify: `docs/plan/10-fe-contract-sync.md`
- Modify: `README.md`
- Verify: `../../../docs/specs/화면연동명세서_v1.0.md`
- Verify: generated `/v3/api-docs`

**Consumes:**
- Task 1~6 결과

**Produces:**
- FE가 실제 서버에 붙을 수 있는 최종 OpenAPI
- 최신 실행 방법과 연동 순서
- P7 완료 증거

- [ ] **Step 1: canonical 경로 정적 검사**

Run:

```bash
rg -n 'place-candidates|address-candidates|coordinate-address' src/main/kotlin
```

Expected: 0건.

Run:

```bash
rg -n 'Tmap|tmap' src/main/kotlin/com/siheungbootcamp/teamd/domain/area src/main/kotlin/com/siheungbootcamp/teamd/domain/place
```

Expected: 0건.

- [ ] **Step 2: 핵심 P7 계약 테스트**

Run:

```bash
./gradlew test --tests '*P7*'
```

Expected: PASS.

- [ ] **Step 3: 기존 후보 보드 회귀**

Run:

```bash
./gradlew test --tests '*P1ContractTest' --tests '*PlaceContractTest' --tests '*P3CommentContractTest' --tests '*P6CandidateBoardContractTest'
```

Expected: PASS.

- [ ] **Step 4: 전체 빌드**

Run:

```bash
./gradlew build
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: OpenAPI와 화면 명세 비교**

로컬 서버를 test profile로 실행하고 `/v3/api-docs`를 저장한다.

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
curl -fsS http://localhost:8080/v3/api-docs > /tmp/teamd-openapi.json
```

확인:

- `API명세서_v1.1.md`의 canonical API 26개가 모두 존재
- Vote·Course·Departure 경로 없음
- `Place`, `Comment`, 지역 결과 필드명이 API 명세와 일치
- `Authorization: Bearer` 보안 스키마 존재

- [ ] **Step 6: FE 동기화 문서 갱신**

`10-fe-contract-sync.md`를 PG-01~PG-10 기준으로 바꾸고 다음을 명시한다.

- P7 완료 후 mock이 아닌 실제 서버 연동 가능
- Kakao Maps JS는 FE, Kakao Local·ODsay는 BE
- 지역 job 2초 폴링
- 공통 영역 nullable
- 지도 클릭 좌표는 `/search/nearby-places`
- 좋아요이며 투표가 아님
- 현재 선택은 확정이 아님
- 레거시 API는 기본 비활성

- [ ] **Step 7: README 갱신**

로컬 실행, 환경변수, Swagger URL, Testcontainers 요구사항, `app.legacy-api-enabled` 기본값과 테스트 전용 사용법을 기록한다.

- [ ] **Step 8: 최종 커밋**

```bash
git add docs/plan/10-fe-contract-sync.md README.md
git commit -m "P7 프론트 연동 계약과 실행 기준을 공개한다"
```

---

## P7 완료 게이트

| ID | 검증 | 완료 조건 |
|---|---|---|
| V7-1 | canonical OpenAPI | canonical API 26개 존재, 레거시 경로 없음 |
| V7-2 | 검색 계약 | canonical 경로·DTO, 최대 15개 |
| V7-3 | 주변 검색 | 공통 영역 밖 좌표 성공, 검색만으로 Place 미생성 |
| V7-4 | 권한 | 모든 활성 참여자의 후보·좋아요·현재 선택 동작 |
| V7-5 | 지역 중심 | 시흥 3개 출발지에서 중심 근처 기준점 우선 |
| V7-6 | 정상 빈 결과 | `commonArea=null`, `anchors=[]`도 `SUCCEEDED` |
| V7-7 | 개인정보 | 익명 `areaId`, 타인 출발지·participant 매핑 미노출 |
| V7-8 | 비용 | ODsay N회, Kakao 최대 3회, TMAP 0회 |
| V7-9 | 레거시 격리 | 기본 404·OpenAPI 제외, opt-in 회귀 PASS |
| V7-10 | DB | V3 migration 및 기존 area data 보존 |
| V7-11 | 전체 회귀 | `./gradlew build` 성공 |
| V7-12 | FE handoff | OpenAPI와 화면 연동 명세 불일치 0건 |

P7이 끝나기 전에는 FE가 실제 API에 맞춘 임시 DTO 변환 코드를 작성하지 않는다. P7 완료 후 `화면연동명세서_v1.0.md` 순서대로 프론트 연동을 시작한다.

## 명시적으로 하지 않는 것

- 레거시 Vote·Course·Departure 테이블과 서비스 코드의 물리 삭제
- V1·V2 migration 수정
- 환승역 노선별 중복 병합
- 서울–부산 장거리 최적화
- TMAP 후보 전수 평가
- 자체 리뷰·별점·인기도 점수
- 지도 탐색 핀 영속화
- SSE·WebSocket
- Redis·메시지 큐·별도 워커
