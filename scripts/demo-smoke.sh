#!/usr/bin/env bash
# =======================================================
# LocalLife v0.1.0-rc demo smoke
#
# Purpose:
#   - Run a repeatable release-candidate demo against the local Docker stack.
#   - Seed deterministic demo data.
#   - Verify Server, MCP, Agent, runtime event replay, and RAG benchmark entrypoints.
#   - Write evidence under artifacts/demo-smoke/<timestamp>/.
#
# Typical usage:
#   bash scripts/demo-smoke.sh
#
# Optional:
#   START_STACK=1 bash scripts/demo-smoke.sh
#   START_STACK=1 SKIP_BUILD=0 bash scripts/demo-smoke.sh
#   INCLUDE_RAG_REAL=1 bash scripts/demo-smoke.sh
# =======================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SERVER_URL="${SERVER_URL:-http://localhost:8080}"
MCP_URL="${MCP_URL:-http://localhost:8081}"
AGENT_URL="${AGENT_URL:-http://localhost:8000}"
GRAFANA_URL="${GRAFANA_URL:-http://localhost:3000}"
LOKI_URL="${LOKI_URL:-http://localhost:3100}"
ALERTMANAGER_URL="${ALERTMANAGER_URL:-http://localhost:9093}"

DEMO_USER_ID="${DEMO_USER_ID:-880000000001}"
DEMO_USER_ROLE="${DEMO_USER_ROLE:-merchant}"
DEMO_MERCHANT_ID="${DEMO_MERCHANT_ID:-880000100001}"
OPERATOR_USER_ID="${OPERATOR_USER_ID:-900000000001}"
OPERATOR_ROLE="${OPERATOR_ROLE:-admin}"

START_STACK="${START_STACK:-0}"
SKIP_BUILD="${SKIP_BUILD:-1}"
INCLUDE_RAG_OFFLINE="${INCLUDE_RAG_OFFLINE:-1}"
INCLUDE_RAG_REAL="${INCLUDE_RAG_REAL:-0}"

RUN_TS="$(date '+%Y%m%d-%H%M%S')"
OUT_DIR="${OUT_DIR:-$ROOT/artifacts/demo-smoke/$RUN_TS}"
mkdir -p "$OUT_DIR"

summary="$OUT_DIR/summary.md"
results_json="$OUT_DIR/results.json"

status_items=()

log() {
  printf '[demo-smoke] %s\n' "$*"
}

record_status() {
  local name="$1"
  local status="$2"
  local detail="$3"
  status_items+=("${name}|${status}|${detail}")
}

require_cmd() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "ERROR: required command not found: $cmd" >&2
    exit 1
  fi
}

wait_for_url() {
  local name="$1"
  local url="$2"
  local attempts="${3:-30}"
  local required="${4:-1}"
  local out="$OUT_DIR/${name}.json"
  local err="$OUT_DIR/${name}.err"

  for ((i = 1; i <= attempts; i++)); do
    if curl -fsS --max-time 5 "$url" >"$out" 2>"$err"; then
      log "ok: $name reachable at $url"
      record_status "$name" "PASS" "$url"
      return 0
    fi
    sleep 2
  done

  if [[ "$required" == "1" ]]; then
    echo "ERROR: $name not reachable at $url" >&2
    cat "$err" >&2 || true
    record_status "$name" "FAIL" "$url"
    return 1
  fi

  log "warn: optional $name not reachable at $url"
  record_status "$name" "WARN" "$url not reachable"
  return 0
}

post_mcp() {
  local payload="$1"
  local output="$2"
  curl -fsS --max-time 20 \
    -H "Content-Type: application/json" \
    -H "X-User-Id: ${DEMO_USER_ID}" \
    -H "X-User-Role: ${DEMO_USER_ROLE}" \
    -H "X-Merchant-Id: ${DEMO_MERCHANT_ID}" \
    -d "$payload" \
    "${MCP_URL}/mcp" >"$output"
}

json_assert_no_mcp_error() {
  local file="$1"
  local label="$2"
  python3 - "$file" "$label" <<'PY'
import json
import sys

path, label = sys.argv[1], sys.argv[2]
body = json.load(open(path, encoding="utf-8"))
if body.get("error"):
    raise SystemExit(f"{label} returned MCP error: {body['error']}")
print(f"ok: {label}")
PY
}

