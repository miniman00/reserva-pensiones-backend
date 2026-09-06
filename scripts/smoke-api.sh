#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${1:-${BASE_URL:-http://localhost:8080}}"
BASE_URL="${BASE_URL%/}"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

check() {
  local name="$1"
  local path="$2"
  local expected="$3"
  local body="$TMP_DIR/body"
  local headers="$TMP_DIR/headers"
  local status

  status="$(curl -sS -D "$headers" -o "$body" -w '%{http_code}' "$BASE_URL$path")"
  if [[ "$status" != "$expected" ]]; then
    echo "[FAIL] $name -> HTTP $status (esperado $expected)"
    cat "$body" || true
    exit 1
  fi

  if ! grep -qi '^X-Request-Id:' "$headers"; then
    echo "[FAIL] $name -> falta X-Request-Id"
    exit 1
  fi
  echo "[OK]   $name -> HTTP $status"
}

check "Liveness" "/actuator/health/liveness" "200"
check "Readiness" "/actuator/health/readiness" "200"
check "Búsqueda pública" "/api/public/pensions?page=0&size=1" "200"
check "Sitemap" "/sitemap.xml" "200"
check "Robots" "/robots.txt" "200"
check "Detalle público inexistente" "/api/public/pensions/9223372036854775807" "404"
check "API privada anónima" "/api/me" "401"

echo "Smoke API completado correctamente contra $BASE_URL"
