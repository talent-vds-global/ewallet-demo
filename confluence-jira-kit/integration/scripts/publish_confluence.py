#!/usr/bin/env python3
"""Đẩy các trang markdown trong confluence/pages lên Confluence Cloud.

  python publish_confluence.py --dry-run     # xem trước, không ghi gì
  python publish_confluence.py               # tạo hoặc cập nhật trang + gắn label
  python publish_confluence.py --only F1     # chỉ một trang

Chuyển đổi được thực hiện:
  - bảng markdown  -> bảng HTML (bảng đầu tiên tên "Page Properties" bọc trong macro Page Properties)
  - ```mermaid```  -> macro Code Block hoặc macro Mermaid, tuỳ MERMAID_MODE
  - ```gherkin```  -> macro Code Block
  - heading, list, đoạn văn, inline code, bold -> HTML tương ứng

Script cố ý KHÔNG dùng thư viện markdown đầy đủ: storage format của Confluence không phải HTML thuần,
và phần cần chính xác tuyệt đối (bảng, macro) thì tự sinh dễ kiểm soát hơn là hậu xử lý.
"""
from __future__ import annotations

import argparse
import html
import re
import sys

from _common import (MERMAID_MACRO, MERMAID_MODE, PAGE_MAP, PAGES_DIR, ROOT_TITLE,
                     SPACE_KEY, BASE_URL, require_credentials, session)

# --- Markdown -> Confluence storage format -----------------------------------


def esc(text: str) -> str:
    return html.escape(text, quote=False)


def inline(text: str) -> str:
    """Xử lý inline: code, bold, italic, link."""
    out = esc(text)
    out = re.sub(r"`([^`]+)`", r"<code>\1</code>", out)
    out = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", out)
    out = re.sub(r"(?<!\*)\*([^*]+)\*(?!\*)", r"<em>\1</em>", out)
    out = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r'<a href="\2">\1</a>', out)
    return out


def code_macro(language: str, body: str) -> str:
    return (
        '<ac:structured-macro ac:name="code">'
        f'<ac:parameter ac:name="language">{language}</ac:parameter>'
        f"<ac:plain-text-body><![CDATA[{body}]]></ac:plain-text-body>"
        "</ac:structured-macro>"
    )


def mermaid_block(body: str) -> str:
    if MERMAID_MODE == "macro":
        return (
            f'<ac:structured-macro ac:name="{MERMAID_MACRO}">'
            f"<ac:plain-text-body><![CDATA[{body}]]></ac:plain-text-body>"
            "</ac:structured-macro>"
        )
    # Giữ marker %% mermaid để Doc Indexer nhận ra đây là sơ đồ, không phải code thường.
    return code_macro("text", "%% mermaid\n" + body)


def table_html(headers: list[str], rows: list[list[str]]) -> str:
    th = "".join(f"<th>{inline(h)}</th>" for h in headers)
    trs = []
    for row in rows:
        cells = "".join(f"<td>{inline(c)}</td>" for c in row)
        trs.append(f"<tr>{cells}</tr>")
    return f"<table><tbody><tr>{th}</tr>{''.join(trs)}</tbody></table>"


def page_properties_macro(inner_table: str) -> str:
    return (
        '<ac:structured-macro ac:name="details">'
        f"<ac:rich-text-body>{inner_table}</ac:rich-text-body>"
        "</ac:structured-macro>"
    )


def convert(md: str) -> str:
    """Chuyển markdown của một trang sang storage format."""
    lines = md.splitlines()
    out: list[str] = []
    i = 0
    in_list = False
    # Bảng đầu tiên nằm ngay sau heading chứa "Page Properties" sẽ được bọc macro.
    next_table_is_props = False

    def close_list() -> None:
        nonlocal in_list
        if in_list:
            out.append("</ul>")
            in_list = False

    while i < len(lines):
        line = lines[i]

        # khối code
        m = re.match(r"^```(\w*)\s*$", line)
        if m:
            lang = m.group(1) or "text"
            body: list[str] = []
            i += 1
            while i < len(lines) and not lines[i].startswith("```"):
                body.append(lines[i])
                i += 1
            i += 1
            close_list()
            text = "\n".join(body)
            out.append(mermaid_block(text) if lang == "mermaid" else code_macro(lang, text))
            continue

        # bảng
        if line.strip().startswith("|") and i + 1 < len(lines) and re.match(
            r"^\s*\|[\s:|-]+\|\s*$", lines[i + 1]
        ):
            headers = [c.strip() for c in line.strip().strip("|").split("|")]
            rows = []
            i += 2
            while i < len(lines) and lines[i].strip().startswith("|"):
                rows.append([c.strip() for c in lines[i].strip().strip("|").split("|")])
                i += 1
            close_list()
            tbl = table_html(headers, rows)
            out.append(page_properties_macro(tbl) if next_table_is_props else tbl)
            next_table_is_props = False
            continue

        # heading
        m = re.match(r"^(#{1,6})\s+(.*)$", line)
        if m:
            close_list()
            level = min(len(m.group(1)) + 1, 6)  # h1 markdown -> h2 Confluence, chừa h1 cho tiêu đề trang
            title = m.group(2).strip()
            out.append(f"<h{level}>{inline(title)}</h{level}>")
            next_table_is_props = "page properties" in title.lower()
            i += 1
            continue

        # trích dẫn
        if line.startswith(">"):
            close_list()
            quote = [line.lstrip("> ").rstrip()]
            i += 1
            while i < len(lines) and lines[i].startswith(">"):
                quote.append(lines[i].lstrip("> ").rstrip())
                i += 1
            body = inline(" ".join(q for q in quote if q))
            out.append(
                '<ac:structured-macro ac:name="info"><ac:rich-text-body>'
                f"<p>{body}</p></ac:rich-text-body></ac:structured-macro>"
            )
            continue

        # danh sách
        m = re.match(r"^\s*[-*]\s+(.*)$", line) or re.match(r"^\s*\d+\.\s+(.*)$", line)
        if m:
            if not in_list:
                out.append("<ul>")
                in_list = True
            out.append(f"<li>{inline(m.group(1))}</li>")
            i += 1
            continue

        # đường kẻ ngang
        if re.match(r"^-{3,}$", line.strip()):
            close_list()
            out.append("<hr/>")
            i += 1
            continue

        if not line.strip():
            close_list()
            i += 1
            continue

        close_list()
        out.append(f"<p>{inline(line.strip())}</p>")
        i += 1

    close_list()
    return "".join(out)


