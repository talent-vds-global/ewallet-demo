#!/usr/bin/env python3
"""Kéo Confluence space về máy cho Doc Indexer.

  python export_confluence.py                # xuất storage format + markdown vào ./export
  python export_confluence.py --list-urls    # chỉ in tiêu đề + URL (dùng để điền Jira)
  python export_confluence.py --json         # xuất một file JSON gộp cho collector

Đây là bản tham chiếu: nó cho thấy chính xác dữ liệu mà Doc Indexer sẽ nhận, để kiểm tra tài liệu
trước khi cắm collector thật vào.
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

from _common import BASE_URL, SPACE_KEY, require_credentials, session

CQL = f'space = "{SPACE_KEY}" AND label = "vq-spec"'


def fetch_pages(s) -> list[dict]:
    pages, start = [], 0
    while True:
        r = s.get(
            f"{BASE_URL}/wiki/rest/api/content/search",
            params={
                "cql": CQL,
                "limit": 50,
                "start": start,
                "expand": "body.storage,version,metadata.labels,ancestors",
            },
        )
        r.raise_for_status()
        data = r.json()
        pages.extend(data.get("results", []))
        if data.get("size", 0) < data.get("limit", 0) or not data.get("_links", {}).get("next"):
            break
        start += data.get("limit", 50)
    return pages


def storage_to_text(storage: str) -> str:
    """Rút gọn storage format thành text đọc được, giữ nguyên nội dung macro code/mermaid."""
    text = re.sub(r"<!\[CDATA\[(.*?)\]\]>", r"\n```\n\1\n```\n", storage, flags=re.S)
    text = re.sub(r"<br\s*/?>", "\n", text)
    text = re.sub(r"</(p|h[1-6]|tr|li)>", "\n", text)
    text = re.sub(r"</t[hd]>", " | ", text)
    text = re.sub(r"<[^>]+>", "", text)
    text = text.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    return re.sub(r"\n{3,}", "\n\n", text).strip()


def labels_of(page: dict) -> list[str]:
    return [l["name"] for l in page.get("metadata", {}).get("labels", {}).get("results", [])]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--list-urls", action="store_true")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--out", default="./export")
    args = ap.parse_args()

    require_credentials()
    s = session()
    pages = fetch_pages(s)

    if args.list_urls:
        for p in sorted(pages, key=lambda x: x["title"]):
            print(f"{p['title']}\t{BASE_URL}/wiki{p['_links']['webui']}")
        return

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    bundle = []
    for p in pages:
        storage = p.get("body", {}).get("storage", {}).get("value", "")
        record = {
            "page_id": p["id"],
            "title": p["title"],
            "version": p["version"]["number"],
            "labels": labels_of(p),
            "url": f"{BASE_URL}/wiki{p['_links']['webui']}",
            "ancestors": [a["title"] for a in p.get("ancestors", [])],
            "storage": storage,
            "text": storage_to_text(storage),
        }
        bundle.append(record)
        safe = re.sub(r"[^\w.-]+", "-", p["title"]).strip("-")
        (out_dir / f"{safe}.txt").write_text(record["text"], encoding="utf-8")
        (out_dir / f"{safe}.storage.html").write_text(storage, encoding="utf-8")

    if args.json:
        (out_dir / "confluence-bundle.json").write_text(
            json.dumps(bundle, ensure_ascii=False, indent=2), encoding="utf-8"
        )

    flows = [p for p in bundle if "vq-flow" in p["labels"]]
    print(f"Xuat {len(bundle)} trang vao {out_dir} ({len(flows)} trang flow).")
    if len(flows) != 6:
        print(f"CANH BAO: mong doi 6 trang flow, thay {len(flows)}. "
              "Kiem tra label vq-flow.")


if __name__ == "__main__":
    main()
