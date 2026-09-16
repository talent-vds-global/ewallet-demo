"""Tiện ích dùng chung cho các script Confluence/Jira."""
from __future__ import annotations

import os
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

import requests
from dotenv import load_dotenv

load_dotenv(Path(__file__).with_name(".env"))

BASE_URL = os.getenv("ATLASSIAN_BASE_URL", "").rstrip("/")
EMAIL = os.getenv("ATLASSIAN_EMAIL", "")
TOKEN = os.getenv("ATLASSIAN_API_TOKEN", "")
SPACE_KEY = os.getenv("CONFLUENCE_SPACE_KEY", "EWL")
ROOT_TITLE = os.getenv("CONFLUENCE_ROOT_PAGE_TITLE", "E-Wallet Business Specs")
JIRA_PROJECT = os.getenv("JIRA_PROJECT_KEY", "EWL")
MERMAID_MODE = os.getenv("MERMAID_MODE", "codeblock")
MERMAID_MACRO = os.getenv("MERMAID_MACRO_NAME", "mermaid-cloud")

KIT_DIR = (Path(__file__).parent / os.getenv("KIT_DIR", "../..")).resolve()
PAGES_DIR = KIT_DIR / "confluence" / "pages"


def require_credentials() -> None:
    missing = [n for n, v in (("ATLASSIAN_BASE_URL", BASE_URL),
                              ("ATLASSIAN_EMAIL", EMAIL),
                              ("ATLASSIAN_API_TOKEN", TOKEN)) if not v]
    if missing:
        sys.exit(f"Thieu bien moi truong: {', '.join(missing)}. Xem .env.example")


def session() -> requests.Session:
    s = requests.Session()
    s.auth = (EMAIL, TOKEN)
    s.headers.update({"Accept": "application/json", "Content-Type": "application/json"})
    return s


# --- Ánh xạ file kit -> trang Confluence -------------------------------------

@dataclass
class PageSpec:
    filename: str
    title: str
    parent: str | None
    labels: list[str] = field(default_factory=list)


GROUP_FLOWS = "Flow nghiep vu"
GROUP_CATALOG = "Catalog"

PAGE_MAP: list[PageSpec] = [
    PageSpec("00-HOME.md", ROOT_TITLE, None, ["vq-spec"]),
    PageSpec("01-DOMAIN-CONVENTIONS.md", "[COMMON] Mien nghiep vu va quy uoc", ROOT_TITLE,
             ["vq-spec", "vq-domain"]),
    PageSpec("02-API-CONTRACTS.md", "[API] Hop dong REST gRPC Kafka", ROOT_TITLE,
             ["vq-spec", "vq-api"]),
    PageSpec("", GROUP_FLOWS, ROOT_TITLE, ["vq-spec"]),
    PageSpec("F1-topup-partner.md", "[F1] Nap tien vi qua doi tac", GROUP_FLOWS,
             ["vq-spec", "vq-flow", "flow-f1", "slug-topup-partner"]),
    PageSpec("F2-bill-telco.md", "[F2] Thanh toan hoa don va nap telco", GROUP_FLOWS,
             ["vq-spec", "vq-flow", "flow-f2", "slug-bill-telco-payment"]),
    PageSpec("F3-p2p-transfer.md", "[F3] Chuyen tien P2P", GROUP_FLOWS,
             ["vq-spec", "vq-flow", "flow-f3", "slug-p2p-transfer"]),
    PageSpec("F4-failure-refund.md", "[F4] Giao dich loi va hoan tien", GROUP_FLOWS,
             ["vq-spec", "vq-flow", "flow-f4", "slug-failure-refund"]),
    PageSpec("F5-async-notification.md", "[F5] Thong bao bat dong bo", GROUP_FLOWS,
             ["vq-spec", "vq-flow", "flow-f5", "slug-async-notification"]),
    PageSpec("F6-transaction-history.md", "[F6] Tra cuu lich su giao dich", GROUP_FLOWS,
             ["vq-spec", "vq-flow", "flow-f6", "slug-transaction-history"]),
    PageSpec("", GROUP_CATALOG, ROOT_TITLE, ["vq-spec"]),
    PageSpec("90-RULE-CATALOG.md", "[CATALOG] Business Rule", GROUP_CATALOG,
             ["vq-spec", "vq-rule-catalog"]),
    PageSpec("91-NFR-CATALOG.md", "[CATALOG] NFR", GROUP_CATALOG,
             ["vq-spec", "vq-nfr"]),
    PageSpec("92-TRACEABILITY-MATRIX.md", "[CATALOG] Traceability", GROUP_CATALOG,
             ["vq-spec", "vq-traceability"]),
]

FLOW_SLUGS = {
    "F1": "topup-partner",
    "F2": "bill-telco-payment",
    "F3": "p2p-transfer",
    "F4": "failure-refund",
    "F5": "async-notification",
    "F6": "transaction-history",
}

OTEL_SERVICES = {
    "ewallet-gateway",
    "ewallet-business-customer-mobileapp",
    "ewallet-payment-order",
    "ewallet-payment-business",
    "ewallet-third-party",
    "ewallet-notification",
    "partner-sim",
    "platform-vquality",
}

RULE_RE = re.compile(r"\bR-[A-Z]+-\d+\b")
NFR_RE = re.compile(r"\bNFR-[A-Z]+-\d+\b")


def read_page_markdown(filename: str) -> str:
    return (PAGES_DIR / filename).read_text(encoding="utf-8")


def iter_markdown_tables(md: str):
    """Sinh ra (headers, rows) cho mỗi bảng markdown trong văn bản."""
    lines = md.splitlines()
    i = 0
    while i < len(lines):
        line = lines[i]
        if line.strip().startswith("|") and i + 1 < len(lines) and re.match(
            r"^\s*\|[\s:|-]+\|\s*$", lines[i + 1]
        ):
            headers = [c.strip() for c in line.strip().strip("|").split("|")]
            rows = []
            i += 2
            while i < len(lines) and lines[i].strip().startswith("|"):
                rows.append([c.strip() for c in lines[i].strip().strip("|").split("|")])
                i += 1
            yield headers, rows
        else:
            i += 1


def iter_mermaid_blocks(md: str):
    for m in re.finditer(r"```mermaid\n(.*?)```", md, re.S):
        yield m.group(1)


def strip_code(text: str) -> str:
    return text.replace("`", "").strip()
