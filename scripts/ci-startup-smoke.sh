#!/usr/bin/env bash
set -euo pipefail

PORT="${CI_PORT:-18080}"
BASE_URL="http://127.0.0.1:${PORT}"
LOG_FILE="${CI_APP_LOG:-target/ci-app.log}"
JAR="${CI_APP_JAR:-}"

if [[ -z "$JAR" ]]; then
  JAR="$(find target -maxdepth 1 -type f -name '*.jar' ! -name '*.original' | sort | head -n 1)"
fi

if [[ -z "$JAR" || ! -f "$JAR" ]]; then
  echo "[FAIL] No se encontró el JAR de la aplicación en target/"
  exit 1
fi

mkdir -p "$(dirname "$LOG_FILE")" "${CI_MEDIA_ROOT:-target/ci-media}"

java -jar "$JAR" --spring.profiles.active=ci >"$LOG_FILE" 2>&1 &
APP_PID=$!

cleanup() {
  if kill -0 "$APP_PID" 2>/dev/null; then
    kill -TERM "$APP_PID" 2>/dev/null || true
    for _ in $(seq 1 20); do
      kill -0 "$APP_PID" 2>/dev/null || return 0
      sleep 1
    done
    kill -KILL "$APP_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

for attempt in $(seq 1 60); do
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    echo "[FAIL] La aplicación terminó antes de quedar ready"
    cat "$LOG_FILE" || true
    exit 1
  fi

  if curl -fsS "$BASE_URL/actuator/health/readiness" >/dev/null 2>&1; then
    echo "[OK] Backend ready; Flyway y validación JPA completados"
    BASE_URL="$BASE_URL" bash scripts/smoke-api.sh
    exit 0
  fi

  sleep 1
done

echo "[FAIL] Timeout esperando readiness"
cat "$LOG_FILE" || true
exit 1
