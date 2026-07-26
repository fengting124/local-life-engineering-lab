#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_TS="${RUN_TS:-$(date +%Y%m%d-%H%M%S)}"
OUT_DIR="${OUT_DIR:-$ROOT/artifacts/performance/backend-$RUN_TS}"
SERVER_HOST="${SERVER_HOST:-http://localhost:8080}"
MCP_HOST="${MCP_HOST:-http://localhost:8081}"
LOCUST_BIN="${LOCUST_BIN:-locust}"
K6_BIN="${K6_BIN:-k6}"

mkdir -p "$OUT_DIR"

log() {
  printf '[backend-perf] %s\n' "$*"
}

record_cmd() {
  printf '$ %s\n' "$*" >> "$OUT_DIR/commands.txt"
}

capture_docker_snapshot() {
  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    docker compose -f "$ROOT/infra/docker-compose.dev.yml" ps -a > "$OUT_DIR/docker-compose-ps.txt" 2>&1 || true
    docker stats --no-stream > "$OUT_DIR/docker-stats.txt" 2>&1 || true
  else
    printf 'docker unavailable\n' > "$OUT_DIR/docker-stats.txt"
  fi
}

run_locust() {
  local name="$1"
  local host="$2"
  local users="$3"
  local spawn_rate="$4"
  local run_time="$5"
  local user_class="$6"
  local locustfile="$7"

  if ! command -v "$LOCUST_BIN" >/dev/null 2>&1; then
    log "skip $name: locust not found"
    printf 'SKIPPED: locust not found\n' > "$OUT_DIR/$name.status"
    return
  fi

  log "run $name users=$users spawn=$spawn_rate time=$run_time host=$host"
  record_cmd "$LOCUST_BIN -f $locustfile --host $host --users $users --spawn-rate $spawn_rate --run-time $run_time --headless --csv $OUT_DIR/$name --html $OUT_DIR/$name.html $user_class"
  "$LOCUST_BIN" \
    -f "$locustfile" \
    --host "$host" \
    --users "$users" \
    --spawn-rate "$spawn_rate" \
    --run-time "$run_time" \
    --headless \
    --csv "$OUT_DIR/$name" \
    --html "$OUT_DIR/$name.html" \
    "$user_class" \
    > "$OUT_DIR/$name.log" 2>&1 || printf 'FAILED\n' > "$OUT_DIR/$name.status"
}

run_k6_seckill() {
  if ! command -v "$K6_BIN" >/dev/null 2>&1; then
    log "skip seckill-k6: k6 not found"
    printf 'SKIPPED: k6 not found\n' > "$OUT_DIR/seckill-k6.status"
    return
  fi

  log "run seckill-k6"
  record_cmd "SUMMARY_PATH=$OUT_DIR/seckill-k6-summary.json BASE_URL=$SERVER_HOST $K6_BIN run $ROOT/performance-tests/k6/seckill.js"
  SUMMARY_PATH="$OUT_DIR/seckill-k6-summary.json" \
    BASE_URL="$SERVER_HOST" \
    "$K6_BIN" run "$ROOT/performance-tests/k6/seckill.js" \
    > "$OUT_DIR/seckill-k6.log" 2>&1 || printf 'FAILED\n' > "$OUT_DIR/seckill-k6.status"
}

log "output: $OUT_DIR"
capture_docker_snapshot

if [[ "${SEED_PERF_DATA:-false}" == "true" ]]; then
  log "seed performance data"
  record_cmd "bash scripts/seed-perf-data.sh"
  bash "$ROOT/scripts/seed-perf-data.sh" > "$OUT_DIR/seed-perf-data.log" 2>&1
fi

run_locust "mixed-read-write" "$SERVER_HOST" "${MIXED_USERS:-20}" "${MIXED_SPAWN_RATE:-5}" "${MIXED_RUN_TIME:-60s}" "LocalLifeUser" "$ROOT/performance-tests/locustfile_locallife_server.py"
run_locust "seckill-spike" "$SERVER_HOST" "${SECKILL_USERS:-50}" "${SECKILL_SPAWN_RATE:-20}" "${SECKILL_RUN_TIME:-30s}" "SeckillUser" "$ROOT/performance-tests/locustfile_locallife_server.py"
run_locust "search-hot" "$SERVER_HOST" "${SEARCH_USERS:-20}" "${SEARCH_SPAWN_RATE:-5}" "${SEARCH_RUN_TIME:-45s}" "SearchUser" "$ROOT/performance-tests/locustfile_locallife_server.py"
run_locust "mcp-tools" "$MCP_HOST" "${MCP_USERS:-10}" "${MCP_SPAWN_RATE:-2}" "${MCP_RUN_TIME:-45s}" "McpToolUser" "$ROOT/performance-tests/locustfile_copilot.py"
run_k6_seckill

capture_docker_snapshot
log "done: $OUT_DIR"
