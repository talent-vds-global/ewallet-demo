#!/usr/bin/env python3
"""Gom bao cao JaCoCo cua tat ca service thanh mot bang do phu.

Moi service la mot project Maven doc lap (kieu multi-repo) nen khong co bao cao
gop san. Script nay doc `*/target/site/jacoco/jacoco.xml` roi in ra:

  - bang tong hop theo service (line / branch / method)
  - cac lop co do phu 0% (khoang trong test - dau vao cho Knowledge Graph)

Dung sau khi chay `mvn verify` o cac service.

    python scripts/coverage-report.py             # bang tong hop
    python scripts/coverage-report.py --gaps      # kem danh sach lop chua co test
    python scripts/coverage-report.py --json out.json   # xuat de may doc
"""

import argparse
import json
import pathlib
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent

# Lop khong mang logic nghiep vu - de trong bang cho do nhieu.
NOISE_SUFFIXES = ("Application", "Config", "Dtos")


def counters(element):
    """Tra ve {loai: (missed, covered)} cua mot node JaCoCo."""
    out = {}
    for c in element.findall("counter"):
        out[c.get("type")] = (int(c.get("missed")), int(c.get("covered")))
    return out


def ratio(pair):
    if not pair:
        return None
    missed, covered = pair
    total = missed + covered
    return None if total == 0 else covered / total


def fmt(value):
    return "  -  " if value is None else f"{value * 100:4.0f}%"


def read_service(xml_path):
    root = ET.parse(xml_path).getroot()
    totals = counters(root)

    classes = []
    for package in root.findall("package"):
        for klass in package.findall("class"):
            name = klass.get("name").split("/")[-1]
            if "$" in name:                      # lop long nhau, gop vao lop ngoai
                continue
            c = counters(klass)
            line = c.get("LINE")
            if not line or sum(line) == 0:       # interface, record rong...
                continue
            classes.append({
                "name": name,
                "package": package.get("name").replace("/", "."),
                "line": ratio(line),
                "branch": ratio(c.get("BRANCH")),
                "lines_total": sum(line),
                "lines_covered": line[1],
            })

    return {
        "line": ratio(totals.get("LINE")),
        "branch": ratio(totals.get("BRANCH")),
        "method": ratio(totals.get("METHOD")),
        "lines_total": sum(totals.get("LINE", (0, 0))),
        "lines_covered": totals.get("LINE", (0, 0))[1],
        "classes": classes,
    }


def main():
    parser = argparse.ArgumentParser(description="Bang do phu test cua ewallet-demo")
    parser.add_argument("--gaps", action="store_true",
                        help="liet ke cac lop co logic nhung chua duoc test cham toi")
    parser.add_argument("--json", metavar="FILE",
                        help="ghi ket qua ra file JSON cho collector doc")
    args = parser.parse_args()

    services = {}
    for xml_path in sorted(ROOT.glob("*/target/site/jacoco/jacoco.xml")):
        services[xml_path.parents[3].name] = read_service(xml_path)

    if not services:
        print("Chua co bao cao JaCoCo nao. Chay `mvn verify` o tung service truoc.",
              file=sys.stderr)
        return 1

    width = max(len(n) for n in services)
    print(f"{'SERVICE'.ljust(width)}   LINE  BRANCH  METHOD   (dong duoc phu / tong)")
    print("-" * (width + 45))

    total_lines = total_covered = 0
    for name, data in services.items():
        total_lines += data["lines_total"]
        total_covered += data["lines_covered"]
        print(f"{name.ljust(width)}  {fmt(data['line'])}  {fmt(data['branch'])}  "
              f"{fmt(data['method'])}   {data['lines_covered']:>5} / {data['lines_total']:<5}")

    print("-" * (width + 45))
    overall = total_covered / total_lines if total_lines else 0
    print(f"{'TOAN BO'.ljust(width)}  {fmt(overall)}                  "
          f"{total_covered:>5} / {total_lines:<5}")

    if args.gaps:
        print("\nLOP CHUA CO TEST CHAM TOI (do phu dong = 0%)")
        print("-" * (width + 45))
        found = False
        for name, data in services.items():
            gaps = [c for c in data["classes"]
                    if c["line"] == 0 and not c["name"].endswith(NOISE_SUFFIXES)]
            if not gaps:
                continue
            found = True
            print(f"  {name}")
            for c in sorted(gaps, key=lambda c: -c["lines_total"]):
                print(f"      {c['name']:<34} {c['lines_total']:>4} dong")
        if not found:
            print("  (khong co)")

    if args.json:
        out = pathlib.Path(args.json)
        out.write_text(json.dumps(services, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"\nDa ghi {out}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
