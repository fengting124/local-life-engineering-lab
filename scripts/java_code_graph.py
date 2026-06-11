#!/usr/bin/env python3
"""
Generate code relationship diagrams for local-life-server.

The script intentionally uses lightweight static analysis so it can run in a
plain Python environment. It recognizes Spring MVC annotations, class roles,
field-injected dependencies, and common method calls through injected fields.
"""

from __future__ import annotations

import argparse
import re
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path


HTTP_ANNOTATIONS = {
    "GetMapping": "GET",
    "PostMapping": "POST",
    "PutMapping": "PUT",
    "DeleteMapping": "DELETE",
    "PatchMapping": "PATCH",
}

SKIP_CALLS = {
    "if",
    "for",
    "while",
    "switch",
    "catch",
    "return",
    "throw",
    "new",
    "super",
    "this",
    "try",
}


@dataclass
class Endpoint:
    http_method: str
    path: str


@dataclass
class JavaMethod:
    name: str
    visibility: str
    return_type: str
    params: str
    annotations: list[str] = field(default_factory=list)
    endpoint: Endpoint | None = None
    calls: list[str] = field(default_factory=list)


@dataclass
class JavaClass:
    path: Path
    package: str
    name: str
    kind: str
    role: str
    module: str
    annotations: list[str] = field(default_factory=list)
    fields: dict[str, str] = field(default_factory=dict)
    methods: list[JavaMethod] = field(default_factory=list)
    imports: dict[str, str] = field(default_factory=dict)
    request_base: str = ""


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def collapse_space(value: str) -> str:
    return re.sub(r"\s+", " ", value).strip()


def sanitize_id(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_]", "_", value)


def escape_md(value: object) -> str:
    text = "" if value is None else str(value)
    return (
        text.replace("\\", "\\\\")
        .replace("|", "\\|")
        .replace("\n", "<br>")
        .strip()
    )


def parse_package(text: str) -> str:
    match = re.search(r"^\s*package\s+([\w.]+);", text, re.MULTILINE)
    return match.group(1) if match else ""


def parse_imports(text: str) -> dict[str, str]:
    imports: dict[str, str] = {}
    for match in re.finditer(r"^\s*import\s+([\w.]+);", text, re.MULTILINE):
        fqcn = match.group(1)
        imports[fqcn.rsplit(".", 1)[-1]] = fqcn
    return imports


def parse_annotations(block: str) -> list[str]:
    annotations = []
    for match in re.finditer(r"@\s*([A-Za-z_][\w.]*)", block):
        annotations.append(match.group(1).rsplit(".", 1)[-1])
    return annotations


def parse_mapping_path(annotation_text: str) -> str:
    string_match = re.search(r'"([^"]*)"', annotation_text)
    if string_match:
        return string_match.group(1)
    return ""


def join_paths(base: str, sub: str) -> str:
    base = base.strip()
    sub = sub.strip()
    if not base and not sub:
        return "/"
    if not base:
        return sub if sub.startswith("/") else f"/{sub}"
    if not sub:
        return base if base.startswith("/") else f"/{base}"
    return f"/{base.strip('/')}/{sub.strip('/')}"


def extract_annotation_invocation(block: str, annotation: str) -> str | None:
    pattern = re.compile(rf"@{annotation}\s*(?:\(([^)]*)\))?", re.DOTALL)
    match = pattern.search(block)
    if not match:
        return None
    return match.group(1) or ""


def detect_endpoint(annotations_block: str, base_path: str) -> Endpoint | None:
    for annotation, method in HTTP_ANNOTATIONS.items():
        invocation = extract_annotation_invocation(annotations_block, annotation)
        if invocation is not None:
            return Endpoint(method, join_paths(base_path, parse_mapping_path(invocation)))

    request_mapping = extract_annotation_invocation(annotations_block, "RequestMapping")
    if request_mapping is not None:
        method = "HTTP"
        method_match = re.search(r"RequestMethod\.([A-Z]+)", request_mapping)
        if method_match:
            method = method_match.group(1)
        return Endpoint(method, join_paths(base_path, parse_mapping_path(request_mapping)))
    return None


