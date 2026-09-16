#!/usr/bin/env python3
"""Kéo issue Jira của project EWL về JSON cho Knowledge Graph.

  python export_jira.py                  # xuất ./export/jira-issues.json
  python export_jira.py --coverage       # in bảng: rule nào chưa có issue nào cài đặt

Tuỳ chọn --coverage trả lời đúng một câu hỏi mà Lead hay hỏi: "quy định nào đang không có ai chịu
trách nhiệm?". Nó đọc mã rule từ Confluence (qua export_confluence) hoặc từ docs/specs, rồi trừ đi
tập mã đã xuất hiện ở field Rule Codes của Jira.
"""
from __future__ import annotations

import argparse
import json
import os
import re
from pathlib import Path

from _common import (BASE_URL, JIRA_PROJECT, RULE_RE, require_credentials, session)

CF = {
    "flow_slug": os.getenv("JIRA_CF_FLOW_SLUG", ""),
    "rule_codes": os.getenv("JIRA_CF_RULE_CODES", ""),
    "nfr_codes": os.getenv("JIRA_CF_NFR_CODES", ""),
    "confluence_page": os.getenv("JIRA_CF_CONFLUENCE_PAGE", ""),
    "service": os.getenv("JIRA_CF_SERVICE", ""),
    "defect_id": os.getenv("JIRA_CF_DEFECT_ID", ""),
    "verdict": os.getenv("JIRA_CF_VERDICT", ""),
}


def fetch_issues(s) -> list[dict]:
    issues, start = [], 0
    fields = ["summary", "issuetype", "status", "components", "labels",
              "priority", "fixVersions", "parent"] + [v for v in CF.values() if v]
    while True:
        r = s.post(
            f"{BASE_URL}/rest/api/3/search",
            json={"jql": f"project = {JIRA_PROJECT} ORDER BY key",
                  "startAt": start, "maxResults": 100, "fields": fields},
        )
        r.raise_for_status()
        data = r.json()
        issues.extend(data.get("issues", []))
        start += data.get("maxResults", 100)
        if start >= data.get("total", 0):
            break
    return issues


def value_of(raw):
    """Chuẩn hoá giá trị custom field về str hoặc list[str]."""
    if raw is None:
        return None
    if isinstance(raw, dict):
        return raw.get("value") or raw.get("name")
    if isinstance(raw, list):
        return [value_of(x) for x in raw]
    return raw


def normalize(issue: dict) -> dict:
    f = issue["fields"]
    rec = {
        "key": issue["key"],
        "type": f["issuetype"]["name"],
        "status": f["status"]["name"],
        "summary": f["summary"],
        "priority": (f.get("priority") or {}).get("name"),
        "components": [c["name"] for c in f.get("components", [])],
        "labels": f.get("labels", []),
        "fix_versions": [v["name"] for v in f.get("fixVersions", [])],
        "parent": (f.get("parent") or {}).get("key"),
    }
    for name, cf_id in CF.items():
        if cf_id:
            rec[name] = value_of(f.get(cf_id))
    return rec


def collect_spec_rules() -> set[str]:
    specs = Path(os.getenv("SPECS_DIR", "../../../ewallet-demo/docs/specs"))
    codes: set[str] = set()
    if specs.exists():
        for p in specs.glob("*.md"):
            codes |= set(RULE_RE.findall(p.read_text(encoding="utf-8")))
    return codes


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--coverage", action="store_true")
    ap.add_argument("--out", default="./export")
    args = ap.parse_args()

    require_credentials()
    s = session()
    records = [normalize(i) for i in fetch_issues(s)]

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "jira-issues.json").write_text(
        json.dumps(records, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Xuat {len(records)} issue vao {out_dir / 'jira-issues.json'}")

    if args.coverage:
        covered: set[str] = set()
        for r in records:
            rc = r.get("rule_codes") or []
            if isinstance(rc, str):
                rc = RULE_RE.findall(rc)
            covered |= {c for c in rc if c}
        spec_rules = collect_spec_rules()
        if not spec_rules:
            print("Khong doc duoc docs/specs, bo qua phan coverage.")
            return
        missing = sorted(spec_rules - covered)
        print(f"\nRule trong tai lieu: {len(spec_rules)}")
        print(f"Rule co it nhat mot issue: {len(spec_rules & covered)}")
        print(f"Rule CHUA co issue nao: {len(missing)}")
        for code in missing:
            print(f"  - {code}")


if __name__ == "__main__":
    main()
