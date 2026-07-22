#!/usr/bin/env bash
set -Eeuo pipefail
set +x

IMAGE_SHA="${1:?사용법: deploy.sh <commit-sha> <api-base-url> <image-repository>}"
API_BASE_URL="${2:?사용법: deploy.sh <commit-sha> <api-base-url> <image-repository>}"
IMAGE_REPOSITORY="${3:?사용법: deploy.sh <commit-sha> <api-base-url> <image-repository>}"
TEAMD_DIR="${TEAMD_DIR:-/opt/teamd}"
COMPOSE_FILE="${COMPOSE_FILE:-${TEAMD_DIR}/docker-compose.prod.yml}"
SMOKE_SCRIPT="${SMOKE_SCRIPT:-${TEAMD_DIR}/smoke-test.sh}"
CURRENT_SHA_FILE="${TEAMD_DIR}/current_sha"
PREVIOUS_SHA_FILE="${TEAMD_DIR}/previous_sha"
ENV_FILE="${TEAMD_DIR}/.env"
ENV_BACKUP="${TEAMD_DIR}/.env.before-deploy"
DEPLOY_SUCCEEDED=false

cd "${TEAMD_DIR}"
test -s "${ENV_FILE}" || { echo "${ENV_FILE}이 없어 배포할 수 없습니다." >&2; exit 1; }
chmod 600 "${ENV_FILE}"

replace_image() {
  local image="$1"
  local temp_env
  temp_env="$(mktemp "${TEAMD_DIR}/.env.XXXXXX")"
  chmod 600 "${temp_env}"
  awk -v image="${image}" '
    BEGIN { replaced = 0 }
    /^APP_IMAGE=/ { print "APP_IMAGE=" image; replaced = 1; next }
    { print }
    END { if (!replaced) print "APP_IMAGE=" image }
  ' "${ENV_FILE}" > "${temp_env}"
  mv "${temp_env}" "${ENV_FILE}"
  chmod 600 "${ENV_FILE}"
}

compose() {
  docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" "$@"
}

rollback() {
  local exit_code=$?
  trap - ERR
  if [[ "${DEPLOY_SUCCEEDED}" == true ]]; then
    return 0
  fi

  echo "배포 검증 실패: 직전 이미지로 자동 롤백합니다." >&2
  if [[ -s "${ENV_BACKUP}" && -s "${CURRENT_SHA_FILE}" ]]; then
    mv "${ENV_BACKUP}" "${ENV_FILE}"
    chmod 600 "${ENV_FILE}"
    compose pull app || true
    compose up -d || true
    echo "자동 롤백 대상: $(cat "${CURRENT_SHA_FILE}")" >&2
  else
    echo "직전 배포 정보가 없어 자동 롤백할 수 없습니다." >&2
  fi
  exit "${exit_code}"
}
trap rollback ERR

cp "${ENV_FILE}" "${ENV_BACKUP}"
chmod 600 "${ENV_BACKUP}"
replace_image "${IMAGE_REPOSITORY}:${IMAGE_SHA}"

compose pull app
compose up -d

for attempt in $(seq 1 30); do
  if curl --fail --silent --show-error --max-time 5 "${API_BASE_URL}/actuator/health" >/dev/null; then
    break
  fi
  if [[ "${attempt}" -eq 30 ]]; then
    echo "health check가 150초 안에 성공하지 못했습니다." >&2
    false
  fi
  sleep 5
done

"${SMOKE_SCRIPT}" "${API_BASE_URL}"

if [[ -s "${CURRENT_SHA_FILE}" ]]; then
  cp "${CURRENT_SHA_FILE}" "${PREVIOUS_SHA_FILE}"
fi
printf '%s\n' "${IMAGE_SHA}" > "${CURRENT_SHA_FILE}"
chmod 600 "${CURRENT_SHA_FILE}" "${PREVIOUS_SHA_FILE}" 2>/dev/null || true
rm -f "${ENV_BACKUP}"
DEPLOY_SUCCEEDED=true
echo "배포 완료: ${IMAGE_SHA}"
