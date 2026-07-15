#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo "Usage: MCP_CONTEXT_SIGNING_SECRET=... $0 <user_id> <role> [merchant_id]" >&2
  exit 64
fi

MCP_CONTEXT_SIGNING_SECRET="${MCP_CONTEXT_SIGNING_SECRET:-local-life-mcp-context-secret}"

user_id="$1"
role="$2"
merchant_id="${3:-}"
timestamp="$(date +%s)"

signature="$(
  printf '%s\n%s\n%s\n%s' "$user_id" "$role" "$merchant_id" "$timestamp" \
    | openssl dgst -sha256 -hmac "$MCP_CONTEXT_SIGNING_SECRET" -hex \
    | awk '{print $2}'
)"

printf -- '-H X-User-Id:%s -H X-User-Role:%s -H X-Agent-Timestamp:%s -H X-Agent-Signature:%s' \
  "$user_id" "$role" "$timestamp" "$signature"