extract_sse_field() {
  local file="$1"
  local event_name="$2"
  local field="$3"
  python3 - "$file" "$event_name" "$field" <<'PY'
import json
import sys

path, event_name, field = sys.argv[1], sys.argv[2], sys.argv[3]
current = None
for raw in open(path, encoding="utf-8"):
    line = raw.rstrip("\n")
    if line.startswith("event: "):
        current = line.split(": ", 1)[1]
    elif current == event_name and line.startswith("data: "):
        data = json.loads(line.split(": ", 1)[1])
        value = data.get(field)
        if value is not None:
            print(value)
            raise SystemExit(0)
raise SystemExit(1)
PY
}

require_cmd curl
require_cmd python3
require_cmd docker

log "writing evidence to $OUT_DIR"

if [[ "$START_STACK" == "1" ]]; then
  log "starting Docker stack via infra/scripts/start.sh"
  if [[ "$SKIP_BUILD" == "1" ]]; then
    bash infra/scripts/start.sh --skip-build | tee "$OUT_DIR/start-stack.log"
  else
    bash infra/scripts/start.sh | tee "$OUT_DIR/start-stack.log"
  fi
else
  log "START_STACK=0, assuming services are already running"
fi

log "checking service health"
wait_for_url "local-life-server-health" "${SERVER_URL}/actuator/health" 60 1
wait_for_url "local-life-copilot-health" "${MCP_URL}/actuator/health" 60 1
wait_for_url "copilot-agent-health" "${AGENT_URL}/health" 60 1
wait_for_url "grafana-health" "${GRAFANA_URL}/api/health" 5 0
wait_for_url "loki-ready" "${LOKI_URL}/ready" 5 0
wait_for_url "alertmanager-ready" "${ALERTMANAGER_URL}/-/ready" 5 0

log "capturing Docker service state"
docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}' >"$OUT_DIR/docker-ps.txt"
record_status "docker-ps" "PASS" "$OUT_DIR/docker-ps.txt"

log "seeding deterministic business demo data"
bash scripts/business-simulate.sh | tee "$OUT_DIR/business-simulate.log"
record_status "business-simulate" "PASS" "demo data seeded"

log "running existing cross-service e2e smoke"
SERVER_URL="$SERVER_URL" MCP_URL="$MCP_URL" AGENT_URL="$AGENT_URL" \
SMOKE_USER_ID="$DEMO_USER_ID" SMOKE_USER_ROLE="$DEMO_USER_ROLE" SMOKE_MERCHANT_ID="$DEMO_MERCHANT_ID" \
  bash scripts/e2e-smoke.sh | tee "$OUT_DIR/e2e-smoke.log"
record_status "e2e-smoke" "PASS" "server + MCP + Agent fast path"

log "calling MCP query_order against seeded abnormal order"
mcp_order="$OUT_DIR/mcp-query-order.json"
post_mcp '{"jsonrpc":"2.0","id":"demo-query-order","method":"tools/call","params":{"name":"query_order","arguments":{"order_id":"202606100002"}}}' "$mcp_order"
json_assert_no_mcp_error "$mcp_order" "MCP query_order"
record_status "mcp-query-order" "PASS" "order_no=202606100002"

log "calling Agent fast path and replaying persisted runtime events"
agent_sse="$OUT_DIR/agent-fast-path.sse"
curl -fsS --no-buffer --max-time 40 \
  -H "Content-Type: application/json" \
  -H "X-User-Id: ${DEMO_USER_ID}" \
  -H "X-User-Role: ${DEMO_USER_ROLE}" \
  -H "X-Merchant-Id: ${DEMO_MERCHANT_ID}" \
  -d '{"message":"今天卖了多少？","session_id":0}' \
  "${AGENT_URL}/chat" >"$agent_sse"

grep -q "event: session_started" "$agent_sse"
grep -q "event: final_answer" "$agent_sse"
grep -q "fast_path" "$agent_sse"
run_id="$(extract_sse_field "$agent_sse" "session_started" "run_id")"
session_id="$(extract_sse_field "$agent_sse" "session_started" "session_id")"
thread_id="$(extract_sse_field "$agent_sse" "session_started" "thread_id")"
record_status "agent-fast-path" "PASS" "run_id=$run_id session_id=$session_id"

