#!/usr/bin/env python3
"""Kiểm 8 bất biến giữa kit, docs/specs, Confluence và Jira.

  python validate_specs.py                       # chỉ kiểm cục bộ (kit vs docs/specs)
  python validate_specs.py --confluence          # thêm kiểm Confluence thật
  python validate_specs.py --jira                # thêm kiểm Jira thật
  python validate_specs.py --confluence --jira   # kiểm đầy đủ

Exit code 0 khi không có lỗi, 1 khi có. Dùng được trong CI.

Lưu ý: script KHÔNG báo lỗi cho 6 lỗi có chủ đích trong code. Đó là drift giữa tài liệu và code —
việc của nền tảng v-quality, không phải của kiểm tra cấu trúc tài liệu.
"""
from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

from _common import (FLOW_SLUGS, NFR_RE, OTEL_SERVICES, PAGES_DIR, PAGE_MAP, RULE_RE,
                     iter_markdown_tables, iter_mermaid_blocks, read_page_markdown,
                     strip_code)

SPECS_DIR = Path(os.getenv("SPECS_DIR", "../../../ewallet-demo/docs/specs"))

errors: list[str] = []
warnings: list[str] = []


def err(msg: str) -> None:
    errors.append(msg)


def warn(msg: str) -> None:
    warnings.append(msg)


FLOW_FILES = {
    "F1": "F1-topup-partner.md",
    "F2": "F2-bill-telco.md",
    "F3": "F3-p2p-transfer.md",
    "F4": "F4-failure-refund.md",
    "F5": "F5-async-notification.md",
    "F6": "F6-transaction-history.md",
}

REQUIRED_PROPS = [
    "Flow ID", "Flow Slug", "Flow Name", "Actor", "Trigger", "Loại", "Services",
    "Protocols", "Entry Endpoint", "Jira Epic", "Spec Source", "Doc Version", "Status",
]


def page_properties(md: str) -> dict[str, str]:
    """Đọc bảng 2 cột ngay sau heading 'Page Properties'."""
    section = md.split("Page Properties", 1)
    if len(section) < 2:
        return {}
    for headers, rows in iter_markdown_tables(section[1]):
        if len(headers) == 2 and headers[0].lower().startswith("khoá"):
            return {strip_code(r[0]): strip_code(r[1]) for r in rows if len(r) == 2}
    return {}


def rule_rows(md: str):
    for headers, rows in iter_markdown_tables(md):
        if headers[:4] == ["Mã", "Điều kiện", "Hành động", "Severity"]:
            for r in rows:
                if len(r) >= 4:
                    yield r


def nfr_rows(md: str):
    for headers, rows in iter_markdown_tables(md):
        if headers[:5] == ["Mã", "metric", "operator", "threshold", "unit"]:
            for r in rows:
                if len(r) >= 5:
                    yield r


# --- Bất biến ---------------------------------------------------------------


def check_1_slugs() -> None:
    """Mỗi flow có slug, khớp giữa kit và docs/specs."""
    for fid, filename in FLOW_FILES.items():
        md = read_page_markdown(filename)
        props = page_properties(md)
        slug = props.get("Flow Slug")
        if slug != FLOW_SLUGS[fid]:
            err(f"[1] {filename}: Flow Slug la '{slug}', mong doi '{FLOW_SLUGS[fid]}'")
        for key in REQUIRED_PROPS:
            if key not in props:
                err(f"[1] {filename}: Page Properties thieu khoa '{key}'")

    if SPECS_DIR.exists():
        for fid, slug in FLOW_SLUGS.items():
            hits = [p for p in SPECS_DIR.glob("F*.md") if slug in p.read_text(encoding="utf-8")]
            if not hits:
                err(f"[1] slug '{slug}' khong xuat hien trong {SPECS_DIR}")
    else:
        warn(f"[1] Khong tim thay {SPECS_DIR}, bo qua doi chieu voi docs/specs")


