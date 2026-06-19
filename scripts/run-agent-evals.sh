#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR/copilot-agent-service"

REPORT_DIR="${AGENT_EVAL_REPORT_DIR:-$ROOT_DIR/copilot-agent-service/evals/reports}"
RUN_NAME="${AGENT_EVAL_RUN_NAME:-agentops-$(date +%Y%m%d-%H%M%S)}"
PYTHON_BIN="${PYTHON_BIN:-}"
if [[ -z "$PYTHON_BIN" ]]; then
  if [[ -x ".venv/bin/python" ]]; then
    PYTHON_BIN=".venv/bin/python"
  else
    PYTHON_BIN="python3"
  fi
fi

"$PYTHON_BIN" -m evals.metrics \
  --category "${AGENT_EVAL_CATEGORY:-all}" \
  --output-dir "$REPORT_DIR" \
  --run-name "$RUN_NAME" \
  "$@"
