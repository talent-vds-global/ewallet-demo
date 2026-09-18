#!/usr/bin/env python3
"""Kiem infra/v-quality/flow-map.yaml tren trace that.

Doc infra/otel-collector/traces/traces*.jsonl (ca ban da xoay vong), gom span theo traceId
roi phan loai tung trace theo flow-map. In ra:

  - so trace moi flow + ket cuc (COMPLETED / HELD / ...)
  - so trace duoc gan nhan phu (F4, F5) va so lan xu ly Kafka theo x-attempt
  - route cua cua vao CHUA duoc map (loi -> exit 1, dung duoc trong CI)

Day chi la kiem cau hinh, khong phai Trace Analyzer: khong tinh baseline, khong tim loi.

    python scripts/flow-map-check.py
    python scripts/flow-map-check.py --traces duong/dan/traces.jsonl

Can PyYAML (pip install pyyaml).
"""

import argparse
import collections
import fnmatch
import json
import pathlib
import sys

try:
    import yaml
except ImportError:
    print("Thieu PyYAML: pip install pyyaml", file=sys.stderr)
    sys.exit(2)

ROOT = pathlib.Path(__file__).resolve().parent.parent
TRACE_DIR = ROOT / "infra" / "otel-collector" / "traces"
FLOW_MAP = ROOT / "infra" / "v-quality" / "flow-map.yaml"

# Ma kind cua OTLP JSON
KIND = {"INTERNAL": 1, "SERVER": 2, "CLIENT": 3, "PRODUCER": 4, "CONSUMER": 5}


def attr_value(value):
    """OTLP JSON boc gia tri trong {"stringValue": ...}; mang thi tra list."""
    if not value:
        return None
    if "arrayValue" in value:
        return [attr_value(v) for v in value["arrayValue"].get("values", [])]
    return next(iter(value.values()))


def attrs(items):
    return {kv["key"]: attr_value(kv.get("value")) for kv in items or []}


def trace_files(explicit):
    if explicit:
        return [pathlib.Path(p) for p in explicit]
    # ban cu traces-<timestamp>.jsonl sap theo ten = theo thoi gian, file dang ghi o cuoi
    rotated = sorted(TRACE_DIR.glob("traces-*.jsonl"))
    current = TRACE_DIR / "traces.jsonl"
    return rotated + ([current] if current.exists() else [])


def load_traces(files):
    traces = collections.defaultdict(list)
    for path in files:
        with path.open(encoding="utf-8") as fh:
            for line in fh:
                if not line.strip():
                    continue
                # moi dong la mot lo span cua nhieu service, khong phai mot trace
                for rs in json.loads(line).get("resourceSpans", []):
                    service = attrs(rs["resource"].get("attributes")).get("service.name")
                    for ss in rs.get("scopeSpans", []):
                        for s in ss.get("spans", []):
                            traces[s["traceId"]].append({
                                "service": service,
                                "name": s["name"],
                                "kind": s.get("kind"),
                                "start": int(s["startTimeUnixNano"]),
                                "attrs": attrs(s.get("attributes")),
                            })
    return traces


def route_ignored(route, patterns):
    return any(fnmatch.fnmatch(route or "", p.replace("**", "*")) for p in patterns)


def span_matches(span, cond):
    if "kind" in cond and span["kind"] != KIND[cond["kind"]]:
        return False
    value = span["attrs"].get(cond["attribute"])
    if "equals" in cond:
        return value == cond["equals"]
    return value in cond.get("in", [])


