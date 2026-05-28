"""
RAG 知识库入库脚本。

功能：
  扫描 rag/knowledge_base/ 目录下的所有 Markdown 文件，
  解析、切分、向量化、写入 Milvus。

运行方式：
  cd copilot-agent-service
  python -m rag.ingest                    # 入库所有公共文档
  python -m rag.ingest --file path.md     # 入库指定文件
  python -m rag.ingest --merchant 20001 --file path.md   # 入库为某商家私有文档

文件命名约定：
  rag/knowledge_base/*.md                 → scope=public（所有人可见）
  rag/knowledge_base/merchant_{id}/*.md   → scope=merchant_private（仅该商家可见）

每个 Markdown 文件的 metadata：
  - doc_id：文件路径的 hash（md5）
  - title：从第一行 # 标题提取
  - source：从文件名推断（platform_rules.md → platform_rule）
  - scope：根据目录结构推断
  - merchant_id：merchant_{id}/ 目录或 --merchant 参数指定
"""
import argparse
import asyncio
import hashlib
import re
import sys
from pathlib import Path
import structlog

from rag.pipeline import ingest_document

log = structlog.get_logger(__name__)

# 知识库根目录
KB_ROOT = Path(__file__).parent / "knowledge_base"


def _doc_id_for(path: Path) -> str:
    """根据文件绝对路径生成稳定的 doc_id（MD5）。"""
    return hashlib.md5(str(path.resolve()).encode("utf-8")).hexdigest()[:16]


def _title_from_markdown(content: str) -> str:
    """从 Markdown 第一行 # 标题提取标题，无标题时用空字符串。"""
    for line in content.splitlines():
        line = line.strip()
        if line.startswith("# "):
            return line[2:].strip()
    return "未命名文档"


def _source_from_filename(filename: str) -> str:
    """从文件名推断来源类型，如 platform_rules.md → platform_rule。"""
    name = filename.replace(".md", "").lower()
    if "rule" in name or "policy" in name:
        return "platform_rule"
    if "faq" in name:
        return "customer_faq"
    if "trouble" in name or "case" in name:
        return "troubleshooting"
    if "announce" in name or "notice" in name:
        return "announcement"
    return "general"


def _detect_scope(path: Path) -> tuple[str, int]:
    """
    从文件路径推断权限范围。

    /knowledge_base/platform_rules.md                  → (public, 0)
    /knowledge_base/merchant_20001/private_notes.md    → (merchant_private, 20001)
    """
    relative = path.relative_to(KB_ROOT)
    parts = relative.parts
    if len(parts) >= 2 and parts[0].startswith("merchant_"):
        try:
            merchant_id = int(parts[0].replace("merchant_", ""))
            return "merchant_private", merchant_id
        except ValueError:
            pass
    return "public", 0


async def ingest_file(file_path: Path, override_scope: str | None = None,
                      override_merchant: int | None = None) -> int:
    """
    入库单个 Markdown 文件。

    :param file_path: Markdown 文件绝对路径
    :param override_scope: 覆盖自动检测的 scope（可选）
    :param override_merchant: 覆盖自动检测的 merchant_id（可选）
    :return: 入库的分块数
    """
    if not file_path.exists():
        log.error("file_not_found", path=str(file_path))
        return 0
    if not file_path.is_file() or file_path.suffix != ".md":
        log.warning("skipped_non_markdown", path=str(file_path))
        return 0

    content = file_path.read_text(encoding="utf-8")
    if not content.strip():
        log.warning("skipped_empty_file", path=str(file_path))
        return 0

    title  = _title_from_markdown(content)
    source = _source_from_filename(file_path.name)

    # 权限范围检测
    detected_scope, detected_merchant = _detect_scope(file_path)
    scope = override_scope or detected_scope
    merchant_id = override_merchant if override_merchant is not None else detected_merchant

    doc_id = _doc_id_for(file_path)

    log.info(
        "ingesting_doc",
        doc_id=doc_id,
        title=title,
        source=source,
        scope=scope,
        merchant_id=merchant_id,
        file=file_path.name,
    )

    chunks = await ingest_document(
        doc_id=doc_id,
        title=title,
        content=content,
        source=source,
        scope=scope,
        merchant_id=merchant_id,
        chunk_size=500,
        overlap=50,
    )

    return chunks


async def ingest_all(override_scope: str | None = None,
                     override_merchant: int | None = None) -> dict:
    """
    扫描 KB_ROOT 下所有 Markdown 文件批量入库。

    :return: 入库统计 {"files": N, "chunks": M}
    """
    if not KB_ROOT.exists():
        log.error("kb_root_not_found", path=str(KB_ROOT))
        return {"files": 0, "chunks": 0}

    md_files = sorted(KB_ROOT.glob("**/*.md"))
    log.info("ingest_start", file_count=len(md_files))

    total_chunks = 0
    success_count = 0

    for md_file in md_files:
        try:
            chunks = await ingest_file(md_file, override_scope, override_merchant)
            if chunks > 0:
                success_count += 1
                total_chunks += chunks
        except Exception as e:
            log.error("ingest_failed", file=str(md_file), error=str(e))

    log.info("ingest_complete", files=success_count, chunks=total_chunks)
    return {"files": success_count, "chunks": total_chunks}


async def main():
    parser = argparse.ArgumentParser(description="LocalLife Copilot RAG 知识库入库")
    parser.add_argument("--file", type=str, help="入库指定文件路径（默认入库整个 knowledge_base 目录）")
    parser.add_argument("--scope", choices=["public", "merchant_private"],
                        help="覆盖文件检测的权限范围")
    parser.add_argument("--merchant", type=int, help="商家 ID（scope=merchant_private 时必填）")
    args = parser.parse_args()

    if args.file:
        chunks = await ingest_file(Path(args.file), args.scope, args.merchant)
        print(f"✓ 入库完成：{chunks} 个分块")
    else:
        result = await ingest_all(args.scope, args.merchant)
        print(f"✓ 入库完成：{result['files']} 个文件，共 {result['chunks']} 个分块")
        if result["chunks"] == 0:
            print("⚠ 警告：没有任何分块入库。可能原因：")
            print("  1. Milvus 未启动（检查 MILVUS_URI）")
            print("  2. knowledge_base/ 目录为空")
            print("  3. sentence-transformers 模型下载失败")
            sys.exit(1)


if __name__ == "__main__":
    asyncio.run(main())