def detect_role(path: Path, text: str, class_name: str, annotations: list[str]) -> str:
    path_text = str(path).replace("\\", "/")
    if "ControllerAdvice" in annotations or "RestControllerAdvice" in annotations or "ExceptionHandler" in text:
        return "Advice"
    if "RestController" in annotations or "/controller/" in path_text:
        return "Controller"
    if "Service" in annotations or "/service/" in path_text:
        return "Service"
    if "Mapper" in annotations or "/mapper/" in path_text or class_name.endswith("Mapper"):
        return "Mapper"
    if "Configuration" in annotations or "/config/" in path_text:
        return "Config"
    if "Component" in annotations and "Consumer" in class_name:
        return "Consumer"
    if "/dto/" in path_text:
        return "DTO"
    if "/entity/" in path_text or "TableName" in annotations:
        return "Entity"
    if "/common/" in path_text:
        return "Common"
    if class_name.endswith("Application"):
        return "Application"
    return "Other"


def detect_module(path: Path) -> str:
    parts = list(path.parts)
    if "module" in parts:
        index = parts.index("module")
        if index + 1 < len(parts):
            return parts[index + 1]
    if "domain" in parts:
        return "domain"
    if "common" in parts:
        return "common"
    if "config" in parts:
        return "config"
    return "root"


def trim_type(raw_type: str) -> str:
    value = collapse_space(raw_type)
    value = re.sub(r"<.*>", "", value)
    return value.split()[-1].replace("[]", "")


def parse_fields(text: str) -> dict[str, str]:
    fields: dict[str, str] = {}
    pattern = re.compile(
        r"^\s*(?:private|protected|public)\s+"
        r"(?:static\s+)?(?:final\s+)?"
        r"([A-Za-z_][\w.<>?,\s\[\]]+?)\s+"
        r"([a-zA-Z_]\w*)\s*(?:=|;)",
        re.MULTILINE,
    )
    for match in pattern.finditer(text):
        raw_type, var_name = match.groups()
        if var_name.isupper():
            continue
        fields[var_name] = trim_type(raw_type)
    return fields


def find_matching_brace(text: str, open_index: int) -> int:
    depth = 0
    in_string = False
    in_char = False
    escaped = False
    line_comment = False
    block_comment = False

    for index in range(open_index, len(text)):
        char = text[index]
        nxt = text[index + 1] if index + 1 < len(text) else ""

        if line_comment:
            if char == "\n":
                line_comment = False
            continue
        if block_comment:
            if char == "*" and nxt == "/":
                block_comment = False
            continue
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            continue
        if in_char:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == "'":
                in_char = False
            continue

        if char == "/" and nxt == "/":
            line_comment = True
            continue
        if char == "/" and nxt == "*":
            block_comment = True
            continue
        if char == '"':
            in_string = True
            continue
        if char == "'":
            in_char = True
            continue
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return index
    return len(text) - 1


def parse_methods(text: str, class_name: str, base_path: str) -> list[JavaMethod]:
    methods: list[JavaMethod] = []
    pattern = re.compile(
        r"(?P<annotations>(?:^\s*@[^\n]+\n)*)"
        r"^\s*(?P<visibility>public|protected|private)\s+"
        r"(?P<signature>(?:static\s+)?(?:final\s+)?[A-Za-z_][\w<>\[\],.? extends super]+\s+"
        r"(?P<name>[A-Za-z_]\w*)\s*\((?P<params>.*?)\)\s*(?:throws[^{]+)?)\{",
        re.MULTILINE | re.DOTALL,
    )
    for match in pattern.finditer(text):
        name = match.group("name")
        if name == class_name:
            continue
        signature = collapse_space(match.group("signature"))
        return_type = signature.rsplit(name, 1)[0].strip()
        return_type = re.sub(r"^(static|final)\s+", "", return_type).strip()
        annotations_block = match.group("annotations")
        open_index = match.end() - 1
        close_index = find_matching_brace(text, open_index)
        body = text[open_index + 1 : close_index]

        methods.append(
            JavaMethod(
                name=name,
                visibility=match.group("visibility"),
                return_type=return_type,
                params=collapse_space(match.group("params")),
                annotations=parse_annotations(annotations_block),
                endpoint=detect_endpoint(annotations_block, base_path),
                calls=[],
            )
        )
        methods[-1]._body = body  # type: ignore[attr-defined]
    return methods


