# I0~I3 — 컨테이너화 · GCP 인프라 · CI/CD · 관측성

> 선행: I0는 P0 완료 후 즉시 · 병렬: **도메인 단계(P1~P4)와 동시 진행 가능**
> 기준: 시스템아키텍처 6·7·8·10·11절

---

## 원칙

> **자동화보다 수동 배포·롤백이 먼저 동작해야 한다.** (아키텍처 10절)

I2(자동 CI/CD)를 서두르지 말고, I1에서 **손으로 한 번 배포하고 손으로 한 번 롤백**해 본 뒤 자동화한다.

---

## I0 — 컨테이너화 (P0 직후, 도메인 작업과 병렬)

### T-I0-1. Dockerfile
- 멀티스테이지: JDK 21 빌드 → JRE 21 런타임
- 비 root 사용자로 실행
- 레이어 캐시: 의존성 다운로드 단계를 소스 복사보다 앞에
- JVM 옵션: `-XX:MaxRAMPercentage` (VM 메모리 기준)

### T-I0-2. docker-compose (운영용)
- Traefik + Spring 앱 2개 서비스
- **앱의 8080 포트를 호스트에 노출하지 않는다.** Traefik 내부 네트워크로만 (아키텍처 7절)
- Traefik: 80/443, Let's Encrypt ACME, HTTP→HTTPS 리다이렉트

### T-I0-3. 프로필·비밀 주입 경로
- `application-prod.yml`은 **값이 아니라 환경변수 참조만** (`${DB_PASSWORD}` 형태)
- 비밀 목록: `DB_PASSWORD`, `TOKEN_PEPPER`, `ORIGIN_ENC_KEY`, `KAKAO_REST_KEY`, `ODSAY_KEY`, `TMAP_APP_KEY`

**주입 경로 (환경별로 값의 출처만 다르고 앱은 환경변수만 읽는다)**

| 환경 | 출처 | 전달 방식 |
|---|---|---|
| Local | 저장소 루트 `.env` (git 무시). `.env.example`에는 변수 이름만 | compose `env_file` |
| Production | **GCP Secret Manager** | VM 시작 스크립트가 서비스 계정 권한으로 값을 읽어 `0600` 권한 env 파일 생성 → compose `env_file` |

- 앱 코드에 환경별 분기를 만들지 않는다
- **서비스 계정 JSON 키 파일을 저장소·VM에 두지 않는다** (VM 기본 서비스 계정 권한 사용)

> ⚠️ 실행 중인 프로세스 환경에는 평문이 존재할 수밖에 없다. 따라서 보안 기준은 "프로세스 안에 평문이 없을 것"이 아니라 **"git·이미지·로그·오류 응답에 남지 않을 것"** 이다.

### ✅ I0 검증
| # | 항목 |
|---:|---|
| VI0-1 | `docker build` 성공, 이미지 크기 기록 |
| VI0-2 | 로컬 compose로 앱 기동 → `/actuator/health` 200 |
| VI0-3 | **이미지·저장소에 비밀 없음** — `docker history`와 이미지 레이어에 비밀 문자열 0건, `git log -p`에 `.env` 미커밋 (프로세스 env에 값이 있는 것은 정상) |
| VI0-4 | 앱 8080이 호스트에 노출되지 않음 (`docker ps` 포트 매핑 확인) |

---

## I1 — GCP 인프라 (P1~P2와 병렬)

아키텍처 11절 순서를 그대로 따른다. **각 단계마다 완료 확인 후 다음으로 넘어간다.**

| # | 작업 | 완료 확인 |
|---:|---|---|
| I1-1 | GCP 프로젝트·결제·API 활성화 (Compute, Cloud SQL, Artifact Registry, Secret Manager, Logging, Monitoring, Trace) | 콘솔에서 각 서비스 사용 가능 |
| I1-2 | VPC + **Cloud SQL Private IP만** (Public IP 미사용) | VM에서만 DB 연결됨, 외부 연결 실패 |
| I1-3 | VM + **고정 외부 IP** | VM 재시작 후에도 IP 유지 |
| I1-4 | 방화벽: **80/443만 공개**, SSH는 IAP + OS Login | 외부에서 8080·5432 접근 불가 |
| I1-5 | Artifact Registry 저장소 생성 + 이미지 push | VM 서비스 계정으로 pull 성공 |
| I1-6 | VM 서비스 계정 최소 권한 (Secret Accessor / AR Reader / Logs Writer / Monitoring Metric Writer / Trace Agent) | Owner 미부여 확인 |
| I1-7 | Secret Manager에 비밀 등록 + VM 시작 스크립트로 `0600` env 파일 생성 → compose 주입 | 앱 기동 성공, env 파일 권한 `0600`, **로그·이미지·git에 비밀 없음** |
| I1-8 | Traefik + Spring compose를 VM에서 기동 | 고정 IP로 health check 성공 |
| I1-9 | Flyway migration이 Cloud SQL에 적용 | `flyway_schema_history` 확인 |
| I1-10 | **ODsay에 고정 IP 등록** | 운영 VM에서 도달권 호출 성공 (P6 V6-14의 선행) |
| I1-11 | 도메인·DNS·TLS + CORS 운영 오리진 설정 | HTTPS로 API 호출 성공, `*` 미사용 |
| I1-12 | Cloud SQL 자동 백업 활성화 | 백업 1건 존재 |
| I1-13 | **Billing Budget 알림 생성** | 임계치 알림 수신 테스트 |
| I1-14 | **수동 배포 1회 + 수동 롤백 1회** | 직전 이미지 태그로 복구 성공 |

