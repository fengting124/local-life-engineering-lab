#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

COMPOSE=(
  docker compose
  -f "${INFRA_DIR}/docker-compose.dev.yml"
  -f "${INFRA_DIR}/docker-compose.lite.yml"
)

"${COMPOSE[@]}" --profile app up -d

if [[ "${WITH_RAG_MODELS:-false}" == "true" ]]; then
  # Explicit service names activate profiled services without starting Milvus Server.
  "${COMPOSE[@]}" up -d embedding-service reranker-service
fi

printf '\nLocalLife Lite started.\n'
printf 'Agent: http://localhost:${AGENT_PORT:-8000}/\n'
printf 'Milvus Lite file is persisted in Docker volume agent_rag_data.\n'
printf 'Set WITH_RAG_MODELS=true to start local embedding and reranker services.\n'