def kit_rule_codes() -> set[str]:
    codes: set[str] = set()
    for spec in PAGE_MAP:
        if not spec.filename:
            continue
        md = read_page_markdown(spec.filename)
        codes |= {strip_code(r[0]) for r in rule_rows(md)}
        # mục 3.2 liệt kê rule chung dạng danh sách inline
        for line in md.splitlines():
            if line.strip().startswith("`R-") or " · " in line:
                codes |= set(RULE_RE.findall(line))
    return {c for c in codes if RULE_RE.fullmatch(c)}


def check_2_rule_parity() -> None:
    kit = kit_rule_codes()
    if not SPECS_DIR.exists():
        warn("[2] Khong co docs/specs, bo qua doi chieu tap rule")
        return
    spec: set[str] = set()
    for p in SPECS_DIR.glob("*.md"):
        spec |= set(RULE_RE.findall(p.read_text(encoding="utf-8")))
    for code in sorted(spec - kit):
        err(f"[2] Rule {code} co trong docs/specs nhung thieu trong kit Confluence")
    for code in sorted(kit - spec):
        err(f"[2] Rule {code} co trong kit Confluence nhung khong co trong docs/specs")


def check_4_nfr_shape() -> None:
    seen: dict[str, str] = {}
    for spec in PAGE_MAP:
        if not spec.filename:
            continue
        md = read_page_markdown(spec.filename)
        for row in nfr_rows(md):
            code, metric, op, threshold, unit = (strip_code(c) for c in row[:5])
            if not NFR_RE.fullmatch(code):
                err(f"[4] {spec.filename}: ma NFR khong hop le '{code}'")
                continue
            if op not in {"<", "<=", ">", ">="}:
                err(f"[4] {code}: operator khong hop le '{op}'")
            try:
                float(threshold.replace(".", "").replace(",", ".")
                      if threshold.count(".") > 1 else threshold)
            except ValueError:
                err(f"[4] {code}: threshold '{threshold}' khong parse duoc thanh so")
            if not unit:
                err(f"[4] {code}: thieu unit")
            if not metric:
                err(f"[4] {code}: thieu metric")
            seen.setdefault(code, spec.filename)

    catalog = read_page_markdown("91-NFR-CATALOG.md")
    catalog_codes = {strip_code(r[0]) for r in nfr_rows(catalog)}
    for code in sorted(set(seen) - catalog_codes):
        err(f"[4] {code} xuat hien o trang flow nhung khong co trong [CATALOG] NFR")


def check_5_6_mermaid() -> None:
    for fid, filename in FLOW_FILES.items():
        md = read_page_markdown(filename)
        blocks = list(iter_mermaid_blocks(md))
        if not blocks:
            err(f"[5] {filename}: khong co khoi mermaid nao")
            continue
        if not any("sequenceDiagram" in b for b in blocks):
            err(f"[5] {filename}: khong co sequenceDiagram")
        for b in blocks:
            for m in re.finditer(r"participant\s+\w+\s+as\s+(.+)", b):
                name = m.group(1).strip()
                if name in OTEL_SERVICES:
                    continue
                if name.startswith("Kafka ") or name.endswith("db"):
                    continue
                warn(f"[6] {filename}: participant '{name}' khong khop OTEL_SERVICE_NAME nao")


def check_headings() -> None:
    required = ["1. Mô tả chung", "2. Luồng nghiệp vụ", "3. Business rule", "4. NFR",
                "5. Acceptance criteria", "6. Bảng/Thực thể liên quan",
                "7. Danh sách mã lỗi", "8. Chức năng ảnh hưởng",
                "9. Bảng ghi nhận thay đổi tài liệu"]
    for filename in FLOW_FILES.values():
        md = read_page_markdown(filename)
        for h in required:
            if h not in md:
                err(f"[heading] {filename}: thieu muc '{h}'")


# --- Kiểm tra online --------------------------------------------------------