def detect_calls(java_class: JavaClass) -> None:
    method_names = {method.name for method in java_class.methods}
    for method in java_class.methods:
        body = getattr(method, "_body", "")
        calls: set[str] = set()

        for var_name, call_name in re.findall(r"\b([a-z][A-Za-z0-9_]*)\s*\.\s*([A-Za-z_]\w*)\s*\(", body):
            target_type = java_class.fields.get(var_name)
            if target_type:
                calls.add(f"{target_type}.{call_name}()")

        for call_name in re.findall(r"(?<![\w.])([A-Za-z_]\w*)\s*\(", body):
            if call_name in method_names and call_name != method.name and call_name not in SKIP_CALLS:
                calls.add(f"{java_class.name}.{call_name}()")

        method.calls = sorted(calls)
        if hasattr(method, "_body"):
            delattr(method, "_body")


def parse_java_file(path: Path, source_root: Path) -> JavaClass | None:
    text = read_text(path)
    class_match = re.search(r"\b(public\s+)?(abstract\s+|final\s+)?(class|interface|enum)\s+([A-Za-z_]\w*)", text)
    if not class_match:
        return None

    class_name = class_match.group(4)
    kind = class_match.group(3)
    class_prefix = text[: class_match.start()]
    annotations = parse_annotations(class_prefix[class_prefix.rfind("\n\n") + 2 :])
    request_invocations = list(re.finditer(r"@RequestMapping\s*\(([^)]*)\)", class_prefix, re.DOTALL))
    request_base = parse_mapping_path(request_invocations[-1].group(1)) if request_invocations else ""

    java_class = JavaClass(
        path=path.relative_to(source_root.parent.parent.parent.parent),
        package=parse_package(text),
        name=class_name,
        kind=kind,
        role=detect_role(path, text, class_name, annotations),
        module=detect_module(path),
        annotations=annotations,
        fields=parse_fields(text),
        methods=parse_methods(text, class_name, request_base),
        imports=parse_imports(text),
        request_base=request_base,
    )
    detect_calls(java_class)
    return java_class


def collect_classes(source_root: Path) -> list[JavaClass]:
    classes = []
    for path in sorted(source_root.rglob("*.java")):
        parsed = parse_java_file(path, source_root)
        if parsed:
            classes.append(parsed)
    return classes


def mermaid_label(name: str, role: str) -> str:
    prefixes = {
        "Controller": "[C]",
        "Service": "[S]",
        "Mapper": "[M]",
        "Consumer": "[Q]",
        "Config": "[CFG]",
        "Entity": "[E]",
        "DTO": "[D]",
        "Common": "[U]",
    }
    return f"{prefixes.get(role, '[J]')} {name}"


def build_layer_mermaid(classes: list[JavaClass]) -> str:
    lines = [
        "```mermaid",
        "flowchart LR",
        "  Client[前端 / Swagger / Agent调用] --> Controller[Controller 接口层]",
        "  Controller --> Service[Service 业务层]",
        "  Service --> Mapper[Mapper 数据访问层]",
        "  Mapper --> MySQL[(MySQL)]",
        "  Service --> Redis[(Redis)]",
        "  Service --> RocketMQ[(RocketMQ)]",
        "  Service --> ES[(Elasticsearch)]",
        "  Consumer[MQ Consumer] --> Service",
        "  DTO[DTO/VO] -.入参出参.-> Controller",
        "  Entity[Entity] -.表映射.-> Mapper",
    ]

    module_roles: dict[str, set[str]] = defaultdict(set)
    for item in classes:
        if item.role in {"Controller", "Service", "Mapper", "Consumer"}:
            module_roles[item.module].add(item.role)

    for module in sorted(module_roles):
        node = sanitize_id(f"mod_{module}")
        role_text = "/".join(sorted(module_roles[module]))
        lines.append(f"  {node}[{module}: {role_text}]")
        if "Controller" in module_roles[module]:
            lines.append(f"  {node} --> Controller")
        if "Service" in module_roles[module]:
            lines.append(f"  {node} --> Service")
        if "Mapper" in module_roles[module]:
            lines.append(f"  {node} --> Mapper")
    lines.append("```")
    return "\n".join(lines)


