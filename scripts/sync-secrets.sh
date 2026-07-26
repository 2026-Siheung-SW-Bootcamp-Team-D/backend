#!/usr/bin/env bash
set -Eeuo pipefail
set +x

PROJECT_ID="${1:?사용법: sync-secrets.sh <project-id> <env-file>}"
ENV_FILE="${2:?사용법: sync-secrets.sh <project-id> <env-file>}"
SECRET_NAMES=(
  DB_PASSWORD
  TOKEN_PEPPER
  ORIGIN_ENC_KEY
  KAKAO_REST_KEY
  ODSAY_API_KEY
  TMAP_APP_KEY
)

test -s "${ENV_FILE}" || { echo "${ENV_FILE}이 없어 secret을 동기화할 수 없습니다." >&2; exit 1; }

TEMP_ENV="$(mktemp "${ENV_FILE}.secrets.XXXXXX")"
VALUE_DIR="$(mktemp -d)"
cleanup() {
  rm -f "${TEMP_ENV}"
  rm -rf "${VALUE_DIR}"
}
trap cleanup EXIT

cp "${ENV_FILE}" "${TEMP_ENV}"
chmod 600 "${TEMP_ENV}"

validate_secret() {
  local name="$1"
  local value="$2"
  [[ -n "${value}" ]] || { echo "${name} 최신 secret이 비어 있습니다." >&2; exit 1; }
  [[ "${value}" != *$'\n'* && "${value}" != *$'\r'* ]] || { echo "${name} secret에 줄바꿈을 사용할 수 없습니다." >&2; exit 1; }
  case "${name}" in
    KAKAO_REST_KEY)
      [[ "${value}" =~ ^[[:xdigit:]]{32}$ ]] || { echo 'KAKAO_REST_KEY 형식이 올바르지 않습니다.' >&2; exit 1; }
      ;;
    ORIGIN_ENC_KEY)
      [[ "${value}" =~ ^[A-Za-z0-9+/]{43}=$ ]] || { echo 'ORIGIN_ENC_KEY 형식이 올바르지 않습니다.' >&2; exit 1; }
      ;;
    *)
      [[ "${#value}" -ge 8 ]] || { echo "${name} secret이 placeholder로 보입니다." >&2; exit 1; }
      ;;
  esac
}

replace_env_value() {
  local name="$1"
  local value="$2"
  local source_file="$3"
  local next_file
  local replaced=false
  local line
  next_file="$(mktemp "${source_file}.next.XXXXXX")"
  chmod 600 "${next_file}"
  while IFS= read -r line || [[ -n "${line}" ]]; do
    if [[ "${line}" == "${name}="* ]]; then
      printf '%s=%s\n' "${name}" "${value}" >> "${next_file}"
      replaced=true
    else
      printf '%s\n' "${line}" >> "${next_file}"
    fi
  done < "${source_file}"
  if [[ "${replaced}" == false ]]; then
    printf '%s=%s\n' "${name}" "${value}" >> "${next_file}"
  fi
  mv "${next_file}" "${source_file}"
  chmod 600 "${source_file}"
}

for secret_name in "${SECRET_NAMES[@]}"; do
  value_file="${VALUE_DIR}/${secret_name}"
  gcloud secrets versions access latest \
    --project="${PROJECT_ID}" \
    --secret "${secret_name}" > "${value_file}"
  secret_value="$(<"${value_file}")"
  validate_secret "${secret_name}" "${secret_value}"
  replace_env_value "${secret_name}" "${secret_value}" "${TEMP_ENV}"
done

mv "${TEMP_ENV}" "${ENV_FILE}"
chmod 600 "${ENV_FILE}"
trap - EXIT
rm -rf "${VALUE_DIR}"
echo 'Secret Manager 동기화 완료'
