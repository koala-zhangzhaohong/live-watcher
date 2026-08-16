#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIVE_ROOT="/www/docker/live"
TRAEFIK_ROOT="/www/docker/traefik/v2"
OTEL_ROOT="/www/docker/otel-lgtm-live"

install -d -m 755 \
  "${LIVE_ROOT}" \
  "${TRAEFIK_ROOT}/config" \
  "${TRAEFIK_ROOT}/logs" \
  "${TRAEFIK_ROOT}/certs" \
  "${OTEL_ROOT}/data" \
  "${OTEL_ROOT}/dashboards" \
  "${OTEL_ROOT}/loki/config"

install -m 644 "${ROOT_DIR}/traefik-tiktok-live.yml" "${LIVE_ROOT}/traefik.yml"
install -m 644 "${ROOT_DIR}/traefik-tiktok-live-dynamic.yml" "${TRAEFIK_ROOT}/config/tiktok-live.yml"

if [[ ! -f "${OTEL_ROOT}/loki/config/loki-config.yaml" ]]; then
  install -m 644 "${ROOT_DIR}/docker/otel/loki-config.yaml" "${OTEL_ROOT}/loki/config/loki-config.yaml"
fi

echo "Docker directories prepared for TikTok Live"
echo "  ${LIVE_ROOT}"
echo "  ${TRAEFIK_ROOT}"
echo "  ${OTEL_ROOT}"
