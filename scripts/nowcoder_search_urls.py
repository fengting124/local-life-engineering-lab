from __future__ import annotations

import argparse
import re
import sys
import time
from pathlib import Path
from urllib.parse import quote_plus, urljoin, urlsplit, urlunsplit

from playwright.sync_api import BrowserContext, Page, sync_playwright


DEFAULT_CHROME_PATHS = [
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
]

DETAIL_RE = re.compile(r"(?:https://www\.nowcoder\.com)?/feed/main/detail/[0-9a-fA-F]{32}(?:\?[^#\s\"'<>]*)?")


def find_browser_executable(explicit_path: str | None) -> str | None:
    if explicit_path:
        return explicit_path
    for path in DEFAULT_CHROME_PATHS:
        if Path(path).exists():
            return path
    return None


def normalize_detail_url(url: str, keep_query: bool) -> str:
    url = urljoin("https://www.nowcoder.com", url)
    parts = urlsplit(url)
    if not keep_query:
        return urlunsplit((parts.scheme, parts.netloc, parts.path, "", ""))
    return urlunsplit((parts.scheme, parts.netloc, parts.path, parts.query, ""))


def collect_detail_urls(page: Page, keep_query: bool) -> list[str]:
    hrefs = page.evaluate(
        """
        () => Array.from(document.querySelectorAll("a[href]"))
          .map(a => new URL(a.getAttribute("href"), location.href).href)
        """
    )
    urls = []
    for href in hrefs:
        for match in DETAIL_RE.findall(str(href)):
            urls.append(normalize_detail_url(match, keep_query))

    html = page.content()
    for match in DETAIL_RE.findall(html):
        urls.append(normalize_detail_url(match, keep_query))

    seen = set()
    deduped = []
    for url in urls:
        if url not in seen:
            seen.add(url)
            deduped.append(url)
    return deduped


def search_nowcoder_urls_for_keyword(
    page: Page,
    keyword: str,
    pages: int,
    keep_query: bool,
    delay: float,
    timeout_ms: int,
) -> list[str]:
    collected: list[str] = []
    seen = set()

    for page_no in range(1, pages + 1):
        url = f"https://www.nowcoder.com/search/all?query={quote_plus(keyword)}&page={page_no}"
        print(f"nowcoder search keyword={keyword!r} page={page_no}")
        try:
            page.goto(url, wait_until="domcontentloaded", timeout=timeout_ms)
            page.wait_for_timeout(3_000)
            page.evaluate("window.scrollTo(0, document.body.scrollHeight)")
            page.wait_for_timeout(1_000)
        except Exception as exc:
            print(f"  search page failed: {exc}", file=sys.stderr)
            continue

        urls = collect_detail_urls(page, keep_query)
        print(f"  found {len(urls)} candidate urls")
        for detail_url in urls:
            if detail_url not in seen:
                seen.add(detail_url)
                collected.append(detail_url)
        time.sleep(delay)

    return collected


def search_external_urls_for_keyword(
    page: Page,
    keyword: str,
    pages: int,
    keep_query: bool,
    delay: float,
    timeout_ms: int,
) -> list[str]:
    collected: list[str] = []
    seen = set()

    for page_no in range(1, pages + 1):
        first = (page_no - 1) * 10 + 1
        query = f"site:nowcoder.com/feed/main/detail {keyword}"
        url = f"https://www.bing.com/search?q={quote_plus(query)}&first={first}"
        print(f"bing search keyword={keyword!r} page={page_no}")
        try:
            page.goto(url, wait_until="domcontentloaded", timeout=timeout_ms)
            page.wait_for_timeout(2_000)
        except Exception as exc:
            print(f"  search page failed: {exc}", file=sys.stderr)
            continue

        urls = collect_detail_urls(page, keep_query)
        print(f"  found {len(urls)} candidate urls")
        for detail_url in urls:
            if detail_url not in seen:
                seen.add(detail_url)
                collected.append(detail_url)
        time.sleep(delay)

    return collected


def iter_pages_from_context(context: BrowserContext):
    for page in context.pages:
        yield page


def run(args: argparse.Namespace) -> int:
    keywords = []
    keywords.extend(args.keyword or [])
    if args.keywords_file:
        keywords.extend(
            line.strip()
            for line in Path(args.keywords_file).read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.strip().startswith("#")
        )
    if not keywords:
        print("No keywords provided. Use --keyword or --keywords-file.", file=sys.stderr)
        return 2

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    browser_executable = find_browser_executable(args.browser)

    with sync_playwright() as p:
        if args.cdp:
            browser = p.chromium.connect_over_cdp(args.cdp)
            context = browser.contexts[0] if browser.contexts else browser.new_context(locale="zh-CN")
            close_context = False
        elif args.profile:
            context = p.chromium.launch_persistent_context(
                user_data_dir=args.profile,
                executable_path=browser_executable,
                headless=not args.headed,
                locale="zh-CN",
                viewport={"width": 1400, "height": 1000},
            )
            close_context = True
        else:
            browser = p.chromium.launch(executable_path=browser_executable, headless=not args.headed)
            context = browser.new_context(locale="zh-CN", viewport={"width": 1400, "height": 1000})
            close_context = True

        page = next(iter_pages_from_context(context), None) or context.new_page()
        all_urls = []
        seen = set()
        for keyword in keywords:
            urls = []
            if args.source in ("nowcoder", "both"):
                urls.extend(
                    search_nowcoder_urls_for_keyword(
                        page, keyword, args.pages, args.keep_query, args.delay, args.timeout_ms
                    )
                )
            if args.source in ("external", "both"):
                urls.extend(
                    search_external_urls_for_keyword(
                        page, keyword, args.pages, args.keep_query, args.delay, args.timeout_ms
                    )
                )
            for url in urls:
                if url not in seen:
                    seen.add(url)
                    all_urls.append(url)
                    if len(all_urls) >= args.limit:
                        break
            if len(all_urls) >= args.limit:
                break

        out_path.write_text("\n".join(all_urls) + ("\n" if all_urls else ""), encoding="utf-8")
        print(f"saved {len(all_urls)} urls to {out_path}")

        if close_context:
            context.close()
        else:
            browser.close()

    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Search candidate Nowcoder post detail URLs by keyword.")
    parser.add_argument("--keyword", action="append", help="Keyword query. Can be repeated.")
    parser.add_argument("--keywords-file", help="Text file with one keyword query per line.")
    parser.add_argument("--out", default="data/nowcoder_candidate_urls.txt")
    parser.add_argument("--limit", type=int, default=50)
    parser.add_argument("--pages", type=int, default=2, help="Search result pages per keyword.")
    parser.add_argument("--source", choices=["nowcoder", "external", "both"], default="nowcoder")
    parser.add_argument("--keep-query", action="store_true", help="Keep query strings on Nowcoder detail URLs.")
    parser.add_argument("--profile", default=".browser_profiles/nowcoder_search")
    parser.add_argument("--no-profile", dest="profile", action="store_const", const=None)
    parser.add_argument("--headed", action="store_true")
    parser.add_argument("--browser", help="Chrome/Edge executable path.")
    parser.add_argument("--cdp", help="Connect to an already-open Chrome DevTools endpoint.")
    parser.add_argument("--timeout-ms", type=int, default=45_000)
    parser.add_argument("--delay", type=float, default=1.5)
    return parser


if __name__ == "__main__":
    raise SystemExit(run(build_parser().parse_args()))
