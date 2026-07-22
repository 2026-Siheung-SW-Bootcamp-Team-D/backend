#!/usr/bin/env bash
set -Eeuo pipefail
set +x

BASE_URL="${1:?사용법: smoke-test.sh <api-base-url>}"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TEMP_DIR}"' EXIT

request() {
  local output_file="$1"
  shift
  curl --silent --show-error --output "${output_file}" --write-out '%{http_code}' "$@"
}

health_status="$(request "${TEMP_DIR}/health.json" "${BASE_URL}/actuator/health")"
[[ "${health_status}" == "200" ]] || { echo "health smoke 실패: HTTP ${health_status}" >&2; exit 1; }

today="$(date -u +%F)"
next_week="$(date -u -d '+7 days' +%F 2>/dev/null || date -u -v+7d +%F)"
create_status="$(request "${TEMP_DIR}/create.json" \
  --request POST \
  --header 'Content-Type: application/json' \
  --data "{\"name\":\"배포 스모크 보드\",\"dateRange\":{\"start\":\"${today}\",\"end\":\"${next_week}\"},\"purpose\":\"배포 검증\",\"hostNickname\":\"스모크\"}" \
  "${BASE_URL}/api/v1/boards")"
[[ "${create_status}" == "201" ]] || { echo "보드 생성 smoke 실패: HTTP ${create_status}" >&2; exit 1; }

readarray -t created < <(python3 - "${TEMP_DIR}/create.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as response:
    body = json.load(response)
print(body["board"]["boardId"])
print(body["participant"]["participantToken"])
PY
)
board_id="${created[0]}"
participant_token="${created[1]}"

get_status="$(request "${TEMP_DIR}/get.json" \
  --header "Authorization: Bearer ${participant_token}" \
  "${BASE_URL}/api/v1/boards/${board_id}")"
[[ "${get_status}" == "200" ]] || { echo "보드 조회 smoke 실패: HTTP ${get_status}" >&2; exit 1; }

invalid_status="$(request "${TEMP_DIR}/invalid.json" \
  --header 'Authorization: Bearer invalid-smoke-token' \
  "${BASE_URL}/api/v1/boards/${board_id}")"
[[ "${invalid_status}" == "401" ]] || { echo "잘못된 토큰 smoke 실패: HTTP ${invalid_status}" >&2; exit 1; }

echo "운영 smoke test 통과"

