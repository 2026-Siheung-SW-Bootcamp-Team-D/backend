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

## 환경변수

| 변수 | 설명 | 예시 |
|---|---|---|
| `DB_PASSWORD` | PostgreSQL 암호 | `local-teamd-password` |
| `TOKEN_PEPPER` | 참여 토큰 HMAC pepper | `local-only-random-pepper` |
| `ORIGIN_ENC_KEY` | 출발지 좌표 AES 암호화 키 (base64) | `openssl rand -base64 32` 결과 |
| `KAKAO_REST_KEY` | Kakao Local API 키 (테스트에서는 stub 사용) | 테스트 불필요 |
| `ODSAY_API_KEY` | ODsay API 키 (테스트에서는 stub 사용) | 테스트 불필요 |

## 프로필별 설정

| 프로필 | 용도 | DB | 외부 API | 
|---|---|---|---|
| `local` | 개발용 | PostgreSQL (Docker) | 실제 호출 |
| `test` | 통합 테스트 | Testcontainers (PostgreSQL) | Stub 서버 |

**`app.legacy-api-enabled` (기본값: `false`)**
- `false`: P7 신규 API만 활성화 (Place, Comment, 지역)
- `true`: P3-P5 레거시 API도 활성화 (Vote, Course, Departure) - 레거시 테스트에서만 사용

## API 문서와 수동 테스트

- Swagger UI: <http://localhost:8080/swagger-ui/index.html>
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>
- 도메인별 수동 테스트: [`docs/manual-testing/README.md`](docs/manual-testing/README.md)

## 테스트 실행

```bash
# 전체 테스트 (Testcontainers 자동 사용)
./gradlew test

# 특정 테스트만 실행
./gradlew test --tests 'P7*'
./gradlew test --tests '*PlaceContractTest'

# 레거시 API 테스트 (P3-P5)
./gradlew test --tests '*P3*,*P4*,*P5*'
```

테스트 실행 시:
- PostgreSQL, Kakao Local, ODsay 등 외부 의존성은 **Testcontainers 또는 stub 서버**로 자동 제공
- 실제 API 키 불필요
- 각 테스트 후 데이터 자동 정리
