#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_TS="${RUN_TS:-$(date +%Y%m%d-%H%M%S)}"
OUT_DIR="${OUT_DIR:-$ROOT/artifacts/performance/agent-$RUN_TS}"
if [[ "$OUT_DIR" != /* ]]; then
  OUT_DIR="$ROOT/$OUT_DIR"
fi
PYTHON_BIN="${PYTHON_BIN:-}"
AGENT_URL="${AGENT_URL:-http://localhost:8000}"

if [[ -z "$PYTHON_BIN" ]]; then
  if [[ -x "$ROOT/copilot-agent-service/.venv/bin/python" ]]; then
    PYTHON_BIN="$ROOT/copilot-agent-service/.venv/bin/python"
  else
    PYTHON_BIN="python3"
  fi
fi

export LLM_PROVIDER="${LLM_PROVIDER:-deepseek}"
export LLM_MODEL="${LLM_MODEL:-deepseek-v4-flash}"
case "${DEBUG:-false}" in
  true|false|TRUE|FALSE|True|False|1|0) ;;
  *) export DEBUG=false ;;
esac

mkdir -p "$OUT_DIR"

log() {
  printf '[agent-perf] %s\n' "$*"
}

if [[ -z "${LLM_API_KEY:-}" ]]; then
  printf 'LLM_API_KEY is required and must be supplied via environment variable.\n' >&2
  exit 2
fi

if [[ "$LLM_PROVIDER" != "deepseek" ]]; then
  printf 'LLM_PROVIDER must be deepseek for this baseline, got %s\n' "$LLM_PROVIDER" >&2
  exit 2
fi

if [[ "$LLM_MODEL" != *flash* ]]; then
  printf 'LLM_MODEL must be a flash model for this baseline, got %s\n' "$LLM_MODEL" >&2
  exit 2
fi

cd "$ROOT/copilot-agent-service"

log "output: $OUT_DIR"
log "offline agent eval"
AGENT_EVAL_REPORT_DIR="$OUT_DIR" \
AGENT_EVAL_RUN_NAME="offline-agent-eval" \
PYTHON_BIN="$PYTHON_BIN" \
  bash "$ROOT/scripts/run-agent-evals.sh" \
  > "$OUT_DIR/offline-agent-eval.log" 2>&1

log "offline rag benchmark"
PYTHON_BIN="$PYTHON_BIN" \
  bash "$ROOT/scripts/run-rag-benchmark.sh" \
  --output-dir "$OUT_DIR" \
  --run-name "offline-rag-benchmark" \
  > "$OUT_DIR/offline-rag-benchmark.log" 2>&1

log "real DeepSeek agent baseline"
"$PYTHON_BIN" -m evals.deepseek_baseline \
  --agent-url "$AGENT_URL" \
  --output-dir "$OUT_DIR" \
  --run-name "deepseek-flash-real-baseline" \
  --concurrency "1" \
  --repeat "2" \
  > "$OUT_DIR/deepseek-flash-real-baseline.log" 2>&1

log "real rag benchmark"
"$PYTHON_BIN" -m evals.rag_benchmark \
  --real \
  --output-dir "$OUT_DIR" \
  --run-name "real-rag-benchmark" \
  > "$OUT_DIR/real-rag-benchmark.log" 2>&1

log "done: $OUT_DIR"
