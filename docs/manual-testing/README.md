# API 수동 테스트 안내

이 디렉터리는 사람이 로컬 서버를 상대로 API 계약을 확인할 때 사용하는 문서다. API가 늘어나도 한 파일이 지나치게 커지지 않도록 도메인별로 분리한다.

## 시작 전 공통 준비

1. 저장소 루트의 [`README.md`](../../README.md)에 따라 PostgreSQL과 애플리케이션을 실행한다.
2. `curl http://localhost:8080/actuator/health`의 `status`가 `UP`인지 확인한다.
3. 실제 운영 비밀이나 외부 API 키를 사용하지 않는다.
4. Swagger UI는 <http://localhost:8080/swagger-ui/index.html>, 원본 JSON은 <http://localhost:8080/v3/api-docs>에서 확인한다.

## 인증 공통 규칙

보드 생성과 초대 확인·참여 응답에서 받은 `participantToken`만 인증에 사용한다.

- Swagger UI: 오른쪽 위 **Authorize**를 누르고 토큰 값만 입력한다. `Bearer `는 입력하지 않는다.
- curl: `Authorization: Bearer {participantToken}` 헤더를 보낸다.
- `participantId`, `role`, 토큰 소유자의 `boardId`는 서버가 토큰에서 해석한다. 쿼리나 본문으로 보내지 않는다.
- URL의 `{boardId}`에는 `brd_...` 공개 ID만 넣는다. 토큰 secret을 붙이지 않는다.

## 도메인별 문서

| 단계 | 도메인 | 엔드포인트 | 문서 |
|---|---|---:|---|
| P1 | 보드·초대·참여자 | 1~8 | [`board-participant.md`](board-participant.md) |
| P2 | 장소 검색·등록 | 9~15 | 구현 후 `place.md` 추가 |
| P3 | 댓글·투표 | 16~24 | 구현 후 `comment-vote.md` 추가 |
| P6 | 지역 찾기 | 25~26 | 구현 후 `area-search.md` 추가 |
| P4 | 코스·공개 일정 | 27~31, 34 | 구현 후 `course-public.md` 추가 |
| P5 | 출발 안내 | 32~33 | 구현 후 `departure.md` 추가 |

각 문서는 Swagger UI 절차, curl 절차, 정상 흐름, 권한 및 주요 오류 확인 순서로 작성한다.