def build_call_mermaid(classes: list[JavaClass]) -> str:
    class_by_name = {item.name: item for item in classes}
    relevant_roles = {"Controller", "Service", "Consumer"}
    edges: set[tuple[str, str, str]] = set()

    for item in classes:
        if item.role not in relevant_roles:
            continue
        for method in item.methods:
            for call in method.calls:
                target_class, target_method = call.split(".", 1)
                if target_class in class_by_name:
                    edges.add((item.name, target_class, target_method.replace("()", "")))

    lines = ["```mermaid", "flowchart LR"]
    for item in classes:
        if item.role in {"Controller", "Service", "Mapper", "Consumer"}:
            node_id = sanitize_id(item.name)
            lines.append(f'  {node_id}["{mermaid_label(item.name, item.role)}"]')
    for source, target, method_name in sorted(edges):
        lines.append(f"  {sanitize_id(source)} -->|{method_name}| {sanitize_id(target)}")
    if not edges:
        lines.append("  Empty[未检测到调用边]")
    lines.append("```")
    return "\n".join(lines)


def endpoint_rows(classes: list[JavaClass]) -> list[list[str]]:
    rows = []
    for item in classes:
        for method in item.methods:
            if method.endpoint:
                rows.append(
                    [
                        method.endpoint.http_method,
                        method.endpoint.path,
                        f"{item.name}.{method.name}()",
                        ", ".join(method.calls) or "-",
                    ]
                )
    return sorted(rows, key=lambda row: (row[1], row[0], row[2]))


def class_dependency_names(item: JavaClass, class_names: set[str]) -> list[str]:
    deps = set()
    for dep_type in item.fields.values():
        if dep_type in class_names:
            deps.add(dep_type)
        elif dep_type in {"StringRedisTemplate", "RedisTemplate"}:
            deps.add("Redis")
        elif dep_type in {"RocketMQTemplate"}:
            deps.add("RocketMQ")
        elif dep_type in {"ElasticsearchOperations", "ElasticsearchClient"}:
            deps.add("Elasticsearch")
    return sorted(deps)


def build_methods_table(classes: list[JavaClass]) -> str:
    lines = [
        "| 模块 | 角色 | Java 文件 | 显式方法 | 直接依赖 |",
        "| --- | --- | --- | --- | --- |",
    ]
    class_names = {item.name for item in classes}
    for item in sorted(classes, key=lambda c: (c.module, c.role, c.name)):
        methods = "<br>".join(
            f"{method.visibility} {method.name}({escape_md(method.params)})"
            for method in item.methods
        )
        deps = ", ".join(class_dependency_names(item, class_names)) or "-"
        lines.append(
            "| "
            + " | ".join(
                [
                    escape_md(item.module),
                    escape_md(item.role),
                    escape_md(str(item.path)),
                    methods or "-",
                    escape_md(deps),
                ]
            )
            + " |"
        )
    return "\n".join(lines)


def build_call_table(classes: list[JavaClass]) -> str:
    lines = [
        "| 类 | 方法 | 检测到的下游调用 |",
        "| --- | --- | --- |",
    ]
    for item in sorted(classes, key=lambda c: (c.role, c.module, c.name)):
        if item.role not in {"Controller", "Service", "Consumer", "Common", "Advice", "Config"}:
            continue
        for method in item.methods:
            lines.append(
                "| "
                + " | ".join(
                    [
                        escape_md(item.name),
                        escape_md(f"{method.name}()"),
                        escape_md(", ".join(method.calls) or "-"),
                    ]
                )
                + " |"
            )
    return "\n".join(lines)


def build_module_table(classes: list[JavaClass]) -> str:
    counter: dict[str, Counter[str]] = defaultdict(Counter)
    for item in classes:
        counter[item.module][item.role] += 1

    roles = ["Controller", "Service", "Mapper", "Consumer", "DTO", "Entity", "Common", "Config", "Other"]
    lines = ["| 模块 | " + " | ".join(roles) + " |", "| --- | " + " | ".join(["---"] * len(roles)) + " |"]
    for module in sorted(counter):
        lines.append(
            "| "
            + escape_md(module)
            + " | "
            + " | ".join(str(counter[module].get(role, 0)) for role in roles)
            + " |"
        )
    return "\n".join(lines)


