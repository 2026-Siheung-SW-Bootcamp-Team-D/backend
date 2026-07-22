# 보드·초대·참여자 API 수동 테스트

대상은 P1 엔드포인트 1~8이다. 정상 흐름을 먼저 끝낸 뒤 권한·보안·CLOSED 보드 오류를 확인한다.

## 테스트 데이터 규칙

- 보드 이름: 2~40자
- 시작일: 실행일보다 이후
- 종료일: 시작일 이상이며 기간은 최대 30일
- 닉네임: 1~20자
- 좌표: WGS84, `lon`은 경도이고 `lat`은 위도

문서의 `2099-01-01`은 오래 유지되는 예시일 뿐이다. 실제 서비스 데이터로 남길 때는 가까운 미래 날짜를 사용한다.

## Swagger UI로 테스트

### 1. 보드와 HOST 생성

1. <http://localhost:8080/swagger-ui/index.html>을 연다.
2. **P1 보드·초대·참여자**에서 `POST /api/v1/boards`를 펼친다.
3. **Try it out**을 누르고 다음 본문으로 실행한다.

```json
{
  "name": "주말 모임",
  "dateRange": {
    "start": "2099-01-01",
    "end": "2099-01-02"
  },
  "purpose": "저녁 식사",
  "hostNickname": "종민"
}
```

4. `201` 응답에서 아래 값을 따로 보관한다.
   - `board.boardId`: `brd_...`
   - `participant.participantToken`: `ptc_....secret`
   - `invitation.inviteCode`
5. Swagger 오른쪽 위 **Authorize**를 누르고 HOST `participantToken` 값만 입력한다.

### 2. HOST 인증 API

다음 API를 순서대로 실행한다. `{boardId}`에는 `brd_...` 값만 입력한다.

| 엔드포인트 | 입력 | 기대 결과 |
|---|---|---|
| `GET /boards/{boardId}` | 생성 응답의 `boardId` | `200`, 기본 정보와 집계 |
| `PATCH /boards/{boardId}` | `{"name":"토요일 저녁 모임"}` | `200`, 이름 변경 |
| `GET /boards/{boardId}/invitation` | `boardId` | `200`, 같은 초대 코드 원문 |
| `GET /invitations/{inviteCode}` | 생성 응답의 코드 | `200`, `joinable: true` |

Swagger가 `participantId`, `role`, 별도 principal `boardId`를 요구하면 최신 서버로 재기동했는지 확인한다. 이 값들은 사용자 입력이 아니다.

### 3. MEMBER 참여와 인증 전환

1. `POST /invitations/{inviteCode}/participants`를 인증 없이 실행한다.

```json
{"nickname":"하늘"}
```

2. `201` 응답의 `participantToken`을 MEMBER 토큰으로 보관한다.
3. **Authorize**에서 기존 HOST 토큰을 지우고 MEMBER 토큰을 입력한다.
4. `PATCH /boards/{boardId}/participants/me`에 다음 본문을 보낸다.

```json
{
  "origin": {
    "label": "정왕역",
    "lon": 126.7426,
    "lat": 37.3459,
    "source": "MANUAL_PIN"
  }
}
```

5. `GET /boards/{boardId}/participants`에서 MEMBER 본인의 `origin`에 `label`, `lon`, `lat`가 보이는지 확인한다.
6. 다시 HOST 토큰으로 Authorize한 뒤 같은 목록을 조회한다. MEMBER의 `origin`에는 `registered: true`만 있고 `label`, `lon`, `lat` 키가 없어야 한다.

### 4. 권한과 CLOSED 보드

1. MEMBER 토큰으로 `PATCH /boards/{boardId}`를 호출하면 `403 FORBIDDEN`이어야 한다.
2. HOST 토큰으로 `PATCH /boards/{boardId}`에 `{"status":"CLOSED"}`를 보내면 `200`과 `status: CLOSED`가 반환되어야 한다.
3. MEMBER 토큰으로 `PATCH /boards/{boardId}/participants/me`를 다시 호출하면 `409 RESOURCE_CONFLICT`여야 한다.

