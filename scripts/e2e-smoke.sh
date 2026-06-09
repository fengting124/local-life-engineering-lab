#!/usr/bin/env bash
set -euo pipefail

SERVER_URL="${SERVER_URL:-http://localhost:8080}"
MCP_URL="${MCP_URL:-http://localhost:8081}"
AGENT_URL="${AGENT_URL:-http://localhost:8000}"
SMOKE_USER_ID="${SMOKE_USER_ID:-9000000001}"
SMOKE_USER_ROLE="${SMOKE_USER_ROLE:-merchant}"
SMOKE_MERCHANT_ID="${SMOKE_MERCHANT_ID:-1}"

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

wait_for_url() {
  local name="$1"
  local url="$2"
  local attempts="${3:-60}"

  for ((i = 1; i <= attempts; i++)); do
    if curl -fsS --max-time 5 "$url" >"$tmp_dir/${name}.json" 2>"$tmp_dir/${name}.err"; then
      echo "ok: ${name} is reachable"
      return 0
    fi
    sleep 3
  done

  echo "failed: ${name} did not become reachable at ${url}" >&2
  cat "$tmp_dir/${name}.err" >&2 || true
  return 1
}

post_mcp() {
  local payload="$1"
  local output="$2"
  curl -fsS --max-time 15 \
    -H "Content-Type: application/json" \
    -H "X-User-Id: ${SMOKE_USER_ID}" \
    -H "X-User-Role: ${SMOKE_USER_ROLE}" \
    -H "X-Merchant-Id: ${SMOKE_MERCHANT_ID}" \
    -d "$payload" \
    "${MCP_URL}/mcp" >"$output"
}

wait_for_url "local-life-server" "${SERVER_URL}/actuator/health"
wait_for_url "local-life-copilot" "${MCP_URL}/actuator/health"
wait_for_url "copilot-agent-service" "${AGENT_URL}/health"

tools_json="$tmp_dir/tools.json"
post_mcp '{"jsonrpc":"2.0","id":"smoke-tools","method":"tools/list","params":{}}' "$tools_json"
python3 - "$tools_json" <<'PY'
import json
import sys

body = json.load(open(sys.argv[1], encoding="utf-8"))
if "error" in body:
    raise SystemExit(f"tools/list returned error: {body['error']}")
tools = body.get("result", {}).get("tools", [])
names = {tool.get("name") for tool in tools}
if "shop_metrics_query" not in names:
    raise SystemExit(f"shop_metrics_query not found in tools/list: {sorted(names)}")
print(f"ok: MCP tools/list returned {len(tools)} tools")
PY

metrics_json="$tmp_dir/metrics.json"
post_mcp '{"jsonrpc":"2.0","id":"smoke-metrics","method":"tools/call","params":{"name":"shop_metrics_query","arguments":{"date":"today"}}}' "$metrics_json"
python3 - "$metrics_json" <<'PY'
import json
import sys

body = json.load(open(sys.argv[1], encoding="utf-8"))
if "error" in body:
    raise SystemExit(f"shop_metrics_query returned error: {body['error']}")
content = body.get("result", {}).get("content", [])
if not content:
    raise SystemExit(f"shop_metrics_query returned empty content: {body}")
metrics = json.loads(content[0]["text"])
for key in ("order_count", "gmv", "cancel_count", "coupon_used_count"):
    if key not in metrics:
        raise SystemExit(f"missing metric key {key}: {metrics}")
print(
    "ok: MCP shop_metrics_query returned "
    f"order_count={metrics['order_count']}, gmv={metrics['gmv']}"
)
PY

chat_sse="$tmp_dir/chat.sse"
curl -fsS --no-buffer --max-time 30 \
  -H "Content-Type: application/json" \
  -H "X-User-Id: ${SMOKE_USER_ID}" \
  -H "X-User-Role: ${SMOKE_USER_ROLE}" \
  -H "X-Merchant-Id: ${SMOKE_MERCHANT_ID}" \
  -d '{"message":"今天卖了多少？","session_id":0}' \
  "${AGENT_URL}/chat" >"$chat_sse"

grep -q "event: final_answer" "$chat_sse"
grep -q "fast_path" "$chat_sse"
echo "ok: Agent /chat reached final_answer via Fast Path"