> 운영 도메인은 `https://yeondang.com`, `https://www.yeondang.com`, API는 `https://api.yeondang.com`으로 확정했다. CORS에는 두 운영 오리진과 승인된 Vercel Preview 패턴을 명시적으로 유지한다.

---

## I2 — CI/CD 파이프라인 (P3/P4와 병렬)

### T-I2-1. PR 파이프라인 (`.github/workflows/pr.yml`)
```
on: pull_request
  - JDK 21 setup + Gradle 캐시
  - ./gradlew build   (컴파일 + 단위 + 계약 테스트, Testcontainers 사용)
  - 테스트 리포트 업로드
```
- **PR에서는 배포하지 않는다.** 외부 API 실제 호출도 하지 않는다(전부 stub)

### T-I2-2. 배포 파이프라인 (`.github/workflows/deploy.yml`)
아키텍처 10절 흐름을 그대로 구현한다.

```
on: push to main
  1. ./gradlew build
  2. docker build
  3. Artifact Registry push  — 태그: {commit-sha} + latest
  4. VM에서 이미지 pull & 재기동
  5. Flyway migration (앱 기동 시 적용)
  6. /actuator/health 성공 대기
  7. smoke test 실행
  8. 실패 시 직전 이미지 태그로 롤백
```

- **인증: GitHub Actions Workload Identity Federation** (서비스 계정 JSON 키 다운로드 금지)
- **`latest`만 쓰지 않는다.** 롤백 대상 특정을 위해 commit SHA 태그 필수
- 배포 전 자동 백업 상태 확인 스텝 포함