def classify(spans, fm):
    entry_cfg = fm["entry"]
    servers = sorted((s for s in spans
                      if s["kind"] == KIND["SERVER"]
                      and s["service"] not in entry_cfg["skip_services"]),
                     key=lambda s: s["start"])
    consumers = sorted((s for s in spans if s["kind"] == KIND["CONSUMER"]),
                       key=lambda s: s["start"])

    if servers:
        entry = servers[0]
        key = (entry["service"],
               entry["attrs"].get(entry_cfg["method_attribute"]),
               entry["attrs"].get(entry_cfg["route_attribute"]))
    elif consumers and entry_cfg.get("consumer_as_entry"):
        entry = consumers[0]
        key = (entry["service"], "CONSUME", entry["attrs"].get("messaging.destination.name"))
    else:
        return "background", None, None, []

    if key[1] != "CONSUME" and route_ignored(key[2], fm["ignore"]["routes"]):
        return "ignored", key, None, []

    primary = None
    for flow in fm["flows"]:
        for e in flow.get("entries", []):
            if (e["service"], e["method"], e["route"]) == key:
                primary = flow["code"]

    outcome = None
    oc = fm["outcome"]
    for s in spans:
        sp = oc["span"]
        if (s["service"] == sp["service"] and s["kind"] == KIND[sp["kind"]]
                and s["attrs"].get("http.request.method") == sp["method"]
                and s["attrs"].get("http.route") == sp["route"]):
            outcome = oc["map"].get(str(s["attrs"].get(oc["attribute"])), "UNKNOWN")

    overlays = []
    for flow in fm["flows"]:
        for ov in flow.get("overlays", []):
            hit = False
            if "when_span" in ov:
                hit = any(span_matches(s, ov["when_span"]) for s in spans)
            if "when_outcome" in ov:
                hit = hit or outcome in ov["when_outcome"]
            if hit:
                overlays.append(f"{flow['code']}:{ov['variant']}")

    # trace chi co consumer (vd retry chay o luong rieng) -> thuoc F5
    if primary is None and key[1] == "CONSUME":
        primary = "F5"
    return (primary or "unmapped"), key, outcome, overlays


def main():
    parser = argparse.ArgumentParser(description="Kiem flow-map.yaml tren trace that")
    parser.add_argument("--traces", nargs="*", help="file jsonl (mac dinh: traces*.jsonl cua otel-collector)")
    parser.add_argument("--flow-map", default=str(FLOW_MAP))
    args = parser.parse_args()

    fm = yaml.safe_load(pathlib.Path(args.flow_map).read_text(encoding="utf-8"))
    files = trace_files(args.traces)
    if not files:
        print(f"Khong co file trace trong {TRACE_DIR}. Chay scripts/demo-flows truoc.", file=sys.stderr)
        return 1
    traces = load_traces(files)

    per_flow = collections.Counter()
    per_outcome = collections.Counter()
    per_overlay = collections.Counter()
    unmapped = collections.Counter()
    slugs = {f["code"]: f["slug"] for f in fm["flows"]}

    for spans in traces.values():
        flow, key, outcome, overlays = classify(spans, fm)
        per_flow[flow] += 1
        if flow in slugs:
            per_outcome[(flow, outcome or "-")] += 1
        for ov in overlays:
            per_overlay[ov] += 1
        if flow == "unmapped":
            unmapped[key] += 1

    attempt_attr = fm["attempts"]["attribute"]
    dlt_suffix = fm["attempts"]["dead_letter_topic_suffix"]
    attempts = collections.Counter()
    for spans in traces.values():
        for s in spans:
            if s["kind"] not in (KIND["PRODUCER"], KIND["CONSUMER"]):
                continue
            raw = s["attrs"].get(attempt_attr)
            n = raw[0] if isinstance(raw, list) and raw else raw
            topic = s["attrs"].get("messaging.destination.name") or ""
            kind = "dead_letter" if topic.endswith(dlt_suffix) else (
                "first_attempt" if n in (None, "1") else "retry")
            attempts[(kind, "co x-attempt" if raw is not None else "KHONG co x-attempt")] += 1

    print(f"{len(files)} file, {len(traces)} trace\n")
    print("TRACE THEO FLOW")
    for code in [f["code"] for f in fm["flows"]] + ["background", "ignored", "unmapped"]:
        label = f"{code} {slugs.get(code, '')}".strip()
        print(f"  {label:<32} {per_flow.get(code, 0):>6}")

    print("\nKET CUC (flow ghi)")
    for (flow, outcome), n in sorted(per_outcome.items()):
        print(f"  {flow:<4} {outcome:<20} {n:>6}")

    print("\nNHAN PHU")
    for ov, n in sorted(per_overlay.items()):
        print(f"  {ov:<32} {n:>6}")

    print("\nSPAN KAFKA THEO LAN XU LY")
    for (kind, has), n in sorted(attempts.items()):
        print(f"  {kind:<14} {has:<20} {n:>6}")

    if unmapped:
        print("\nCUA VAO CHUA DUOC MAP (them vao flows[].entries hoac ignore.routes)")
        for (svc, method, route), n in unmapped.most_common():
            print(f"  {n:>5}  {svc}  {method} {route}")
        return 1
    print("\nOK: moi trace co cua vao deu thuoc mot flow hoac bi bo qua co chu dich.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
