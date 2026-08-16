#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_CP="${BUILD_CP:-$(TZ=Asia/Shanghai date +%Y%m%d%H%M)}"
IMAGE_VERSION="${IMAGE_VERSION:-1.0.0-CP${BUILD_CP}}"
TARGET_PLATFORM="${TARGET_PLATFORM:-linux/amd64}"
BUILD_ENV="${BUILD_ENV:-prod}"
OUTPUT_DIR="${OUTPUT_DIR:-${ROOT_DIR}/image-output}"

BACKEND_IMAGE="tiktok-live-service:${IMAGE_VERSION}"
FRONTEND_IMAGE="tiktok-live-web:${IMAGE_VERSION}"
BACKEND_ARCHIVE="${OUTPUT_DIR}/tiktok-live-service-${IMAGE_VERSION}.tar.gz"
FRONTEND_ARCHIVE="${OUTPUT_DIR}/tiktok-live-web-${IMAGE_VERSION}.tar.gz"

for command_name in docker gzip shasum; do
  command -v "${command_name}" >/dev/null 2>&1 || {
    echo "${command_name} command not found" >&2
    exit 1
  }
done

docker info >/dev/null 2>&1 || {
  echo "docker daemon is not running" >&2
  exit 1
}

mkdir -p "${OUTPUT_DIR}"

echo "==> Building TikTok Live images"
echo "    Version:  ${IMAGE_VERSION}"
echo "    Platform: ${TARGET_PLATFORM}"
echo "    Environment: ${BUILD_ENV}"

docker build \
  --platform "${TARGET_PLATFORM}" \
  --build-arg "TIKTOK_LIVE_VERSION=${IMAGE_VERSION}" \
  --build-arg "SPRING_PROFILES_ACTIVE=${BUILD_ENV}" \
  -f "${ROOT_DIR}/Dockerfile.backend" \
  -t "${BACKEND_IMAGE}" \
  "${ROOT_DIR}"

docker build \
  --platform "${TARGET_PLATFORM}" \
  --build-arg "TIKTOK_LIVE_VERSION=${IMAGE_VERSION}" \
  --build-arg "VITE_APP_ENV=${BUILD_ENV}" \
  -f "${ROOT_DIR}/Dockerfile.frontend" \
  -t "${FRONTEND_IMAGE}" \
  "${ROOT_DIR}"

echo "==> Exporting image archives"
docker save "${BACKEND_IMAGE}" | gzip -1 > "${BACKEND_ARCHIVE}"
docker save "${FRONTEND_IMAGE}" | gzip -1 > "${FRONTEND_ARCHIVE}"

shasum -a 256 "${BACKEND_ARCHIVE}" "${FRONTEND_ARCHIVE}"
echo "==> Done"
echo "    ${BACKEND_ARCHIVE}"
echo "    ${FRONTEND_ARCHIVE}"
echo "    Prepare directories: sudo ./prepare-docker-directories.sh"
echo "    Start: TIKTOK_LIVE_IMAGE_VERSION=${IMAGE_VERSION} docker compose -f DockerFile-gateway.yml up -d"
