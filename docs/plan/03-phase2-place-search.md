# P2 — 장소 검색 · 장소 등록

> 선행: P1 · 후행: P3, P4 · 병렬: I1(GCP 인프라), P6 앞부분(JTS/ODsay 단위 테스트)
> 엔드포인트: 9~15 (API명세서 6·7절)

---

## 이 단계의 의미

**첫 외부 API 연동**이다. 여기서 만드는 외부 호출 공통 규약(타임아웃·429 백오프·오류 매핑·로깅 마스킹)을 P5(TMAP)·P6(ODsay)가 그대로 재사용한다. 즉 Kakao 어댑터는 "Kakao용 코드"가 아니라 **외부 어댑터의 레퍼런스 구현**으로 만든다.

---

## 작업 목록

### T2-1. 외부 호출 공통 기반 (`global/external/`)
> Wave 1 레인 C에서 골격을 먼저 만들었다면 여기서 완성한다.

- 공통 클라이언트 설정: connect 3s / read 5s 타임아웃
- 429 처리: `Retry-After` 우선, 없으면 **1s·2s·4s 백오프 최대 3회** (API명세서 13절)
- 오류 매핑 — 어댑터는 외부 예외를 그대로 던지지 않고 아래로 변환한다

| 외부 상황 | 내부 오류 |
|---|---|
| 응답 파싱 실패·계약 위반 | `502 EXTERNAL_BAD_RESPONSE` |
| 5xx, 타임아웃, 429 재시도 소진 | `503 EXTERNAL_UNAVAILABLE` |
| 일일 예산 초과 | `503 QUOTA_EXCEEDED` |

- 로깅: **공급자·엔드포인트 종류·상태코드·지연시간·재시도 횟수만.** 검색어 원문·응답 본문·API 키 금지 (아키텍처 8절)
- 공급자별 일일 호출 예산 카운터. 초과 시 `QUOTA_EXCEEDED`

### T2-2. Kakao Local 어댑터
`infra/external/kakao/`

| 내부 메서드 | Kakao API | 사용처 |
|---|---|---|
| `searchKeyword(query, lon?, lat?, radius?)` | 키워드 검색 | 엔드포인트 9 |
| `searchAddress(query)` | 주소 검색 | 10 |
| `coord2Address(lon, lat)` | 좌표→주소 | 11 |
| `searchHubsNearby(...)` | 역·터미널·시청·시장 후보 | P6 허브 수집 |

- Kakao 카테고리 문자열 → `internalCategory`(8종) 매핑 테이블을 **한 파일**에 둔다. 매핑 실패는 `ETC`
- `providerPlaceUrl`은 허용 도메인(`place.map.kakao.com`) 검증 후에만 저장

### T2-3. 검색 엔드포인트 (9~11)
- 저장 자원을 만들지 않는 순수 GET. **`Place` 행을 만들지 않는다** (MVP 완료 기준 2)
- 9: `query` 2~80자, **URL 형식이면 `400 URL_QUERY_NOT_ALLOWED`**, 최대 5개 정규화 반환, 결과 없으면 `200` + 빈 배열 + `hint`
- 10: 결과 없어도 `200` + 빈 배열 (오류 아님)
- 11: `lon`,`lat` 필수. 도로명·지번 둘 다 없어도 오류 아님
- Rate limit: 참여자당 20회/분

### T2-4. 장소 등록·조회·삭제 (12~15)
`domain/place/`

- `Place` 엔티티 (ERD 2.3). `status` = `ACTIVE`/`ARCHIVED`, `deletedAt` soft delete
- 12 `POST /places`: `name` 1~100자, `internalCategory` 8종 검증, `source` ∈ {`SEARCH_SELECT`,`MANUAL_PIN`}, **중복 허용·자동 병합 없음(BR-004)**
- 13 `GET /places`: `category`, `sort`(`RECENT`|`COMMENTS`), `bbox`, 페이지네이션. `commentCount` 포함 (P3 전까지는 0)
- 14 `GET /places/{placeId}`
- 15 `DELETE /places/{placeId}`: **제안자 또는 호스트만**. 참조 중이면 `409 PLACE_IN_USE`. 성공 시 soft delete + `204`. **이미 삭제된 장소를 같은 권한으로 다시 삭제해도 `204`** (멱등)

### T2-5. `PlaceUsageChecker` 인터페이스 ⭐ 병렬 충돌 방지 장치

```
interface PlaceUsageChecker {
    fun findUsage(placeId: Long): PlaceUsage?   // null이면 미사용
}
```

- P2에서는 인터페이스 + 빈 구현 목록만 등록
- P3이 `VotePlaceUsageChecker`, P4가 `CoursePlaceUsageChecker`를 **각자 새 파일로** 추가
- 삭제 서비스는 `List<PlaceUsageChecker>`를 순회 → 하나라도 걸리면 `409 PLACE_IN_USE` + `details`(courseId/orderIndex 등)
- 덕분에 Wave 3의 A·B 레인이 같은 파일을 동시에 수정하지 않는다

---

## ✅ 검증 게이트

| # | 항목 | 방법 |
|---:|---|---|
| V2-1 | E2E: 검색 → 후보 선택 → 장소 등록 → 목록 조회 | 계약 테스트 (16절 2단계). 외부는 stub 서버 |
| V2-2 | **검색만으로 Place가 생성되지 않음** | 검색 3회 호출 후 `place` count = 0 |
| V2-3 | **검색만으로 TMAP·ODsay를 호출하지 않음** | stub 호출 카운터 assert (MVP 완료 기준 3) |
| V2-4 | URL 검색어 차단 | `query=https://...` → `400 URL_QUERY_NOT_ALLOWED` |
| V2-5 | 외부 429 → 백오프 후 성공 | stub이 429 2회 후 200 → 최종 200, 재시도 3회 이하 |
| V2-6 | 외부 5xx → `503 EXTERNAL_UNAVAILABLE` | stub 500 고정 |
| V2-7 | 깨진 JSON → `502 EXTERNAL_BAD_RESPONSE` | stub 비계약 응답 |
| V2-8 | 검색어·API 키가 로그에 없음 | 테스트 중 로그 캡처 후 문자열 부재 assert |
| V2-9 | 삭제 권한 | 제3자 → `403`, 제안자·호스트 → `204`, 재삭제 → `204` |
| V2-10 | 비허용 `providerPlaceUrl` 거부 | 임의 도메인 URL → `400 INVALID_ARGUMENT` |
| V2-11 | 빌드 그린 | `./gradlew build` |

---

## 리스크

| 리스크 | 대응 |
|---|---|
| 실제 Kakao 키로만 테스트해 CI에서 실패 | **모든 테스트는 stub 기준.** 실제 키 호출은 수동 스모크 1회만 |
| 카테고리 매핑이 여러 곳에 흩어짐 | 매핑 테이블 단일 파일 + 매핑 단위 테스트 |
| 삭제 참조 검사를 P3/P4가 각자 하드코딩 | T2-5의 `PlaceUsageChecker` 강제 |
