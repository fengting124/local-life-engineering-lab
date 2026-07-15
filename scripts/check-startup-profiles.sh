#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

required_profile_flags="--profile app --profile search --profile mq"
status=0

check_file() {
  local file="$1"
  local description="$2"

  if ! grep -Fq -- "$required_profile_flags" "$file"; then
    echo "missing core compose profiles in ${description}: ${file}" >&2
    status=1
  fi
}

check_file "infra/scripts/start.sh" "full-stack startup script"
check_file "infra/scripts/start.ps1" "Windows full-stack startup script"
check_file "README.md" "root quickstart"
check_file "docs/05-interview/面试演示脚本.md" "interview demo runbook"

exit "$status"
