#!/usr/bin/env bash
set -Eeuo pipefail
set +x

IMAGE_SHA="${1:?사용법: deploy.sh <commit-sha> <api-base-url> <image-repository> <api-domain> <frontend-base-url> <cors-allowed-origins>}"
API_BASE_URL="${2:?사용법: deploy.sh <commit-sha> <api-base-url> <image-repository> <api-domain> <frontend-base-url> <cors-allowed-origins>}"
IMAGE_REPOSITORY="${3:?사용법: deploy.sh <commit-sha> <api-base-url> <image-repository> <api-domain> <frontend-base-url> <cors-allowed-origins>}"
API_DOMAIN="${4:?사용법: deploy.sh <commit-sha> <api-base-url> <image-repository> <api-domain> <frontend-base-url> <cors-allowed-origins>}"
FRONTEND_BASE_URL="${5:?사용법: deploy.sh <commit-sha> <api-base-url> <image-repository> <api-domain> <frontend-base-url> <cors-allowed-origins>}"
CORS_ALLOWED_ORIGINS="${6:?사용법: deploy.sh <commit-sha> <api-base-url> <image-repository> <api-domain> <frontend-base-url> <cors-allowed-origins>}"
TEAMD_DIR="${TEAMD_DIR:-/opt/teamd}"
COMPOSE_FILE="${COMPOSE_FILE:-${TEAMD_DIR}/docker-compose.prod.yml}"
SMOKE_SCRIPT="${SMOKE_SCRIPT:-${TEAMD_DIR}/smoke-test.sh}"
DYNAMIC_TEMPLATE="${DYNAMIC_TEMPLATE:-${TEAMD_DIR}/dynamic.yml.template}"
DYNAMIC_FILE="${DYNAMIC_FILE:-${TEAMD_DIR}/dynamic.yml}"
CURRENT_SHA_FILE="${TEAMD_DIR}/current_sha"
PREVIOUS_SHA_FILE="${TEAMD_DIR}/previous_sha"
ENV_FILE="${TEAMD_DIR}/.env"
ENV_BACKUP="${TEAMD_DIR}/.env.before-deploy"
DEPLOY_SUCCEEDED=false

cd "${TEAMD_DIR}"
test -s "${ENV_FILE}" || { echo "${ENV_FILE}이 없어 배포할 수 없습니다." >&2; exit 1; }
chmod 600 "${ENV_FILE}"

validate_public_settings() {
  [[ "${API_DOMAIN}" =~ ^[A-Za-z0-9.-]+$ ]] || { echo 'API_DOMAIN 형식이 올바르지 않습니다.' >&2; exit 1; }
  [[ "${FRONTEND_BASE_URL}" =~ ^https://[A-Za-z0-9.-]+$ ]] || { echo 'FRONTEND_BASE_URL은 https URL이어야 합니다.' >&2; exit 1; }
  [[ "${CORS_ALLOWED_ORIGINS}" != *'*'* ]] || { echo 'CORS_ALLOWED_ORIGINS에 와일드카드를 사용할 수 없습니다.' >&2; exit 1; }
  [[ "${CORS_ALLOWED_ORIGINS}" != *$'\n'* && "${CORS_ALLOWED_ORIGINS}" != *$'\r'* ]] || { echo 'CORS_ALLOWED_ORIGINS에 줄바꿈을 사용할 수 없습니다.' >&2; exit 1; }
}

replace_deploy_settings() {
  local image="$1"
  local temp_env
  temp_env="$(mktemp "${TEAMD_DIR}/.env.XXXXXX")"
  chmod 600 "${temp_env}"
  awk -v image="${image}" -v domain="${API_DOMAIN}" -v frontend="${FRONTEND_BASE_URL}" -v origins="${CORS_ALLOWED_ORIGINS}" '
    BEGIN { image_replaced = domain_replaced = frontend_replaced = origins_replaced = 0 }
    /^APP_IMAGE=/ { print "APP_IMAGE=" image; image_replaced = 1; next }
    /^API_DOMAIN=/ { print "API_DOMAIN=" domain; domain_replaced = 1; next }
    /^FRONTEND_BASE_URL=/ { print "FRONTEND_BASE_URL=" frontend; frontend_replaced = 1; next }
    /^CORS_ALLOWED_ORIGINS=/ { print "CORS_ALLOWED_ORIGINS=" origins; origins_replaced = 1; next }
    { print }
    END {
      if (!image_replaced) print "APP_IMAGE=" image
      if (!domain_replaced) print "API_DOMAIN=" domain
      if (!frontend_replaced) print "FRONTEND_BASE_URL=" frontend
      if (!origins_replaced) print "CORS_ALLOWED_ORIGINS=" origins
    }
  ' "${ENV_FILE}" > "${temp_env}"
  mv "${temp_env}" "${ENV_FILE}"
  chmod 600 "${ENV_FILE}"
}

render_dynamic_config() {
  local temp_dynamic
  test -r "${DYNAMIC_TEMPLATE}" || { echo "${DYNAMIC_TEMPLATE}이 없어 Traefik 설정을 만들 수 없습니다." >&2; exit 1; }
  temp_dynamic="$(mktemp "${TEAMD_DIR}/.dynamic.yml.XXXXXX")"
  sed "s|\${API_DOMAIN}|${API_DOMAIN}|g" "${DYNAMIC_TEMPLATE}" > "${temp_dynamic}"
  mv "${temp_dynamic}" "${DYNAMIC_FILE}"
  chmod 644 "${DYNAMIC_FILE}"
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
    API_DOMAIN="$(awk -F= '/^API_DOMAIN=/{print substr($0, index($0, "=") + 1); exit}' "${ENV_FILE}")"
    render_dynamic_config || true
    compose pull app || true
    compose up -d --force-recreate traefik app || true
    echo "자동 롤백 대상: $(cat "${CURRENT_SHA_FILE}")" >&2
  else
    echo "직전 배포 정보가 없어 자동 롤백할 수 없습니다." >&2
  fi
  exit "${exit_code}"
}
trap rollback ERR

validate_public_settings
cp "${ENV_FILE}" "${ENV_BACKUP}"
chmod 600 "${ENV_BACKUP}"
replace_deploy_settings "${IMAGE_REPOSITORY}:${IMAGE_SHA}"
render_dynamic_config

compose pull app
compose up -d --force-recreate traefik app

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
