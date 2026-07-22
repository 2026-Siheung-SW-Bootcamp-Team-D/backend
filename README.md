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

## API 문서와 수동 테스트

- Swagger UI: <http://localhost:8080/swagger-ui/index.html>
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>
- 도메인별 수동 테스트: [`docs/manual-testing/README.md`](docs/manual-testing/README.md)
