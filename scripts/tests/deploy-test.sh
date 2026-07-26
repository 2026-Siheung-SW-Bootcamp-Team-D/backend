#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "${TEST_DIR}"' EXIT
mkdir -p "${TEST_DIR}/bin" "${TEST_DIR}/teamd"

cp "${ROOT_DIR}/docker-compose.prod.yml" "${TEST_DIR}/teamd/docker-compose.prod.yml"
cp "${ROOT_DIR}/dynamic.yml.template" "${TEST_DIR}/teamd/dynamic.yml.template"
cp "${ROOT_DIR}/scripts/sync-secrets.sh" "${TEST_DIR}/teamd/sync-secrets.sh"
printf '%s\n' \
  'APP_IMAGE=registry/teamd:old-sha' \
  'DB_URL=jdbc:postgresql://db/teamd' \
  'DB_USERNAME=teamd' \
  'DB_PASSWORD=not-a-real-secret' \
  'TOKEN_PEPPER=not-a-real-pepper' \
  'ORIGIN_ENC_KEY=not-a-real-origin-key' \
  'KAKAO_REST_KEY=test' \
  'ODSAY_API_KEY=test' \
  'TMAP_APP_KEY=test' \
  'API_DOMAIN=old-api.example.com' \
  'FRONTEND_BASE_URL=https://old.example.com' \
  'CORS_ALLOWED_ORIGINS=https://old.example.com' \
  'CORS_ALLOWED_ORIGIN_PATTERNS=https://team-d-*.vercel.app' > "${TEST_DIR}/teamd/.env"
printf '%s\n' 'old-sha' > "${TEST_DIR}/teamd/current_sha"

cat > "${TEST_DIR}/bin/docker" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${DOCKER_CALLS}"
if [[ "$*" == *"pull app"* ]] && grep -q 'broken-sha' "${TEAMD_DIR}/.env"; then
  exit 42
fi
MOCK
cat > "${TEST_DIR}/bin/curl" <<'MOCK'
#!/usr/bin/env bash
exit 0
MOCK
cat > "${TEST_DIR}/bin/gcloud" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${GCLOUD_CALLS}"
secret_name=''
previous_argument=''
for argument in "$@"; do
  case "${argument}" in
    --secret=*) secret_name="${argument#--secret=}" ;;
  esac
  if [[ "${previous_argument}" == '--secret' ]]; then
    secret_name="${argument}"
  fi
  previous_argument="${argument}"
done
if [[ "${MISSING_SECRET:-}" == "${secret_name}" ]]; then
  exit 65
fi
if [[ "${INVALID_SECRET:-}" == "${secret_name}" ]]; then
  printf '%s' 'test'
  exit 0
fi
case "${secret_name}" in
  DB_PASSWORD) printf '%s' 'synced-db-password' ;;
  TOKEN_PEPPER) printf '%s' 'synced-token-pepper' ;;
  ORIGIN_ENC_KEY) printf '%s' 'AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=' ;;
  KAKAO_REST_KEY) printf '%s' '0123456789abcdef0123456789abcdef' ;;
  ODSAY_API_KEY) printf '%s' 'synced-odsay-key' ;;
  TMAP_APP_KEY) printf '%s' 'synced-tmap-key' ;;
  *) exit 64 ;;
esac
MOCK
cat > "${TEST_DIR}/bin/stat" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == '-c' && "${2:-}" == '%a' ]]; then
  printf '%s\n' 600
  exit 0
fi
exit 1
MOCK
cat > "${TEST_DIR}/teamd/smoke-test.sh" <<'MOCK'
#!/usr/bin/env bash
exit 0
MOCK
chmod +x "${TEST_DIR}/bin/docker" "${TEST_DIR}/bin/curl" "${TEST_DIR}/bin/gcloud" "${TEST_DIR}/bin/stat" "${TEST_DIR}/teamd/smoke-test.sh" "${TEST_DIR}/teamd/sync-secrets.sh"

export PATH="${TEST_DIR}/bin:${PATH}"
export TEAMD_DIR="${TEST_DIR}/teamd"
export DOCKER_CALLS="${TEST_DIR}/docker-calls.log"
export GCLOUD_CALLS="${TEST_DIR}/gcloud-calls.log"

file_mode() {
  local file="$1"
  if stat -c '%a' "${file}" >/dev/null 2>&1; then
    stat -c '%a' "${file}"
  else
    stat -f '%Lp' "${file}"
  fi
}

