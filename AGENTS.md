# teamd-backend

시흥 SW 부트캠프 Team D의 약속 보드 API 서버다. 클라이언트는 `../frontend`이며, 로그인 대신 보드별 참여 토큰으로 권한을 구분한다.

기능과 API의 원본은 [`docs`](https://github.com/2026-Siheung-SW-Bootcamp-Team-D/docs) repository의 `specs/`다. (../docs/specs)다.
엔드포인트·응답·상태 전이를 바꾸기 전에는 반드시 두 문서를 읽는다. 문서와 구현이 어긋나면 구현으로 덮어쓰지 말고 먼저 합의한다.

## 도구와 외부 연동

- 빌드와 테스트는 wrapper만 사용한다: `./gradlew test`, `./gradlew build`. 로컬 Gradle은 사용하지 않는다.
- 외부 API 키와 운영별 값은 환경변수 또는 프로필별 설정으로만 주입한다. `application.yml`과 소스에 시크릿을 기록하지 않는다.
- Kakao Local·경로 API의 검증 결과는 `../docs/link-poc/RESULTS.md`, `../docs/api-validation/RESULTS.md`에 있다. 외부 API 동작을 다시 가정하거나 재조사하기 전에 이 결과를 확인한다.
- 장소·주소·지역 검색은 Kakao Local만 사용한다. 외부 지도 URL을 입력받거나 서버에서 네이버·카카오 장소 페이지를 수집하지 않는다.
- 외부 API 실패는 제공자 응답 전문, 참여 토큰, 출발지·검색어 원문을 API 오류 본문이나 일반 로그에 노출하지 않는다.

## 현재 제약과 랜드마인

- 현재 DB는 H2 인메모리다. 재시작 뒤 데이터가 사라지는 것은 정상이다. PostgreSQL로 전환할 때는 스키마 변경보다 마이그레이션 도구 도입을 먼저 합의한다.
- `kotlin("plugin.spring")`과 `kotlin("plugin.jpa")`가 프록시와 JPA용 `no-arg` 생성자를 제공한다. `@Entity`, `@MappedSuperclass`, `@Embeddable` 및 Spring stereotype 밖의 클래스는 기본적으로 final이다. 프록시가 필요한 클래스를 임의로 만들 때는 열림 여부를 먼저 확인한다.
- 참여 토큰은 `participantId.secret` 형식이며 secret 원문을 저장하지 않는다. 서버 pepper를 사용한 HMAC-SHA-256 결과만 저장하고, 생성·참여 성공 응답 외에는 토큰을 반환하지 않는다.
- API 시간은 UTC로 저장하고 ISO-8601 offset으로 응답한다. 보드 시간대는 `Asia/Seoul`이다. 좌표는 WGS84이며 `lon`은 경도, `lat`은 위도다.
- 코스 초안 갱신은 `If-Match`/ETag의 낙관적 잠금 계약을 따른다. 충돌은 `412 VERSION_MISMATCH`로 처리하며 조용히 마지막 쓰기로 덮어쓰지 않는다.

## Kotlin · Spring 구현 규칙

### 의존성 주입과 프록시

- 생성자 주입만 사용한다. Kotlin의 primary constructor와 `val` 의존성을 기본으로 하며 `@Autowired`는 단일 생성자에 붙이지 않는다.
- `@Transactional`은 컨트롤러가 아니라 애플리케이션 서비스 경계에 둔다. 읽기 중심 서비스는 클래스 수준 `@Transactional(readOnly = true)`, 쓰기 메서드만 `@Transactional`로 오버라이드한다.
- 같은 객체 내부 호출은 Spring 프록시를 거치지 않는다. 트랜잭션·캐시·비동기 어노테이션이 필요한 메서드를 self-invocation으로 호출하지 않는다.
- 설정값이 여러 개이거나 검증이 필요하면 `@ConfigurationProperties`와 불변 `data class`를 사용한다. 단일 단순 값만 `@Value`로 주입할 수 있다.
- 웹 계층은 입력 검증 → 유스케이스 위임 → HTTP 응답 변환만 맡는다. 컨트롤러와 Spring Data repository에 비즈니스 규칙이나 권한 판단을 넣지 않는다.

### JPA 엔티티와 연관관계

- JPA 엔티티는 반드시 일반 `class`로 작성한다. `data class`, Lombok, 자동 생성 `equals`/`hashCode`/`toString`은 금지한다. 지연 로딩 순회와 프록시 동일성 오류를 막기 위함이다.
- `plugin.jpa`가 JPA용 no-arg 생성자를 생성하므로 Java식 `protected` 빈 생성자를 기계적으로 추가하지 않는다. 생성자는 유효한 생성 경로와 필수 값 초기화만 표현한다.
- 엔티티의 상태 변경은 허용하되, 무분별한 public setter 대신 의도가 드러나는 메서드(`rename`, `close`, `changeSchedule`)로 제한한다. 생성 이후 변경할 수 없는 값은 `val`로 둔다.
- JPA가 채우는 식별자와 nullable 연관관계만 nullable로 둔다. `lateinit`은 초기화 순서가 실제 불변식인 경우에만 사용하며, nullable 문제를 숨기는 용도로 쓰지 않는다.
- 양방향 연관관계는 한쪽을 연관관계 주인으로 명확히 하고, 추가·제거 메서드에서 양쪽을 함께 맞춘다. 컬렉션은 JPA가 관리할 수 있는 mutable 구현체로 유지하되 외부에는 읽기 전용 `List`/`Set`으로 노출한다.
- 대량 update/delete JPQL은 영속성 컨텍스트를 무효화할 수 있다. 필요한 경우 `@Modifying(clearAutomatically = true, flushAutomatically = true)`를 사용하고, 이어지는 엔티티 접근이 오래된 상태를 읽지 않는지 검증한다.
- 카운터·상태 전이 경쟁이 실제로 가능한 경로는 read-modify-write보다 조건부·원자적 update를 우선 검토한다. 중복 방지 전용 repository 메서드는 명세상 필요가 확인될 때만 추가한다.

### DTO·널 안정성·컬렉션

- 요청·응답 DTO는 기본적으로 불변 `data class`와 `val`을 사용한다. Bean Validation은 요청 DTO 경계에 `@field:NotBlank`, `@field:Size`처럼 use-site target을 명시한다.
- Java의 `Optional` 대신 Kotlin nullable 타입을 사용한다. nullable은 부재가 정상 결과인 경우에만 쓰며, DTO·엔티티 필드와 함수 매개변수에 의미 없는 `?`를 붙이지 않는다.
- `!!`는 금지한다. 코드상 보장되는 불변식이 있다면 null을 제거하는 구조나 명시적인 도메인 예외로 표현한다.
- 컬렉션 변환은 입력을 바꾸지 않는 `map`, `filter`, `associate`를 우선한다. 분기·상태 변경으로 읽기 어려워지면 체인보다 일반 `for` 루프를 사용한다.
- `Result`는 도메인 실패 전달 수단으로 쓰지 않는다. 비즈니스 실패는 도메인별 `BusinessException`과 `ErrorCode`로 표현하고, 예외 처리기는 API 명세의 오류 계약으로 변환한다.

### 주석과 학습용 설명

- 이 저장소는 학습용이다. 새 파일의 핵심 클래스·설정·예외 처리기 위에는 KDoc(`/** ... */`)으로 **무엇을 하는지**, **왜 필요한지**, **다른 코드가 어떻게 사용하는지**를 쉬운 말로 설명한다.
- 주석은 코드를 한국어로 다시 읽어 주지 않는다. "값을 대입한다"보다 "외부 API 원문을 응답에 넣지 않는 이유"처럼 코드만 봐서는 알기 어려운 선택과 제약을 적는다.
- 초보자가 오해하기 쉬운 Kotlin·JPA·Spring 프록시 동작, 보안 경계, 상태 전이에는 짧은 설명을 남긴다. 단, 구현과 함께 갱신할 수 없는 긴 튜토리얼이나 오래된 코드 예시는 넣지 않는다.
- 함수 이름과 타입으로 충분히 설명되는 내용에는 주석을 추가하지 않는다. 설명이 길어져도 함수가 이해되지 않으면 먼저 이름·경계·구조를 개선한다.

### HTTP 오류·보안·관측성

- 오류 응답은 `error.code`, `error.message`, `error.details`, `requestId` 형식을 유지한다. 스택트레이스, SQL, 외부 API 본문, 인증 정보, 원문 위치·검색어를 클라이언트에 반환하지 않는다.
- 입력값은 웹 경계에서 형식·길이·범위를 검증하고, 권한과 상태 전이는 유스케이스에서 다시 검증한다. 참여자 토큰 소유권은 수정·삭제마다 확인한다.
- `X-Request-Id`는 UUID 형식만 신뢰하고, 없거나 잘못되면 서버가 생성한다. 응답 헤더와 오류 본문의 requestId는 같은 값이어야 한다.
- 인증 응답은 `Cache-Control: private, no-store`를 적용한다. 공개 일정은 토큰 이외의 개인 출발지·참여 토큰·상세 출발 주소를 절대 포함하지 않는다.
- 장소 삭제, 작업 중복, 외부 API 한도, 낙관적 잠금 충돌은 명세의 HTTP 상태와 오류 코드로 처리한다. 일반 `RuntimeException`을 직접 던지지 않는다.

## 변경과 검증

- API·스키마·도메인 상태·외부 API 호출량을 바꾸는 작업은 추상화나 캐시를 먼저 추가하지 말고 문서의 범위와 제약을 확인한다.
- 새 기능·버그 수정은 기대 동작을 보여 주는 테스트를 먼저 추가한다. 엔티티·서비스 단위 테스트와 HTTP 계약 테스트 중 위험에 맞는 가장 작은 검증을 선택한다.
- 완료 전에는 영향 범위의 테스트를 실행하고, 코드 변경이 있으면 `./gradlew build`를 실행한다. 테스트가 실패하면 실패 원인을 해결하거나 작업 범위를 명확히 보고한다.
- 커밋 메시지는 한국어로 작성한다. 하나의 논리 단위만 담고, 무관한 변경이나 사용자가 진행 중인 변경을 함께 커밋하지 않는다.
