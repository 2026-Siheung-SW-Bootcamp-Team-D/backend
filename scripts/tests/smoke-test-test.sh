#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "${TEST_DIR}"' EXIT
mkdir -p "${TEST_DIR}/bin"

cat > "${TEST_DIR}/bin/curl" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
output_file=''
url=''
invalid_auth=false
while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --output) output_file="$2"; shift 2 ;;
    --write-out) shift 2 ;;
    --header)
      [[ "$2" == *'invalid-smoke-token'* ]] && invalid_auth=true
      shift 2
      ;;
    --request|--data) shift 2 ;;
    --silent|--show-error) shift ;;
    *) url="$1"; shift ;;
  esac
done
printf '%s\n' "${url}" >> "${CURL_CALLS}"
case "${url}" in
  */actuator/health)
    if [[ "${MISSING_JOB_POLLER:-}" == true ]]; then
      printf '%s' '{"status":"UP","components":{"db":{"status":"UP"}}}' > "${output_file}"
    else
      printf '%s' '{"status":"UP","components":{"jobPoller":{"status":"UP"}}}' > "${output_file}"
    fi
    printf '%s' 200
    ;;
  */api/v1/boards)
    printf '%s' '{"board":{"boardId":"brd_smoke"},"participantToken":"ptc_smoke.secret"}' > "${output_file}"
    printf '%s' 201
    ;;
  */api/v1/boards/brd_smoke/search/addresses*)
    printf '%s' '{"provider":"KAKAO","items":[]}' > "${output_file}"
    printf '%s' 200
    ;;
  */api/v1/boards/brd_smoke/search/places*)
    printf '%s' '{"provider":"KAKAO","items":[]}' > "${output_file}"
    printf '%s' 200
    ;;
  */api/v1/boards/brd_smoke)
    if [[ "${invalid_auth}" == true ]]; then
      printf '%s' '{"error":{"code":"UNAUTHORIZED"}}' > "${output_file}"
      printf '%s' 401
    else
      printf '%s' '{"boardId":"brd_smoke"}' > "${output_file}"
      printf '%s' 200
    fi
    ;;
  *) exit 65 ;;
esac
MOCK
chmod +x "${TEST_DIR}/bin/curl"

export PATH="${TEST_DIR}/bin:${PATH}"
export CURL_CALLS="${TEST_DIR}/curl-calls.log"

"${ROOT_DIR}/scripts/smoke-test.sh" https://api.example.test

grep -q '/api/v1/boards/brd_smoke/search/addresses?q=' "${CURL_CALLS}"
grep -q '/api/v1/boards/brd_smoke/search/places?q=' "${CURL_CALLS}"

export MISSING_JOB_POLLER=true
if "${ROOT_DIR}/scripts/smoke-test.sh" https://api.example.test; then
  echo 'jobPoller health가 없는데 smoke가 성공했습니다.' >&2
  exit 1
fi

echo 'smoke-test.sh Kakao 검색 검증 통과'
