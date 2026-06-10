from __future__ import annotations

import argparse
import ast
import json
import os
import re
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

from playwright.sync_api import BrowserContext, Page, sync_playwright


DEFAULT_CHROME_PATHS = [
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
]

POST_END_MARKERS = [
    "\n#",
    "\n首次评论",
    "\n一键发评",
    "\n评论\n",
    "\n全部评论",
    "\n相关推荐",
    "\n相关推荐",
    "\n全站热榜",
    "\n正在热议",
]

NO_CONTENT_MARKERS = [
    "请登录",
    "登录 / 注册",
    "登录/注册",
    "来晚一步",
    "内容已经被删除",
    "该内容已经被删除",
]


@dataclass
class ExtractedPost:
    title: str
    main_text: str
    body_text: str
    extraction_method: str
    start_index: int
    end_index: int


def normalize_text(text: str) -> str:
    text = text.strip()
    if "\\n" in text and text.count("\n") <= 2:
        try:
            decoded = ast.literal_eval(text)
            if isinstance(decoded, str):
                text = decoded
        except Exception:
            text = text.replace("\\n", "\n")
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    lines = [line.strip() for line in text.split("\n")]
    text = "\n".join(line for line in lines if line)
    return re.sub(r"\n{3,}", "\n\n", text).strip()


def find_browser_executable(explicit_path: str | None) -> str | None:
    if explicit_path:
        return explicit_path
    for path in DEFAULT_CHROME_PATHS:
        if Path(path).exists():
            return path
    return None


def parse_cookie_header(cookie_header: str) -> list[dict[str, object]]:
    cookies = []
    for chunk in cookie_header.split(";"):
        if "=" not in chunk:
            continue
        name, value = chunk.strip().split("=", 1)
        if not name:
            continue
        cookies.append(
            {
                "name": name,
                "value": value,
                "domain": ".nowcoder.com",
                "path": "/",
                "secure": True,
                "sameSite": "Lax",
            }
        )
    return cookies


def load_cookie_header(cookie_file: str | None, cookie_env: str | None) -> str:
    if cookie_file:
        return Path(cookie_file).read_text(encoding="utf-8").strip()
    if cookie_env:
        return os.environ.get(cookie_env, "").strip()
    return ""


def read_urls(args: argparse.Namespace) -> list[str]:
    urls: list[str] = []
    if args.urls_file:
        urls.extend(
            line.strip()
            for line in Path(args.urls_file).read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.strip().startswith("#")
        )
    urls.extend(args.url or [])
    seen = set()
    deduped = []
    for url in urls:
        if url not in seen:
            seen.add(url)
            deduped.append(url)
    return deduped


def choose_best_dom_block(page: Page) -> dict[str, object] | None:
    blocks = page.evaluate(
        """
        () => {
          const rejectTags = new Set(["SCRIPT", "STYLE", "NOSCRIPT", "svg", "path"]);
          const candidates = [];
          const nodes = document.querySelectorAll("article, main, section, div, [class*='content'], [class*='detail'], [class*='feed']");
          for (const el of nodes) {
            if (rejectTags.has(el.tagName)) continue;
            const rect = el.getBoundingClientRect();
            if (rect.width < 300 || rect.height < 120) continue;
            const style = window.getComputedStyle(el);
            if (style.display === "none" || style.visibility === "hidden") continue;
            const text = (el.innerText || "").trim();
            if (text.length < 300) continue;
            if (text.includes("全部评论") && text.indexOf("全部评论") < 200) continue;
            const contentHints = (text.match(/Q\\d+|Agent|ReAct|MCP|A2A|面试|项目|简历/g) || []).length;
            const noiseHints = (text.match(/全站热榜|正在热议|相关推荐|创作者周榜/g) || []).length;
            const score = text.length + contentHints * 350 - noiseHints * 500;
            candidates.push({
              tag: el.tagName.toLowerCase(),
              className: el.className ? String(el.className) : "",
              text,
              score,
              rect: { x: rect.x, y: rect.y, width: rect.width, height: rect.height }
            });
          }
          candidates.sort((a, b) => b.score - a.score);
          return candidates[0] || null;
        }
        """
    )
    return blocks if isinstance(blocks, dict) else None


def trim_at_end_markers(text: str) -> tuple[str, int]:
    end = len(text)
    for marker in POST_END_MARKERS:
        idx = text.find(marker)
        if idx != -1:
            end = min(end, idx)
    return text[:end].strip(), end


def clean_main_text(text: str) -> str:
    for marker in ("登录 / 注册", "登录/注册"):
        idx = text.find(marker)
        if 0 <= idx <= 300:
            return text[idx + len(marker) :].strip()
    return text