## curl로 정상 흐름 테스트

응답 값 추출을 위해 `jq`를 사용한다. HTTP 상태와 헤더까지 보려면 `curl`에 `-i`를 추가한다.

### 1. 보드 생성과 변수 준비

```bash
curl -sS -X POST http://localhost:8080/api/v1/boards \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "주말 모임",
    "dateRange": {"start": "2099-01-01", "end": "2099-01-02"},
    "purpose": "저녁 식사",
    "hostNickname": "종민"
  }' | tee /tmp/teamd-board.json | jq

BOARD_ID=$(jq -r '.board.boardId' /tmp/teamd-board.json)
HOST_TOKEN=$(jq -r '.participant.participantToken' /tmp/teamd-board.json)
INVITE_CODE=$(jq -r '.invitation.inviteCode' /tmp/teamd-board.json)
```

### 2. 보드 조회·수정과 초대 확인

```bash
curl -sS "http://localhost:8080/api/v1/boards/$BOARD_ID" \
  -H "Authorization: Bearer $HOST_TOKEN" | jq

curl -sS -X PATCH "http://localhost:8080/api/v1/boards/$BOARD_ID" \
  -H "Authorization: Bearer $HOST_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"토요일 저녁 모임"}' | jq

curl -sS "http://localhost:8080/api/v1/boards/$BOARD_ID/invitation" \
  -H "Authorization: Bearer $HOST_TOKEN" | jq

curl -sS "http://localhost:8080/api/v1/invitations/$INVITE_CODE" | jq
```

### 3. MEMBER 참여와 출발지 등록

```bash
curl -sS -X POST "http://localhost:8080/api/v1/invitations/$INVITE_CODE/participants" \
  -H 'Content-Type: application/json' \
  -d '{"nickname":"하늘"}' | tee /tmp/teamd-member.json | jq

MEMBER_TOKEN=$(jq -r '.participantToken' /tmp/teamd-member.json)

curl -sS -X PATCH "http://localhost:8080/api/v1/boards/$BOARD_ID/participants/me" \
  -H "Authorization: Bearer $MEMBER_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "origin": {
      "label": "정왕역",
      "lon": 126.7426,
      "lat": 37.3459,
      "source": "MANUAL_PIN"
    }
  }' | jq
```

### 4. 출발지 노출 범위 확인

```bash
# HOST 조회: MEMBER 출발지는 registered 외 상세 키가 없어야 한다.
curl -sS "http://localhost:8080/api/v1/boards/$BOARD_ID/participants" \
  -H "Authorization: Bearer $HOST_TOKEN" | jq

# MEMBER 조회: 본인 출발지 상세가 보여야 한다.
curl -sS "http://localhost:8080/api/v1/boards/$BOARD_ID/participants" \
  -H "Authorization: Bearer $MEMBER_TOKEN" | jq
```

## curl로 오류 계약 테스트

### MEMBER의 HOST API 접근

```bash
curl -sS -X PATCH "http://localhost:8080/api/v1/boards/$BOARD_ID" \
  -H "Authorization: Bearer $MEMBER_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"권한 없는 변경"}' | jq
```

기대 결과는 `403 FORBIDDEN`이다.

### 보드 종료 후 쓰기 차단

```bash
curl -sS -X PATCH "http://localhost:8080/api/v1/boards/$BOARD_ID" \
  -H "Authorization: Bearer $HOST_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"status":"CLOSED"}' | jq

curl -sS -X PATCH "http://localhost:8080/api/v1/boards/$BOARD_ID/participants/me" \
  -H "Authorization: Bearer $MEMBER_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"nickname":"변경 불가"}' | jq
```

두 번째 요청은 `409 RESOURCE_CONFLICT`여야 한다.

## 테스트 종료

애플리케이션을 `Ctrl+C`로 종료한다. PostgreSQL 컨테이너만 내릴 때는 다음을 실행한다.

```bash
docker compose -f docker-compose.local.yml down
```

DB 데이터까지 지우려면 `down -v`가 필요하지만 복구할 수 없으므로 테스트 데이터를 정말 삭제할 때만 사용한다.
