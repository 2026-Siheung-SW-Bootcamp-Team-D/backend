# teamd-backend

## 로컬 실행

1. `.env.example`을 `.env`로 복사하고 로컬 전용 값을 채운다. `ORIGIN_ENC_KEY`는 `openssl rand -base64 32`로 만든다.
2. `docker compose -f docker-compose.local.yml up -d`로 PostgreSQL 16을 실행한다.
3. `.env` 값을 셸 환경으로 주입하고 `./gradlew bootRun --args='--spring.profiles.active=local'`을 실행한다.
4. `curl http://localhost:8080/actuator/health`가 `{"status":"UP"}`을 반환하는지 확인한다.

스키마는 시작 시 Flyway `V1__baseline.sql`로 생성된다. H2는 사용하지 않는다.
