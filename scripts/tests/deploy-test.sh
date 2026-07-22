#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "${TEST_DIR}"' EXIT
mkdir -p "${TEST_DIR}/bin" "${TEST_DIR}/teamd"

cp "${ROOT_DIR}/docker-compose.prod.yml" "${TEST_DIR}/teamd/docker-compose.prod.yml"
printf '%s\n' 'APP_IMAGE=registry/teamd:old-sha' 'DB_PASSWORD=not-a-real-secret' > "${TEST_DIR}/teamd/.env"
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
cat > "${TEST_DIR}/teamd/smoke-test.sh" <<'MOCK'
#!/usr/bin/env bash
exit 0
MOCK
chmod +x "${TEST_DIR}/bin/docker" "${TEST_DIR}/bin/curl" "${TEST_DIR}/teamd/smoke-test.sh"

export PATH="${TEST_DIR}/bin:${PATH}"
export TEAMD_DIR="${TEST_DIR}/teamd"
export DOCKER_CALLS="${TEST_DIR}/docker-calls.log"

"${ROOT_DIR}/scripts/deploy.sh" good-sha https://example.invalid registry/teamd
grep -q '^APP_IMAGE=registry/teamd:good-sha$' "${TEAMD_DIR}/.env"
grep -q '^good-sha$' "${TEAMD_DIR}/current_sha"
grep -q '^old-sha$' "${TEAMD_DIR}/previous_sha"

if "${ROOT_DIR}/scripts/deploy.sh" broken-sha https://example.invalid registry/teamd; then
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

echo 'deploy.sh 성공·자동 롤백 테스트 통과'
