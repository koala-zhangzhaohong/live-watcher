#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_HOST="${DEPLOY_HOST:-103.39.227.254}"
DEPLOY_PORT="${DEPLOY_PORT:-22}"
DEPLOY_USER="${DEPLOY_USER:-root}"
REMOTE_ROOT="${REMOTE_ROOT:-/www/docker/live}"
IMAGE_VERSION="${IMAGE_VERSION:-1.0.0-CP$(TZ=Asia/Shanghai date +%Y%m%d%H%M)}"
OUTPUT_DIR="${OUTPUT_DIR:-${ROOT_DIR}/image-output}"

command -v sshpass >/dev/null 2>&1 || { echo "sshpass command not found" >&2; exit 1; }
command -v ssh >/dev/null 2>&1 || { echo "ssh command not found" >&2; exit 1; }
command -v scp >/dev/null 2>&1 || { echo "scp command not found" >&2; exit 1; }

if [[ -z "${DEPLOY_PASSWORD:-}" ]]; then
  read -r -s -p "SSH password for ${DEPLOY_USER}@${DEPLOY_HOST}: " DEPLOY_PASSWORD
  echo
fi
export SSHPASS="${DEPLOY_PASSWORD}"
unset DEPLOY_PASSWORD

export IMAGE_VERSION BUILD_ENV=prod OUTPUT_DIR TARGET_PLATFORM="${TARGET_PLATFORM:-linux/amd64}"
"${ROOT_DIR}/deploy.sh"

BACKEND_ARCHIVE="${OUTPUT_DIR}/tiktok-live-service-${IMAGE_VERSION}.tar.gz"
FRONTEND_ARCHIVE="${OUTPUT_DIR}/tiktok-live-web-${IMAGE_VERSION}.tar.gz"
SSH=(sshpass -e ssh -p "${DEPLOY_PORT}" -o StrictHostKeyChecking=accept-new)
SCP=(sshpass -e scp -P "${DEPLOY_PORT}" -o StrictHostKeyChecking=accept-new)
TARGET="${DEPLOY_USER}@${DEPLOY_HOST}"

"${SSH[@]}" "${TARGET}" "mkdir -p '${REMOTE_ROOT}/config' '${REMOTE_ROOT}/logs' '${REMOTE_ROOT}/certs' '/www/docker/live' '/www/docker/otel-lgtm-live/data' '/www/docker/otel-lgtm-live/dashboards' '/www/docker/otel-lgtm-live/loki/config' '/www/docker/traefik/v2/config' '/www/docker/traefik/v2/logs' '/www/docker/traefik/v2/certs'"
"${SCP[@]}" \
  "${BACKEND_ARCHIVE}" "${FRONTEND_ARCHIVE}" \
  "${ROOT_DIR}/DockerFile-gateway.yml" \
  "${ROOT_DIR}/traefik-tiktok-live.yml" \
  "${ROOT_DIR}/traefik-tiktok-live-dynamic.yml" \
  "${ROOT_DIR}/docker/otel/loki-config.yaml" \
  "${TARGET}:${REMOTE_ROOT}/"

"${SSH[@]}" "${TARGET}" bash -s -- "${REMOTE_ROOT}" "${IMAGE_VERSION}" "$(basename "${BACKEND_ARCHIVE}")" "$(basename "${FRONTEND_ARCHIVE}")" <<'REMOTE'
set -euo pipefail
remote_root="$1"
image_version="$2"
backend_archive="$3"
frontend_archive="$4"
cd "$remote_root"
docker load -i "$backend_archive"
docker load -i "$frontend_archive"
install -m 644 traefik-tiktok-live.yml /www/docker/live/traefik.yml
install -m 644 traefik-tiktok-live-dynamic.yml /www/docker/traefik/v2/config/tiktok-live.yml
install -m 644 loki-config.yaml /www/docker/otel-lgtm-live/loki/config/loki-config.yaml
docker network inspect traefik-gateway-v2 >/dev/null 2>&1 || docker network create traefik-gateway-v2 >/dev/null
if docker compose version >/dev/null 2>&1; then
  compose=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  compose=(docker-compose)
else
  echo "Docker Compose is not installed" >&2
  exit 1
fi
TIKTOK_LIVE_IMAGE_VERSION="$image_version" "${compose[@]}" -f DockerFile-gateway.yml up -d --remove-orphans

containers=(
  tiktok-live-backend-1
  tiktok-live-backend-2
  tiktok-live-backend-3
  tiktok-live-web-1
  tiktok-live-web-2
  tiktok-live-traefik-gateway
  tiktok-live-traefik-otel-lgtm
)
for attempt in $(seq 1 30); do
  all_running=true
  for container in "${containers[@]}"; do
    if [[ "$(docker inspect --format '{{.State.Status}}' "$container" 2>/dev/null || true)" != "running" ]]; then
      all_running=false
      break
    fi
  done
  [[ "$all_running" == "true" ]] && break
  sleep 2
done
for container in "${containers[@]}"; do
  [[ "$(docker inspect --format '{{.State.Status}}' "$container")" == "running" ]] || {
    echo "Container is not running: $container" >&2
    exit 1
  }
done

for attempt in $(seq 1 30); do
  if curl -fsS http://127.0.0.1:57000/ >/dev/null \
    && curl -fsS http://127.0.0.1:57000/api/douyin/live >/dev/null; then
    break
  fi
  [[ "$attempt" == "30" ]] && { echo "Production health check failed" >&2; exit 1; }
  sleep 2
done

echo "Removing unused TikTok Live application images"
used_image_references="$(docker ps -a --format '{{.Image}}')"
for repository in tiktok-live-service tiktok-live-web; do
  while IFS= read -r image_reference; do
    [[ -n "$image_reference" ]] || continue
    if ! grep -Fqx "$image_reference" <<< "$used_image_references"; then
      docker image rm "$image_reference"
    fi
  done < <(docker image ls --filter "reference=${repository}:*" --format '{{.Repository}}:{{.Tag}}' | sort -u)
done
find "$remote_root" -maxdepth 1 -type f \
  \( -name 'tiktok-live-service-*.tar.gz' -o -name 'tiktok-live-web-*.tar.gz' \) \
  ! -name "$backend_archive" ! -name "$frontend_archive" -delete

"${compose[@]}" -f DockerFile-gateway.yml ps
REMOTE

echo "Production deployment completed: http://${DEPLOY_HOST}:57000"