"${ROOT_DIR}/scripts/deploy.sh" good-sha https://example.invalid registry/teamd api.yeondang.com https://yeondang.com https://yeondang.com,https://www.yeondang.com test-project
grep -q '^APP_IMAGE=registry/teamd:good-sha$' "${TEAMD_DIR}/.env"
grep -q '^API_DOMAIN=api.yeondang.com$' "${TEAMD_DIR}/.env"
grep -q '^FRONTEND_BASE_URL=https://yeondang.com$' "${TEAMD_DIR}/.env"
grep -q '^CORS_ALLOWED_ORIGINS=https://yeondang.com,https://www.yeondang.com$' "${TEAMD_DIR}/.env"
grep -q '^CORS_ALLOWED_ORIGIN_PATTERNS=https://team-d-\*.vercel.app$' "${TEAMD_DIR}/.env"
grep -q '^DB_PASSWORD=synced-db-password$' "${TEAMD_DIR}/.env"
grep -q '^TOKEN_PEPPER=synced-token-pepper$' "${TEAMD_DIR}/.env"
grep -q '^ORIGIN_ENC_KEY=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=$' "${TEAMD_DIR}/.env"
grep -q '^KAKAO_REST_KEY=0123456789abcdef0123456789abcdef$' "${TEAMD_DIR}/.env"
grep -q '^ODSAY_API_KEY=synced-odsay-key$' "${TEAMD_DIR}/.env"
grep -q '^TMAP_APP_KEY=synced-tmap-key$' "${TEAMD_DIR}/.env"
test "$(wc -l < "${GCLOUD_CALLS}" | tr -d ' ')" = 6
grep -q "Host(\`api.yeondang.com\`)" "${TEAMD_DIR}/dynamic.yml"
test "$(file_mode "${TEAMD_DIR}/.env")" = 600
grep -q '^good-sha$' "${TEAMD_DIR}/current_sha"
grep -q '^old-sha$' "${TEAMD_DIR}/previous_sha"

cp "${TEAMD_DIR}/.env" "${TEST_DIR}/expected-env"
export INVALID_SECRET=KAKAO_REST_KEY
if "${ROOT_DIR}/scripts/deploy.sh" invalid-secret-sha https://example.invalid registry/teamd api.yeondang.com https://yeondang.com https://yeondang.com,https://www.yeondang.com test-project; then
  echo 'placeholder Kakao secret 배포가 성공으로 끝났습니다.' >&2
  exit 1
fi
unset INVALID_SECRET
cmp "${TEST_DIR}/expected-env" "${TEAMD_DIR}/.env"

export MISSING_SECRET=ODSAY_API_KEY
if "${ROOT_DIR}/scripts/deploy.sh" missing-secret-sha https://example.invalid registry/teamd api.yeondang.com https://yeondang.com https://yeondang.com,https://www.yeondang.com test-project; then
  echo 'Secret Manager 접근 실패 배포가 성공으로 끝났습니다.' >&2
  exit 1
fi
unset MISSING_SECRET
cmp "${TEST_DIR}/expected-env" "${TEAMD_DIR}/.env"

if "${ROOT_DIR}/scripts/deploy.sh" broken-sha https://example.invalid registry/teamd api.yeondang.com https://yeondang.com https://yeondang.com,https://www.yeondang.com test-project; then
  echo '깨진 이미지 배포가 성공으로 끝났습니다.' >&2
  exit 1
fi
grep -q '^APP_IMAGE=registry/teamd:good-sha$' "${TEAMD_DIR}/.env"
grep -q '^good-sha$' "${TEAMD_DIR}/current_sha"
grep -q '^old-sha$' "${TEAMD_DIR}/previous_sha"
grep -q 'compose.*up -d' "${DOCKER_CALLS}"
if grep -q 'not-a-real-secret' "${DOCKER_CALLS}"; then
  echo '배포 로그에 비밀값이 노출됐습니다.' >&2
  exit 1
fi

if "${ROOT_DIR}/scripts/deploy.sh" missing-origin https://example.invalid registry/teamd api.yeondang.com https://yeondang.com https://yeondang.com test-project; then
  echo '필수 운영 origin이 없는 배포가 성공으로 끝났습니다.' >&2
  exit 1
fi
grep -q '^APP_IMAGE=registry/teamd:good-sha$' "${TEAMD_DIR}/.env"

echo 'deploy.sh 성공·자동 롤백 테스트 통과'
