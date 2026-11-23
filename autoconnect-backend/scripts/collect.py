"""Automated collector: Telegram web view -> canonical parser -> config pool -> seed.

Runs in GitHub Actions (see .github/workflows/collector.yml) and locally:
    python3 scripts/collect.py [--dry-run]

Pipeline position: this script refreshes seed/pool.json (the persistent pool)
and seed/configs.json (the bundle seed). scripts/build-bundle.mjs then merges
the pool with the KV client-report aggregation (clients are the ONLY
dead/alive authority, doc section 4 as corrected 2026-11-21) and signs the
bundle. The collector itself never judges a config dead; it only tracks when
a config was last SEEN in a source. Pool entries unseen for POOL_TTL_DAYS are
dropped as stale inventory, which is source hygiene, not a liveness verdict.

Guards (a bad run must never shrink or empty the published bundle):
  - zero unique configs parsed across all sources  -> exit 2, nothing written
  - resulting seed would hold < MIN_SEED configs   -> exit 3, nothing written
All computation happens in memory; files are written only after guards pass.

The parser is the single canonical implementation in
autoconnect-core/parser/parser.py (same module the CLI and the parity tests
use) — it is imported, never copied, so record ids cannot diverge.
"""

from __future__ import annotations

import argparse
import html
import importlib.util
import json
import os
import re
import sys
import urllib.request
from datetime import datetime, timedelta, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SEED_DIR = os.path.join(ROOT, "seed")
POOL_PATH = os.path.join(SEED_DIR, "pool.json")
CONFIGS_PATH = os.path.join(SEED_DIR, "configs.json")
SOURCES_PATH = os.path.join(SEED_DIR, "sources.json")

POOL_TTL_DAYS = 14
POOL_CAP = 200
SEED_CAP = 60
MIN_SEED = 10

LINK_RE = re.compile(r"(?:vless|vmess|trojan|ss)://[^\s\"'<>]+")
UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Safari/537.36"


def iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_iso(text: str) -> datetime:
    return datetime.fromisoformat(text.replace("Z", "+00:00"))


def load_parser():
    parser_dir = os.environ.get("AUTOCONNECT_PARSER_DIR") or os.path.join(
        os.path.dirname(ROOT), "autoconnect-core", "parser"
    )
    path = os.path.join(parser_dir, "parser.py")
    if not os.path.isfile(path):
        raise SystemExit(f"canonical parser not found at {path}")
    spec = importlib.util.spec_from_file_location("ac_parser", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def fetch_text(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=40) as res:
        return res.read().decode("utf-8", "replace")


def extract_links(page_html: str) -> list:
    """Share links in page order (oldest post first), returned newest first."""
    links = [html.unescape(m).rstrip(".,)") for m in LINK_RE.findall(page_html)]
    links.reverse()
    return links


def load_pool(now_iso: str) -> dict:
    if os.path.isfile(POOL_PATH):
        with open(POOL_PATH, encoding="utf-8") as fh:
            return json.load(fh)
    # Bootstrap: seed the pool from the currently published seed so the first
    # automated run can only grow coverage, never shrink it.
    pool = {"version": 1, "entries": {}}
    if os.path.isfile(CONFIGS_PATH):
        with open(CONFIGS_PATH, encoding="utf-8") as fh:
            seed = json.load(fh)
        for rec in seed.get("configs", []):
            pool["entries"][rec["id"]] = {
                "record": rec,
                "first_seen": rec.get("first_seen") or now_iso,
                "last_seen": now_iso,
                "sources": ["bootstrap"],
            }
    return pool


def write_json(path: str, payload) -> None:
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        fh.write(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
    os.replace(tmp, path)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--dry-run", action="store_true", help="compute and report, write nothing")
    args = ap.parse_args(argv)

    now = datetime.now(timezone.utc)
    now_iso = iso(now)
    parser = load_parser()

    with open(SOURCES_PATH, encoding="utf-8") as fh:
        sources = json.load(fh)["sources"]

    fresh = {}          # config id -> record (this run, all sources merged)
    per_source = {}     # source id -> {"links": n, "unique": n, "ok": bool}
    latest_links = {}   # source id -> [links newest first]
    for src in sources:
        sid = src["id"]
        try:
            page = fetch_text(src["url"])
        except Exception as exc:  # one dead source must not kill the run
            print(f"source {sid}: FETCH FAILED ({exc})", file=sys.stderr)
            per_source[sid] = {"links": 0, "unique": 0, "ok": False}
            continue
        links = extract_links(page)
        latest_links[sid] = links
        result = parser.parse_links(links)
        records = parser.dedupe_records(result["records"])
        per_source[sid] = {"links": len(links), "unique": len(records), "ok": True}
        for rec in records:
            rec.setdefault("operator_scores", {})
            slot = fresh.setdefault(rec["id"], rec)
            slot["_sources"] = sorted(set(slot.get("_sources", [])) | {sid})

    if not fresh:
        print("GUARD: zero unique configs parsed across all sources; aborting, nothing written",
              file=sys.stderr)
        return 2

    pool = load_pool(now_iso)
    entries = pool["entries"]
    for cid, rec in fresh.items():
        srcs = rec.pop("_sources", [])
        if cid in entries:
            entry = entries[cid]
            rec["first_seen"] = entry["first_seen"]
            entry["record"] = rec
            entry["last_seen"] = now_iso
            entry["sources"] = sorted(set(entry.get("sources", [])) | set(srcs))
        else:
            rec["first_seen"] = now_iso
            entries[cid] = {"record": rec, "first_seen": now_iso,
                            "last_seen": now_iso, "sources": srcs}

    cutoff = now - timedelta(days=POOL_TTL_DAYS)
    pruned = [cid for cid, e in entries.items() if parse_iso(e["last_seen"]) < cutoff]
    for cid in pruned:
        del entries[cid]

    ordered = sorted(entries.values(), key=lambda e: e["last_seen"], reverse=True)
    if len(ordered) > POOL_CAP:
        keep = {e["record"]["id"] for e in ordered[:POOL_CAP]}
        pool["entries"] = entries = {cid: e for cid, e in entries.items() if cid in keep}
        ordered = ordered[:POOL_CAP]

    seed_configs = [e["record"] for e in ordered[:SEED_CAP]]
    if len(seed_configs) < MIN_SEED:
        print(f"GUARD: seed would hold only {len(seed_configs)} configs (< {MIN_SEED}); "
              "aborting, nothing written", file=sys.stderr)
        return 3

    print(f"collector: sources={json.dumps(per_source)} fresh_unique={len(fresh)} "
          f"pool={len(entries)} (pruned {len(pruned)} stale) seed={len(seed_configs)}")

    if args.dry_run:
        print("dry-run: nothing written")
        return 0

    write_json(POOL_PATH, pool)
    write_json(CONFIGS_PATH, {"sample_only": False, "configs": seed_configs})
    for sid, links in latest_links.items():
        with open(os.path.join(SEED_DIR, "sources", f"{sid}-latest.txt"), "w", encoding="utf-8") as fh:
            fh.write("\n".join(links) + "\n")
    print("wrote seed/pool.json, seed/configs.json, seed/sources/*-latest.txt")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