def extract_from_body_text(body_text: str) -> ExtractedPost:
    text = normalize_text(body_text)
    trimmed, end = trim_at_end_markers(text)

    lines = trimmed.split("\n")
    title = ""
    for line in lines:
        if len(line) >= 8 and not any(nav in line for nav in ["首页", "题库", "搜索", "会员"]):
            if re.search(r"Agent|AI|面试|简历|项目|Q\d+|MCP|A2A", line):
                title = line
                break
    if not title and lines:
        title = lines[0]

    start = 0
    focus_marker = "\n关注\n"
    focus_index = trimmed.find(focus_marker)
    if focus_index != -1:
        start = focus_index + len(focus_marker)
        trimmed = trimmed[start:].strip()

    if title:
        title_index = trimmed.find(title)
        if title_index != -1:
            start += title_index
            trimmed = trimmed[title_index:].strip()

    return ExtractedPost(
        title=title,
        main_text=trimmed,
        body_text=text,
        extraction_method="body_text_slice",
        start_index=start,
        end_index=end,
    )


def extract_post(page: Page) -> ExtractedPost:
    body_text = normalize_text(page.locator("body").inner_text(timeout=30_000))
    body_fallback = extract_from_body_text(body_text)

    block = choose_best_dom_block(page)
    if not block:
        return body_fallback

    block_text = normalize_text(str(block.get("text", "")))
    block_text, block_end = trim_at_end_markers(block_text)

    if len(block_text) < 300 or len(block_text) < len(body_fallback.main_text) * 0.25:
        return body_fallback

    title = body_fallback.title
    block_lines = block_text.split("\n")
    for line in block_lines[:8]:
        if re.search(r"Agent|AI|面试|简历|项目|Q\d+|MCP|A2A", line):
            title = line
            break

    return ExtractedPost(
        title=title,
        main_text=block_text,
        body_text=body_text,
        extraction_method="dom_block_score",
        start_index=body_text.find(block_text[:80]),
        end_index=block_end,
    )


def wait_for_content(page: Page, require_pattern: str | None, timeout_ms: int) -> None:
    deadline = time.monotonic() + timeout_ms / 1000
    pattern = re.compile(require_pattern) if require_pattern else None
    last_text = ""
    stable_rounds = 0

    while time.monotonic() < deadline:
        try:
            text = page.locator("body").inner_text(timeout=5_000)
        except Exception:
            time.sleep(1)
            continue
        if pattern and pattern.search(text):
            return
        if not pattern and len(text) > 1_000 and text == last_text:
            stable_rounds += 1
            if stable_rounds >= 2:
                return
        else:
            stable_rounds = 0
            last_text = text
        time.sleep(1)


def goto_with_fallback(page: Page, url: str, timeout_ms: int) -> None:
    try:
        page.goto(url, wait_until="domcontentloaded", timeout=timeout_ms)
        return
    except Exception as first_error:
        try:
            existing_text = page.locator("body").inner_text(timeout=3_000)
            if len(existing_text.strip()) > 200:
                return
        except Exception:
            pass
        try:
            page.goto(url, wait_until="commit", timeout=timeout_ms)
            return
        except Exception as second_error:
            raise RuntimeError(f"navigation failed: {first_error!r}; fallback failed: {second_error!r}") from second_error


def fetch_one(
    page: Page,
    url: str,
    require_pattern: str | None,
    timeout_ms: int,
    min_main_chars: int,
) -> dict[str, object]:
    goto_with_fallback(page, url, timeout_ms)
    wait_for_content(page, require_pattern, timeout_ms)
    extracted = extract_post(page)
    main_text = clean_main_text(extracted.main_text)
    no_content_hits = [marker for marker in NO_CONTENT_MARKERS if marker in main_text]
    content_ok = len(main_text) >= min_main_chars and not no_content_hits

    return {
        "url": url,
        "page_title": page.title(),
        "extracted_title": extracted.title,
        "main_text": main_text,
        "body_text": extracted.body_text,
        "main_text_chars": len(main_text),
        "body_text_chars": len(extracted.body_text),
        "extraction_method": extracted.extraction_method,
        "start_index": extracted.start_index,
        "end_index": extracted.end_index,
        "content_ok": content_ok,
        "possible_login_required": not content_ok,
        "missing_content_markers": no_content_hits,
    }


def extract_current_page(page: Page, min_main_chars: int) -> dict[str, object]:
    extracted = extract_post(page)
    main_text = clean_main_text(extracted.main_text)
    no_content_hits = [marker for marker in NO_CONTENT_MARKERS if marker in main_text]
    content_ok = len(main_text) >= min_main_chars and not no_content_hits

    return {
        "url": page.url,
        "page_title": page.title(),
        "extracted_title": extracted.title,
        "main_text": main_text,
        "body_text": extracted.body_text,
        "main_text_chars": len(main_text),
        "body_text_chars": len(extracted.body_text),
        "extraction_method": f"current_page:{extracted.extraction_method}",
        "start_index": extracted.start_index,
        "end_index": extracted.end_index,
        "content_ok": content_ok,
        "possible_login_required": not content_ok,
        "missing_content_markers": no_content_hits,
    }


def iter_pages_from_context(context: BrowserContext) -> Iterable[Page]:
    for page in context.pages:
        yield page


def choose_current_page(context: BrowserContext) -> Page:
    pages = list(iter_pages_from_context(context))
    for page in pages:
        if "nowcoder.com/feed/main/detail/" in page.url:
            return page
    for page in pages:
        if "nowcoder.com" in page.url:
            return page
    return pages[0] if pages else context.new_page()