**배포 실행 경로 (WIF 이후 구체화, 리뷰 결정 #4)**

| 요소 | 결정 |
|---|---|
| CI 전용 서비스 계정 | VM 런타임 계정과 **분리**. `github-deployer@` |
| WIF 신뢰 조건 | 이 저장소의 특정 브랜치(`main`)에서 온 토큰만 CI 계정을 가장(impersonate)하도록 attribute 조건 지정 |
| CI 계정 권한 | Artifact Registry Writer(push), VM에 명령을 내리기 위한 최소 권한. Secret Accessor·Owner 미부여 |
| AR push | `gcloud auth configure-docker {region}-docker.pkg.dev` 후 `docker push {region}-docker.pkg.dev/{project}/{repo}/teamd:{sha}` |
| VM 원격 실행 | `gcloud compute ssh {vm} --tunnel-through-iap --command "cd /opt/teamd && ./deploy.sh {sha}"`. `deploy.sh`가 pull → compose up → health 대기 → smoke → 실패 시 롤백 수행 |
| 직전 SHA 확인 | VM의 `/opt/teamd/current_sha`(또는 compose `.env`의 이미지 태그)에서 읽어 `previous_sha`로 보관. 롤백은 그 태그로 재기동 |
| SSH 방식 | 공개 22 포트 대신 **IAP 터널** 사용 (아키텍처 7절 방화벽 규칙과 일치) |

### T-I2-3. Smoke test 스크립트
운영 배포 직후 실행. **쓰기 부작용이 최소인 순서**로:
1. `GET /actuator/health` → 200
2. `POST /boards` → 201 + 토큰 발급
3. `GET /boards/{id}` (발급 토큰) → 200
4. 잘못된 토큰 → 401
5. (선택) 생성한 보드 종료

### T-I2-4. Migration 안전 규칙 (문서화 + 리뷰 체크)
> ⚠️ **DB migration은 애플리케이션 롤백만으로 되돌아가지 않는다.**

- 최초 운영 배포 이후 **적용된 migration 파일 수정 금지**
- 파괴적 변경은 **컬럼 추가 → 코드 전환 → 구 컬럼 제거** 3단계로 분리
- 한 배포 시점에 "새 코드가 요구하는 스키마"와 "이전 코드가 견딜 수 있는 스키마"가 모두 성립해야 한다

### ✅ I2 검증
| # | 항목 |
|---:|---|
| VI2-1 | PR을 열면 빌드·테스트가 자동 실행되고 실패 시 머지 차단 |
| VI2-2 | main 머지 → Artifact Registry에 commit SHA 태그 이미지 생성 |
| VI2-3 | 배포 후 health check + smoke test 자동 통과 |
| VI2-4 | **의도적으로 깨진 이미지 배포 → 자동 롤백 동작** |
| VI2-5 | 워크플로 로그에 비밀이 마스킹됨 |
| VI2-6 | 서비스 계정 JSON 키가 저장소·시크릿에 없음 (WIF 사용) |

---

## I3 — 관측성 · 배포 리허설 (P5와 병렬)

### T-I3-1. Ops Agent + 구조화 로그
- VM에 Ops Agent 설치 → Cloud Logging / Monitoring / Trace 전송
- **JSON 로그**: `timestamp`, `level`, `service`, `requestId`, `traceId`, `errorCode`, `elapsedMs`
- **기록 금지**: 참여 토큰, 초대 코드, 공개 토큰, API 키, 댓글 본문, 출발지 원문, 전체 검색어
- 외부 API 로그는 공급자·엔드포인트 종류·상태코드·지연시간·재시도 횟수만

### T-I3-2. 메트릭 (Micrometer)
- HTTP 요청 수, 4xx/5xx 비율, p95 응답시간
- JVM heap·GC·커넥션 풀
- **지역 찾기 상태별 개수** (QUEUED·RUNNING·RETRY_WAIT·FAILED)
- **출발 계산 상태별 개수** (CALCULATING·READY·STALE·UNAVAILABLE·FAILED)
- 외부 API 공급자별 호출 수·429·성공률
- VM 자원

### T-I3-2b. 분산 추적 계측 (Trace, 리뷰 결정 #5)
메트릭만으로는 I3 게이트의 "Trace 1건"을 만들 수 없다. 추적을 실제로 발생시키는 계측을 넣는다.

- **OpenTelemetry Java agent를 컨테이너에 부착**(`-javaagent:opentelemetry-javaagent.jar`)해 Spring MVC·JDBC·HTTP 클라이언트 스팬을 자동 생성한다. 앱 코드 수정이 거의 없어 MVP에 적합하다
- exporter는 OTLP → Cloud Trace (Ops Agent의 OTLP 수신 또는 직접 exporter 중 하나). agent 환경변수로 설정하고 코드에 넣지 않는다
- `traceId`를 로그 필드(T-I3-1)와 응답 `requestId` 흐름에 연결해 로그↔트레이스 상호 추적이 되게 한다
- 외부 API 호출(`RestClient`) 스팬에는 공급자·엔드포인트 종류·상태코드만 속성으로 남기고 **URL 쿼리·키·좌표는 스팬 속성에서 제외**한다

> 오버엔지니어링 방지: 커스텀 스팬을 손으로 잔뜩 만들지 않는다. agent 자동 계측으로 시작하고, 특정 구간이 안 보일 때만 최소한의 수동 스팬을 추가한다.

### T-I3-3. 초기 경보
API 5xx 비율 초과 / 외부 API 429 급증 / 작업 FAILED·QUEUED 적체 / VM 자원 임계치 / health check 실패 / DB 연결 실패

### T-I3-4. 배포·롤백 리허설
새 버전 배포 → 문제 상황 가정 → 직전 태그 복구까지 **실제로 한 번 수행하고 소요 시간을 기록**한다.

### ✅ I3 검증
| # | 항목 |
|---:|---|
| VI3-1 | Cloud Logging에 JSON 로그 1건, Monitoring 메트릭 1건, Trace 1건 확인 |
| VI3-2 | **로그 전문 검색으로 토큰·검색어·좌표 문자열 0건** |
| VI3-3 | 경보 1개를 인위적으로 트리거해 알림 수신 |
| VI3-4 | 배포→롤백 리허설 완료, 소요 시간 문서화 |
| VI3-5 | requestId로 로그와 API 오류 응답을 연결 추적 가능 |

---

## 인프라 리스크

| 리스크 | 대응 |
|---|---|
| 단일 VM이라 무중단 불가 | **의도된 제약**(아키텍처 14절). 배포 중 짧은 다운타임을 팀 기준으로 합의 |
| Cloud SQL Private IP 설정 실패로 개발 지연 | I1-2를 P1 초반에 착수해 여유 확보 |
| ODsay 고정 IP 등록 지연 | I1-10을 P6 시작 **전에** 완료. 등록 리드타임을 미리 확인 |
| 예산 초과 | Billing Budget + 외부 API 일일 예산 카운터(P2) 이중 방어 |
| GitHub Actions에서 Testcontainers 실패 | runner의 Docker 사용 가능 여부를 I2 착수 시 최우선 확인 |
