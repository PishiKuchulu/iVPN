"""CLI: collected links file -> seed-format JSON for the backend builder.

Usage:
    python3 cli.py links.txt [-o records.json] [--first-seen ISO8601]

Input: one share link per line; blank lines and lines starting with "//"
are ignored (note: "#" cannot be a comment marker, it starts a link's
display-name fragment).

Output: {"sample_only": false, "configs": [ConfigRecord, ...]} — the exact
shape ~/workspace/autoconnect-backend/scripts/build-bundle.mjs consumes as
its seed. Each record gains first_seen + source_count (dedupe count);
operator_scores starts empty and is filled by client reports, never here.
Rejections are printed to stderr as a summary and never abort the run.
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone

from parser import dedupe_records, parse_links


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("input", help="text file with one share link per line")
    ap.add_argument("-o", "--output", default="-",
                    help="output JSON path (default: stdout)")
    ap.add_argument("--first-seen", default=None,
                    help="ISO-8601 timestamp stamped on every record "
                         "(default: now, UTC)")
    args = ap.parse_args(argv)

    with open(args.input, "r", encoding="utf-8") as fh:
        links = [ln.strip() for ln in fh
                 if ln.strip() and not ln.strip().startswith("//")]

    result = parse_links(links)
    first_seen = args.first_seen or datetime.now(timezone.utc).strftime(
        "%Y-%m-%dT%H:%M:%SZ")
    configs = []
    for rec in dedupe_records(result["records"]):
        rec.setdefault("operator_scores", {})
        rec["first_seen"] = first_seen
        configs.append(rec)

    payload = {"sample_only": False, "configs": configs}
    text = json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True)
    if args.output == "-":
        print(text)
    else:
        with open(args.output, "w", encoding="utf-8") as fh:
            fh.write(text + "\n")

    print(f"links={len(links)} parsed={len(result['records'])} "
          f"unique={len(configs)} rejected={len(result['rejections'])}",
          file=sys.stderr)
    for rej in result["rejections"]:
        print(f"REJECT [{rej['code']}] {rej['detail']} :: {rej['link'][:80]}",
              file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