# --- Confluence API ----------------------------------------------------------


def find_page(s, title: str):
    r = s.get(
        f"{BASE_URL}/wiki/rest/api/content",
        params={"spaceKey": SPACE_KEY, "title": title, "expand": "version"},
    )
    r.raise_for_status()
    results = r.json().get("results", [])
    return results[0] if results else None


def upsert_page(s, title: str, body: str, parent_id: str | None, dry_run: bool) -> str | None:
    existing = find_page(s, title)
    if dry_run:
        action = "CAP NHAT" if existing else "TAO MOI"
        print(f"  [dry-run] {action}: {title} ({len(body)} ky tu storage format)")
        return existing["id"] if existing else None

    payload = {
        "type": "page",
        "title": title,
        "space": {"key": SPACE_KEY},
        "body": {"storage": {"value": body, "representation": "storage"}},
    }
    if parent_id:
        payload["ancestors"] = [{"id": parent_id}]

    if existing:
        payload["version"] = {"number": existing["version"]["number"] + 1}
        r = s.put(f"{BASE_URL}/wiki/rest/api/content/{existing['id']}", json=payload)
    else:
        r = s.post(f"{BASE_URL}/wiki/rest/api/content", json=payload)
    if not r.ok:
        print(f"  LOI {r.status_code} khi ghi trang {title}: {r.text[:400]}", file=sys.stderr)
        r.raise_for_status()
    page_id = r.json()["id"]
    print(f"  {'cap nhat' if existing else 'tao moi'}: {title} -> {page_id}")
    return page_id


def set_labels(s, page_id: str, labels: list[str], dry_run: bool) -> None:
    if dry_run or not page_id:
        print(f"  [dry-run] label: {', '.join(labels)}")
        return
    r = s.post(
        f"{BASE_URL}/wiki/rest/api/content/{page_id}/label",
        json=[{"prefix": "global", "name": l} for l in labels],
    )
    if not r.ok:
        print(f"  CANH BAO: khong gan duoc label cho {page_id}: {r.text[:200]}", file=sys.stderr)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true", help="khong ghi len Confluence")
    ap.add_argument("--only", help="chi publish trang co filename chua chuoi nay")
    args = ap.parse_args()

    if not args.dry_run:
        require_credentials()
    s = session()

    ids: dict[str, str] = {}
    for spec in PAGE_MAP:
        if args.only and args.only not in (spec.filename or spec.title):
            continue
        print(f"\n{spec.title}")
        if spec.filename:
            path = PAGES_DIR / spec.filename
            if not path.exists():
                print(f"  BO QUA: khong tim thay {path}", file=sys.stderr)
                continue
            body = convert(path.read_text(encoding="utf-8"))
        else:
            # trang gom nhóm, chỉ có mục lục con
            body = ('<ac:structured-macro ac:name="children">'
                    '<ac:parameter ac:name="all">true</ac:parameter>'
                    "</ac:structured-macro>")
        parent_id = ids.get(spec.parent) if spec.parent else None
        if spec.parent and not parent_id and not args.dry_run:
            parent = find_page(s, spec.parent)
            parent_id = parent["id"] if parent else None
        page_id = upsert_page(s, spec.title, body, parent_id, args.dry_run)
        if page_id:
            ids[spec.title] = page_id
            set_labels(s, page_id, spec.labels, args.dry_run)

    print(f'\nXong. Kiem tra bang CQL: space = "{SPACE_KEY}" AND label = "vq-spec"')


if __name__ == "__main__":
    main()
