# teamd-backend

## 로컬 실행

필수 도구는 Java 21, Docker Compose, `curl`이다. 아래 예시는 응답 값 추출에 `jq`도 사용한다.

```bash
cp .env.example .env
```

`.env`의 로컬 값을 채운다. 실제 운영 키는 사용하지 않는다.

```dotenv
DB_PASSWORD=local-teamd-password
TOKEN_PEPPER=local-only-random-pepper
ORIGIN_ENC_KEY=<openssl rand -base64 32 결과>
```

PostgreSQL 16을 실행하고 상태가 `healthy`가 될 때까지 기다린다.

```bash
docker compose -f docker-compose.local.yml up -d
docker compose -f docker-compose.local.yml ps
```

별도 터미널에서 환경변수를 주입하고 애플리케이션을 실행한다.

```bash
set -a
source .env
set +a
./gradlew bootRun --args='--spring.profiles.active=local'
```

기동 확인:

```bash
curl -s http://localhost:8080/actuator/health | jq
```

`status`가 `UP`이면 준비된 것이다. 스키마는 시작 시 Flyway `V1__baseline.sql`로 생성되며 H2는 사용하지 않는다.

## Swagger

- Swagger UI: <http://localhost:8080/swagger-ui/index.html>
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>

보드 생성과 참여 API는 인증 없이 실행한다. 나머지는 Swagger UI 오른쪽 위 **Authorize**에서 생성 또는 참여 응답의 `participantToken` 값만 입력한다. `Bearer ` 접두사는 Swagger가 붙이므로 토큰 값만 넣는다.

## P1 수동 테스트

서버가 실행 중인 별도 터미널에서 아래 순서로 엔드포인트 1~8을 확인한다. 날짜는 실행일 이후여야 하며 시작일부터 종료일까지 최대 30일이다.

### 1. 보드와 HOST 생성

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

기대 결과는 `201 Created`이며 토큰은 이 응답에서만 확인할 수 있다.

### 2~5. 보호 보드 조회와 초대 확인

```bash
# 2. 보드 조회: 200
curl -sS "http://localhost:8080/api/v1/boards/$BOARD_ID" \
  -H "Authorization: Bearer $HOST_TOKEN" | jq

# 3. HOST가 보드 이름 수정: 200
curl -sS -X PATCH "http://localhost:8080/api/v1/boards/$BOARD_ID" \
  -H "Authorization: Bearer $HOST_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"토요일 저녁 모임"}' | jq

# 4. HOST의 현재 초대 정보: 200
curl -sS "http://localhost:8080/api/v1/boards/$BOARD_ID/invitation" \
  -H "Authorization: Bearer $HOST_TOKEN" | jq

# 5. 인증 없는 초대 확인: 200
curl -sS "http://localhost:8080/api/v1/invitations/$INVITE_CODE" | jq
```

### 6. MEMBER 참여

```bash
curl -sS -X POST "http://localhost:8080/api/v1/invitations/$INVITE_CODE/participants" \
  -H 'Content-Type: application/json' \
  -d '{"nickname":"하늘"}' | tee /tmp/teamd-member.json | jq

MEMBER_TOKEN=$(jq -r '.participantToken' /tmp/teamd-member.json)
```

기대 결과는 `201 Created`, 역할은 `MEMBER`다.

### 7~8. 참여자 목록과 내 출발지 수정

```bash
# 8. MEMBER 자신의 출발지 등록: 200
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

# 7. HOST가 목록 조회: MEMBER 출발지는 registered만 있고 label/lon/lat 키가 없어야 함
curl -sS "http://localhost:8080/api/v1/boards/$BOARD_ID/participants" \
  -H "Authorization: Bearer $HOST_TOKEN" | jq

# MEMBER 본인이 목록 조회: 자신의 label/lon/lat는 보여야 함
curl -sS "http://localhost:8080/api/v1/boards/$BOARD_ID/participants" \
  -H "Authorization: Bearer $MEMBER_TOKEN" | jq
```

### 권한·종료 상태 확인

```bash
# MEMBER의 보드 수정은 403 FORBIDDEN
curl -sS -X PATCH "http://localhost:8080/api/v1/boards/$BOARD_ID" \
  -H "Authorization: Bearer $MEMBER_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"권한 없는 변경"}' | jq

# HOST가 보드 종료: 200, status=CLOSED
curl -sS -X PATCH "http://localhost:8080/api/v1/boards/$BOARD_ID" \
  -H "Authorization: Bearer $HOST_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"status":"CLOSED"}' | jq

# 종료 뒤 참여자 쓰기는 409 RESOURCE_CONFLICT
curl -sS -X PATCH "http://localhost:8080/api/v1/boards/$BOARD_ID/participants/me" \
  -H "Authorization: Bearer $MEMBER_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"nickname":"변경 불가"}' | jq
```

HTTP 상태까지 함께 보려면 각 `curl` 명령에 `-i`를 추가한다. 수동 테스트 후 로컬 DB를 중지하려면 다음을 실행한다.

```bash
docker compose -f docker-compose.local.yml down
```
