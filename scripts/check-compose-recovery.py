#!/usr/bin/env python3
"""Verify that stateful Lite dependencies survive Docker daemon restarts."""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SERVICES = {
    "mysql": "/var/lib/mysql",
    "redis": "/data",
}


def main() -> int:
    env = os.environ.copy()
    env.setdefault("HITL_PAYLOAD_SIGNING_SECRET", "test-only-compose-check")
    env.setdefault("ANTHROPIC_API_KEY", "")
    result = subprocess.run(
        [
            "docker",
            "compose",
            "-f",
            "infra/docker-compose.dev.yml",
            "-f",
            "infra/docker-compose.lite.yml",
            "--profile",
            "app",
            "config",
            "--format",
            "json",
        ],
        cwd=ROOT,
        env=env,
        text=True,
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        print(result.stderr, file=sys.stderr, end="")
        return result.returncode

    config = json.loads(result.stdout)
    errors: list[str] = []
    for service_name, data_path in SERVICES.items():
        service = config["services"][service_name]
        if service.get("restart") != "unless-stopped":
            errors.append(
                f"{service_name}: restart must be unless-stopped, "
                f"got {service.get('restart')!r}"
            )
        mounts = service.get("volumes", [])
        if not any(
            mount.get("type") == "volume" and mount.get("target") == data_path
            for mount in mounts
        ):
            errors.append(f"{service_name}: missing named volume at {data_path}")

    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("Compose recovery check passed: MySQL and Redis restart with named volumes.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