def run(args: argparse.Namespace) -> int:
    urls = read_urls(args)
    if not urls and not args.current_page and not args.list_pages:
        print("No URLs provided. Use --urls-file or --url.", file=sys.stderr)
        return 2

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    cookie_header = load_cookie_header(args.cookie_file, args.cookie_env)
    browser_executable = find_browser_executable(args.browser)

    with sync_playwright() as p:
        if args.cdp:
            browser = p.chromium.connect_over_cdp(args.cdp)
            context = browser.contexts[0] if browser.contexts else browser.new_context(locale="zh-CN")
            close_context = False
        else:
            launch_args = ["--disable-blink-features=AutomationControlled"]
            if args.profile:
                context = p.chromium.launch_persistent_context(
                    user_data_dir=args.profile,
                    executable_path=browser_executable,
                    headless=not args.headed,
                    locale="zh-CN",
                    viewport={"width": 1400, "height": 1000},
                    args=launch_args,
                )
            else:
                browser = p.chromium.launch(
                    executable_path=browser_executable,
                    headless=not args.headed,
                    args=launch_args,
                )
                context = browser.new_context(locale="zh-CN", viewport={"width": 1400, "height": 1000})
            close_context = True

        if cookie_header:
            context.add_cookies(parse_cookie_header(cookie_header))

        if args.list_pages:
            for idx, existing_page in enumerate(iter_pages_from_context(context), start=1):
                print(f"[{idx}] {existing_page.url} | {existing_page.title()}")
            if close_context:
                context.close()
            else:
                browser.close()
            return 0

        page = choose_current_page(context) if args.current_page else (next(iter_pages_from_context(context), None) or context.new_page())
        if args.login_wait_seconds > 0:
            page.goto("https://www.nowcoder.com/", wait_until="domcontentloaded", timeout=args.timeout_ms)
            print(f"Waiting {args.login_wait_seconds}s for manual login...")
            page.wait_for_timeout(args.login_wait_seconds * 1000)

        with out_path.open("w", encoding="utf-8") as out:
            if args.current_page:
                print(f"[current] {page.url}")
                try:
                    record = extract_current_page(page, args.min_main_chars)
                    print(
                        "  ok "
                        f"main={record['main_text_chars']} "
                        f"body={record['body_text_chars']} "
                        f"method={record['extraction_method']} "
                        f"content_ok={record['content_ok']}"
                    )
                except Exception as exc:
                    record = {"url": page.url, "error": repr(exc)}
                    print(f"  failed {exc}", file=sys.stderr)
                out.write(json.dumps(record, ensure_ascii=False) + "\n")
                out.flush()

            for idx, url in enumerate(urls, start=1):
                print(f"[{idx}/{len(urls)}] {url}")
                try:
                    record = fetch_one(page, url, args.require, args.timeout_ms, args.min_main_chars)
                    print(
                        "  ok "
                        f"main={record['main_text_chars']} "
                        f"body={record['body_text_chars']} "
                        f"method={record['extraction_method']} "
                        f"content_ok={record['content_ok']}"
                    )
                except Exception as exc:
                    record = {"url": url, "error": repr(exc)}
                    print(f"  failed {exc}", file=sys.stderr)
                out.write(json.dumps(record, ensure_ascii=False) + "\n")
                out.flush()
                time.sleep(args.delay)

        if close_context:
            context.close()
        else:
            browser.close()

    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Batch extract rendered Nowcoder post text into JSONL.")
    parser.add_argument("--urls-file", help="Text file containing one post URL per line.")
    parser.add_argument("--url", action="append", help="Single post URL. Can be repeated.")
    parser.add_argument("--out", default="data/nowcoder_posts.jsonl", help="Output JSONL path.")
    parser.add_argument("--profile", default=".browser_profiles/nowcoder", help="Persistent browser profile dir.")
    parser.add_argument("--no-profile", dest="profile", action="store_const", const=None)
    parser.add_argument("--headed", action="store_true", help="Show browser. Use this for first login.")
    parser.add_argument("--browser", help="Chrome/Edge executable path.")
    parser.add_argument("--cdp", help="Connect to an already-open Chrome DevTools endpoint, e.g. http://127.0.0.1:9222")
    parser.add_argument("--list-pages", action="store_true", help="List pages visible through --cdp and exit.")
    parser.add_argument("--current-page", action="store_true", help="Extract the already-open Nowcoder tab without navigating.")
    parser.add_argument("--cookie-file", help="File containing a raw Cookie header value.")
    parser.add_argument("--cookie-env", help="Environment variable containing a raw Cookie header value.")
    parser.add_argument("--require", help="Regex that must appear in rendered text before extraction.")
    parser.add_argument("--min-main-chars", type=int, default=500)
    parser.add_argument("--login-wait-seconds", type=int, default=0, help="Open Nowcoder and wait for manual login before fetching.")
    parser.add_argument("--timeout-ms", type=int, default=90_000)
    parser.add_argument("--delay", type=float, default=2.0)
    return parser


if __name__ == "__main__":
    raise SystemExit(run(build_parser().parse_args()))
