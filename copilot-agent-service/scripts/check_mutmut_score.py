#!/usr/bin/env python3
"""Fail CI when mutmut's mutation score drops below the project gate."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Check mutmut .meta files and enforce a minimum kill rate.",
    )
    parser.add_argument(
        "--stats-root",
        default="mutants",
        help="Directory produced by mutmut run. Defaults to ./mutants.",
    )
    parser.add_argument(
        "--min-kill-rate",
        type=float,
        default=50.0,
        help="Minimum killed/total percentage required to pass.",
    )
    parser.add_argument(
        "--max-other",
        type=int,
        default=0,
        help="Maximum number of non-0/1 mutmut exit codes allowed.",
    )
    return parser.parse_args()


def load_exit_codes(stats_root: Path) -> list[int]:
    exit_codes: list[int] = []
    for meta_path in sorted(stats_root.rglob("*.meta")):
        with meta_path.open(encoding="utf-8") as handle:
            data = json.load(handle)
        exit_codes.extend(data.get("exit_code_by_key", {}).values())
    return exit_codes


def main() -> int:
    args = parse_args()
    stats_root = Path(args.stats_root)

    if not stats_root.exists():
        print(f"mutmut stats root does not exist: {stats_root}")
        return 2

    exit_codes = load_exit_codes(stats_root)
    if not exit_codes:
        print(f"no mutmut .meta exit codes found under: {stats_root}")
        return 2

    killed = sum(code == 1 for code in exit_codes)
    survived = sum(code == 0 for code in exit_codes)
    other = len(exit_codes) - killed - survived
    total = len(exit_codes)
    kill_rate = killed * 100.0 / total

    print(
        "mutmut score: "
        f"total={total}, killed={killed}, survived={survived}, "
        f"other={other}, kill_rate={kill_rate:.1f}%"
    )

    if other > args.max_other:
        print(f"FAILED: other exit codes {other} > allowed {args.max_other}")
        return 1

    if kill_rate < args.min_kill_rate:
        print(f"FAILED: kill rate {kill_rate:.1f}% < required {args.min_kill_rate:.1f}%")
        return 1

    print(f"PASSED: kill rate {kill_rate:.1f}% >= required {args.min_kill_rate:.1f}%")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