runtime_events="$OUT_DIR/agent-runtime-events.json"
curl -fsS --max-time 15 \
  -H "X-User-Id: ${DEMO_USER_ID}" \
  -H "X-User-Role: ${DEMO_USER_ROLE}" \
  "${AGENT_URL}/chat/runs/${run_id}/events?after_sequence=-1&limit=20" >"$runtime_events"
python3 - "$runtime_events" "$run_id" <<'PY'
import json
import sys

path, expected_run_id = sys.argv[1], sys.argv[2]
body = json.load(open(path, encoding="utf-8"))
if body.get("run_id") != expected_run_id:
    raise SystemExit(f"unexpected run_id: {body.get('run_id')}")
events = body.get("events", [])
types = {event.get("event_type") for event in events}
required = {"session_started", "final_answer"}
missing = required - types
if missing:
    raise SystemExit(f"runtime events missing {missing}: {events}")
print(f"ok: runtime event replay returned {len(events)} events")
PY
record_status "agent-runtime-events" "PASS" "run_id=$run_id"

if [[ "$INCLUDE_RAG_OFFLINE" == "1" ]]; then
  log "running offline RAG quality benchmark"
  DEBUG=false bash scripts/run-rag-benchmark.sh \
    --output-dir "$OUT_DIR/rag" \
    --run-name "rag-quality-offline" | tee "$OUT_DIR/rag-offline.log"
  record_status "rag-benchmark-offline" "PASS" "$OUT_DIR/rag/rag-quality-offline.md"
fi

if [[ "$INCLUDE_RAG_REAL" == "1" ]]; then
  log "running real RAG quality benchmark"
  DEBUG=false \
  EMBEDDING_SERVICE_URL="${EMBEDDING_SERVICE_URL:-http://localhost:8100}" \
  RERANKER_SERVICE_URL="${RERANKER_SERVICE_URL:-http://localhost:8101}" \
  MILVUS_HOST="${MILVUS_HOST:-localhost}" \
  MILVUS_PORT="${MILVUS_PORT:-19530}" \
  MILVUS_COLLECTION="${MILVUS_COLLECTION:-local_life_kb}" \
  bash scripts/run-rag-benchmark.sh \
    --real \
    --output-dir "$OUT_DIR/rag" \
    --run-name "rag-quality-real" | tee "$OUT_DIR/rag-real.log"
  record_status "rag-benchmark-real" "PASS" "$OUT_DIR/rag/rag-quality-real.md"
fi

python3 - "$results_json" "${status_items[@]}" <<'PY'
import json
import sys

out = sys.argv[1]
items = []
for raw in sys.argv[2:]:
    name, status, detail = raw.split("|", 2)
    items.append({"name": name, "status": status, "detail": detail})
with open(out, "w", encoding="utf-8") as f:
    json.dump({"items": items}, f, ensure_ascii=False, indent=2)
PY

{
  echo "# LocalLife Demo Smoke Report"
  echo
  echo "- Run timestamp: ${RUN_TS}"
  echo "- Branch: $(git branch --show-current)"
  echo "- Commit: $(git rev-parse --short HEAD)"
  echo "- Server URL: ${SERVER_URL}"
  echo "- MCP URL: ${MCP_URL}"
  echo "- Agent URL: ${AGENT_URL}"
  echo "- Demo merchant: ${DEMO_MERCHANT_ID}"
  echo "- Agent run: ${run_id}"
  echo "- Agent session: ${session_id}"
  echo "- Agent thread: ${thread_id}"
  echo
  echo "## Checks"
  echo
  echo "| Check | Status | Detail |"
  echo "| --- | --- | --- |"
  for item in "${status_items[@]}"; do
    IFS='|' read -r name status detail <<<"$item"
    echo "| ${name} | ${status} | ${detail} |"
  done
  echo
  echo "## Evidence Files"
  echo
  echo "- Docker state: \`${OUT_DIR}/docker-ps.txt\`"
  echo "- Business seed log: \`${OUT_DIR}/business-simulate.log\`"
  echo "- E2E smoke log: \`${OUT_DIR}/e2e-smoke.log\`"
  echo "- MCP order response: \`${OUT_DIR}/mcp-query-order.json\`"
  echo "- Agent SSE: \`${OUT_DIR}/agent-fast-path.sse\`"
  echo "- Agent runtime events: \`${OUT_DIR}/agent-runtime-events.json\`"
  echo "- Results JSON: \`${OUT_DIR}/results.json\`"
} >"$summary"

log "summary written to $summary"
log "demo smoke completed"