def check_confluence() -> None:
    from _common import BASE_URL, SPACE_KEY, require_credentials, session
    require_credentials()
    s = session()
    r = s.get(f"{BASE_URL}/wiki/rest/api/content/search",
              params={"cql": f'space = "{SPACE_KEY}" AND label = "vq-spec"', "limit": 100,
                      "expand": "metadata.labels"})
    r.raise_for_status()
    pages = r.json().get("results", [])
    flows = [p for p in pages
             if "vq-flow" in [l["name"] for l in
                              p.get("metadata", {}).get("labels", {}).get("results", [])]]
    print(f"  Confluence: {len(pages)} trang vq-spec, {len(flows)} trang vq-flow")
    if len(flows) != 6:
        err(f"[confluence] mong doi 6 trang vq-flow, thay {len(flows)}")
    titles = {p["title"] for p in pages}
    for spec in PAGE_MAP:
        if spec.title not in titles:
            err(f"[confluence] thieu trang '{spec.title}'")


def check_jira() -> None:
    from _common import BASE_URL, JIRA_PROJECT, require_credentials, session
    require_credentials()
    s = session()
    cf_rule = os.getenv("JIRA_CF_RULE_CODES", "")
    cf_defect = os.getenv("JIRA_CF_DEFECT_ID", "")
    cf_page = os.getenv("JIRA_CF_CONFLUENCE_PAGE", "")
    fields = ["summary", "issuetype", "labels"] + [f for f in (cf_rule, cf_defect, cf_page) if f]
    r = s.post(f"{BASE_URL}/rest/api/3/search",
               json={"jql": f"project = {JIRA_PROJECT}", "maxResults": 200, "fields": fields})
    r.raise_for_status()
    issues = r.json().get("issues", [])
    epics = [i for i in issues if i["fields"]["issuetype"]["name"] == "Epic"]
    print(f"  Jira: {len(issues)} issue, {len(epics)} epic")
    if len(epics) != 6:
        err(f"[7] mong doi 6 epic, thay {len(epics)}")
    if cf_page:
        for e in epics:
            if not e["fields"].get(cf_page):
                err(f"[7] epic {e['key']} thieu Confluence Page")
    if cf_defect:
        ids = sorted(int(i["fields"][cf_defect]) for i in issues
                     if i["fields"].get(cf_defect) is not None)
        if ids != [1, 2, 3, 4, 5, 6]:
            err(f"[8] Defect ID mong doi [1..6], thay {ids}")

    # bất biến 3: rule must phải có ít nhất một issue
    if cf_rule:
        covered: set[str] = set()
        for i in issues:
            raw = i["fields"].get(cf_rule) or []
            if isinstance(raw, str):
                raw = RULE_RE.findall(raw)
            covered |= set(raw)
        must: set[str] = set()
        for spec in PAGE_MAP:
            if not spec.filename:
                continue
            for row in rule_rows(read_page_markdown(spec.filename)):
                if strip_code(row[3]) == "must":
                    must.add(strip_code(row[0]))
        for code in sorted(must - covered):
            warn(f"[3] rule must '{code}' chua co issue Jira nao tham chieu")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--confluence", action="store_true")
    ap.add_argument("--jira", action="store_true")
    ap.add_argument("--specs", help="duong dan docs/specs")
    args = ap.parse_args()

    global SPECS_DIR
    if args.specs:
        SPECS_DIR = Path(args.specs)

    print("Kiem tra cuc bo (kit vs docs/specs)...")
    check_1_slugs()
    check_2_rule_parity()
    check_4_nfr_shape()
    check_5_6_mermaid()
    check_headings()

    if args.confluence:
        print("Kiem tra Confluence...")
        check_confluence()
    if args.jira:
        print("Kiem tra Jira...")
        check_jira()

    print()
    for w in warnings:
        print(f"CANH BAO {w}")
    for e in errors:
        print(f"LOI      {e}")
    print(f"\nTong: {len(errors)} loi, {len(warnings)} canh bao")
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