def build_endpoint_table(classes: list[JavaClass]) -> str:
    lines = [
        "| HTTP | 路径 | Controller 方法 | 进入的下游方法 |",
        "| --- | --- | --- | --- |",
    ]
    for row in endpoint_rows(classes):
        lines.append("| " + " | ".join(escape_md(cell) for cell in row) + " |")
    return "\n".join(lines)


def generate_markdown(classes: list[JavaClass], source_root: Path) -> str:
    role_counter = Counter(item.role for item in classes)
    endpoints = endpoint_rows(classes)
    method_count = sum(len(item.methods) for item in classes)

    return "\n\n".join(
        [
            "# LocalLife Server 代码关系图",
            (
                "> 本文档由 `python3 scripts/java_code_graph.py` 自动生成。"
                "它用于学习 `local-life-server` 的接口入口、类依赖和方法调用关系。"
            ),
            "## 1. 当前扫描结果\n\n"
            + "\n".join(
                [
                    f"- Java 文件数：{len(classes)}",
                    f"- 显式方法数：{method_count}",
                    f"- HTTP 接口数：{len(endpoints)}",
                    "- 角色分布："
                    + "，".join(f"{role}={count}" for role, count in sorted(role_counter.items())),
                    f"- 源码目录：`{source_root}`",
                ]
            ),
            "## 2. 怎么读这份图\n\n"
            "从上往下看：HTTP 请求先进入 Controller，Controller 只做参数接收和响应包装；"
            "真正的业务规则在 Service；Mapper 负责 MySQL；Redis/RocketMQ/ES 是 Service 旁路依赖。"
            "面试时不要只背接口，要能沿着 `接口 -> Controller -> Service -> Mapper/中间件 -> 数据一致性` 讲完整链路。",
            "## 3. 服务分层总览\n\n" + build_layer_mermaid(classes),
            "## 4. Controller/Service/Mapper 调用图\n\n"
            "这张图来自源码里的字段依赖和 `xxx.yyy()` 调用，适合快速看哪些类在协作。\n\n"
            + build_call_mermaid(classes),
            "## 5. 模块角色统计\n\n" + build_module_table(classes),
            "## 6. HTTP 接口入口表\n\n"
            "每一行都可以按 `Controller 方法 -> 进入的下游方法` 去代码里继续追。\n\n"
            + build_endpoint_table(classes),
            "## 7. 核心方法调用索引\n\n" + build_call_table(classes),
            "## 8. 所有 Java 文件索引\n\n"
            "这里列出每个 Java 文件的显式方法和直接依赖。DTO、Entity 主要承载数据结构，通常没有显式业务方法。\n\n"
            + build_methods_table(classes),
            "## 9. 生成方式与局限\n\n"
            "重新生成：\n\n"
            "```bash\npython3 scripts/java_code_graph.py\n```\n\n"
            "局限：这是轻量静态分析，不是完整 Java AST。它能稳定识别 Spring 接口、字段依赖和常见方法调用；"
            "但对反射、AOP、MyBatis 动态 SQL、Lombok 生成方法、链式泛型推断和运行时代理只能做近似。"
            "所以它适合学习和面试梳理，真正排障仍要结合 Swagger、日志、断点和测试。",
        ]
    ) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate local-life-server Java code graph docs.")
    parser.add_argument(
        "--source-root",
        default="local-life-server/src/main/java",
        help="Java source root to scan.",
    )
    parser.add_argument(
        "--output",
        default="docs/04-notes/LocalLifeServer代码关系图.md",
        help="Markdown output path.",
    )
    args = parser.parse_args()

    repo_root = Path.cwd()
    source_root = (repo_root / args.source_root).resolve()
    output = (repo_root / args.output).resolve()

    classes = collect_classes(source_root)
    markdown = generate_markdown(classes, source_root.relative_to(repo_root))
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(markdown, encoding="utf-8")

    print(f"Generated {output.relative_to(repo_root)}")
    print(f"Java files: {len(classes)}")
    print(f"HTTP endpoints: {len(endpoint_rows(classes))}")


if __name__ == "__main__":
    main()
