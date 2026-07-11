#!/usr/bin/env python3
"""Lightweight documentation checks for LocalLife."""
from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DOC_ROOTS = [
    ROOT / "README.md",
    ROOT / "docs",
    ROOT / "local-life-server",
    ROOT / "local-life-copilot",
    ROOT / "copilot-agent-service",
]
IGNORED_PARTS = {
    ".git",
    ".mypy_cache",
    ".pytest_cache",
    ".ruff_cache",
    ".venv",
    "__pycache__",
    "build",
    "dist",
    "mutants",
    "node_modules",
    "target",
}
IGNORED_PREFIXES = (
    "copilot-agent-service/evals/reports/",
)

FORMAL_DOC_PREFIXES = (
    "docs/",
)
METADATA_EXEMPT = (
    "docs/README.md",
    "docs/00-学习路线.md",
    "docs/01-project/00-文档索引.md",
    "docs/03-process/README.md",
)
AUXILIARY_PREFIXES = (
    "docs/templates/",
    "docs/archive/",
    "copilot-agent-service/rag/knowledge_base/",
)
REQUIRED_META = ("Status:", "Type:", "Owners:", "Last verified:", "Source of truth:")
SECRET_PATTERNS = [
    re.compile(r"(?i)(api[_-]?key|secret|token|password)\s*[:=]\s*['\"]?[A-Za-z0-9_./+=-]{12,}"),
    re.compile(r"mysql://[^\\s`]+:[^\\s`]+@"),
]
PERSONAL_PATH_PATTERNS = [
    re.compile(r"C:\\Users\\[^\\\s`]+"),
    re.compile(r"/home/(?!user\b|具体用户名\b|\$\{)[A-Za-z0-9._-]+"),
]


def rel(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def markdown_files() -> list[Path]:
    files: set[Path] = set()
    for root in DOC_ROOTS:
        if root.is_file() and root.suffix == ".md":
            files.add(root)
        elif root.is_dir():
            files.update(root.rglob("*.md"))
    return sorted(path for path in files if not is_ignored(path))


def is_ignored(path: Path) -> bool:
    r = rel(path)
    if any(part in IGNORED_PARTS for part in path.parts):
        return True
    return r.startswith(IGNORED_PREFIXES)


def is_formal_doc(path: Path) -> bool:
    r = rel(path)
    if r in METADATA_EXEMPT:
        return False
    if r.startswith(AUXILIARY_PREFIXES):
        return False
    return r.startswith(FORMAL_DOC_PREFIXES)


def strip_code_blocks(text: str) -> str:
    return re.sub(r"```.*?```", "", text, flags=re.S)


def check_headings(path: Path, text: str, errors: list[str]) -> None:
    text = strip_code_blocks(text)
    headings = []
    for line_no, line in enumerate(text.splitlines(), 1):
        match = re.match(r"^(#{1,6})\s+\S", line)
        if match:
            headings.append((line_no, len(match.group(1)), line))
    h1_count = sum(1 for _, level, _ in headings if level == 1)
    if h1_count < 1:
        errors.append(f"{rel(path)}: expected at least one H1")
    prev = 0
    for line_no, level, line in headings:
        if prev and level > prev + 1:
            errors.append(f"{rel(path)}:{line_no}: heading level jumps from H{prev} to H{level}: {line}")
        prev = level


def check_code_fences(path: Path, text: str, errors: list[str]) -> None:
    in_fence = False
    start_line = 0
    for line_no, line in enumerate(text.splitlines(), 1):
        if line.startswith("```"):
            if not in_fence:
                in_fence = True
                start_line = line_no
            else:
                in_fence = False
    if in_fence:
        errors.append(f"{rel(path)}:{start_line}: fenced code block is not closed")


def link_target_exists(path: Path, target: str) -> bool:
    clean = target.split("#", 1)[0]
    if not clean or clean.startswith(("http://", "https://", "mailto:")):
        return True
    if clean.startswith("#"):
        return True
    clean = clean.replace("%20", " ")
    return (path.parent / clean).resolve().exists()


def check_links(path: Path, text: str, errors: list[str]) -> None:
    no_code = strip_code_blocks(text)
    for match in re.finditer(r"(?<!!)\[[^\]]+\]\(([^)]+)\)", no_code):
        target = match.group(1).strip()
        if not link_target_exists(path, target):
            errors.append(f"{rel(path)}: broken markdown link -> {target}")


def check_metadata(path: Path, text: str, errors: list[str]) -> None:
    if not is_formal_doc(path):
        return
    head = "\n".join(text.splitlines()[:12])
    for key in REQUIRED_META:
        if key not in head:
            errors.append(f"{rel(path)}: missing metadata field {key}")
    status_match = re.search(r"^- Status:\s*(Superseded)\b", head, flags=re.M)
    if status_match and "Superseded by:" not in head:
        errors.append(f"{rel(path)}: Superseded document must declare Superseded by")


def check_sensitive_text(path: Path, text: str, errors: list[str]) -> None:
    for pattern in PERSONAL_PATH_PATTERNS:
        for match in pattern.finditer(text):
            errors.append(f"{rel(path)}: personal path detected: {match.group(0)}")
    for pattern in SECRET_PATTERNS:
        for match in pattern.finditer(text):
            errors.append(f"{rel(path)}: possible secret detected: {match.group(0)[:48]}")


def inventory_paths() -> set[str]:
    inventory = ROOT / "docs" / "文档清单.md"
    if not inventory.exists():
        return set()
    found = set()
    for line in inventory.read_text(encoding="utf-8").splitlines():
        if line.startswith("| `"):
            parts = line.split("|")
            if len(parts) > 1:
                found.add(parts[1].strip().strip("`"))
    return found


def check_inventory(files: list[Path], errors: list[str]) -> None:
    inv = inventory_paths()
    if not inv:
        errors.append("docs/文档清单.md: missing or no inventory rows")
        return
    expected = {
        rel(path)
        for path in files
        if rel(path).startswith(("README.md", "docs/", "local-life-server/", "local-life-copilot/", "copilot-agent-service/"))
        and not is_ignored(path)
        and not rel(path).startswith(("docs/.obsidian/",))
    }
    missing = sorted(expected - inv)
    for item in missing:
        errors.append(f"docs/文档清单.md: missing inventory entry for {item}")


def main() -> int:
    errors: list[str] = []
    files = markdown_files()
    for path in files:
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            errors.append(f"{rel(path)}: cannot read as UTF-8")
            continue
        check_headings(path, text, errors)
        check_code_fences(path, text, errors)
        check_links(path, text, errors)
        check_metadata(path, text, errors)
        check_sensitive_text(path, text, errors)
    check_inventory(files, errors)
    if errors:
        print("Documentation check failed:")
        for error in errors:
            print(f"- {error}")
        return 1
    print(f"Documentation check passed: {len(files)} Markdown files checked.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
