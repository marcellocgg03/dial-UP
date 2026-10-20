#!/usr/bin/env python3
"""Turn a resolve-results.json (from `tools/spike.sh cmd resolve-set`) into a Markdown summary.

Run as `python3 -I tools/resolve-report.py FILE [--min-ok 20]` or `python3 -I tools/resolve-report.py --self-test`.
Standard library only.

Exit codes:
  0  every pass resolved at least --min-ok videos (or, for a smaller pass, had no failure)
  1  a pass with total >= min-ok has ok < min-ok, or a pass with total < min-ok has any failure
  2  the input cannot be read or is not a resolve-results document
"""
import json
import sys

DEFAULT_MIN_OK = 20


def cell(value):
    return "-" if value is None else str(value)


def pass_counts(data):
    """(ok, total) from the records, which are more trustworthy than the stored summary."""
    records = data.get("records")
    if isinstance(records, list):
        ok = sum(1 for r in records if isinstance(r, dict) and r.get("ok"))
        return ok, len(records)
    summary = data.get("summary") or {}
    return int(summary.get("ok") or 0), int(summary.get("total") or 0)


def pass_failed(ok, total, min_ok):
    """True when the pass does not meet the verdict rule."""
    if total >= min_ok:
        return ok < min_ok
    return ok < total  # a short pass (limit=, spot checks) may not have any failure


def evaluate(results, min_ok=DEFAULT_MIN_OK):
    """Returns (exit_code, markdown_lines)."""
    if not isinstance(results, dict) or not isinstance(results.get("passes"), dict):
        return 2, ["not a resolve-results document (no 'passes' object)"]

    lines = []
    label = results.get("label")
    lines.append("## yt-dlp resolve results" + (f" ({label})" if label else ""))
    lines.append("")
    lines.append("| pass | ok/total | cold ms | warm median ms | warm p90 ms | min ms | max ms |")
    lines.append("|---|---|---|---|---|---|---|")

    failed_passes = []
    failures = []
    for name, data in results["passes"].items():
        if not isinstance(data, dict):
            return 2, [f"pass '{name}' is not an object"]
        ok, total = pass_counts(data)
        summary = data.get("summary") or {}
        lines.append(
            "| {} | {}/{} | {} | {} | {} | {} | {} |".format(
                name,
                ok,
                total,
                cell(summary.get("coldMs")),
                cell(summary.get("warmMedianMs")),
                cell(summary.get("warmP90Ms")),
                cell(summary.get("minMs")),
                cell(summary.get("maxMs")),
            )
        )
        if pass_failed(ok, total, min_ok):
            failed_passes.append((name, ok, total))
        for r in data.get("records") or []:
            if isinstance(r, dict) and not r.get("ok"):
                failures.append((name, r.get("id"), r.get("error")))

    lines.append("")
    lines.append(f"- yt-dlp version before: {cell(results.get('ytdlpVersionBefore'))}")
    lines.append(f"- yt-dlp version after: {cell(results.get('ytdlpVersionAfter'))}")
    lines.append(f"- update: {cell(results.get('updateStatus'))} (channel {cell(results.get('updateChannel'))}, {cell(results.get('updateMs'))} ms)")
    lines.append(f"- JS runtime line: {cell(results.get('jsRuntimeLine'))}")
    lines.append(f"- payload init ms: {cell(results.get('payloadInitMs'))}")
    if "cacheDirEntriesAtStart" in results:
        lines.append(f"- yt-dlp cache files at start: {cell(results.get('cacheDirEntriesAtStart'))}")

    lines.append("")
    if failures:
        lines.append(f"### Failures ({len(failures)})")
        for name, vid, err in failures:
            lines.append(f"- [{name}] {cell(vid)}: {cell(err)}")
    else:
        lines.append("### Failures: none")
    refused = results.get("refused") or []
    if refused:
        lines.append("")
        lines.append(f"### Refused IDs ({len(refused)})")
        for r in refused:
            lines.append(f"- {cell(r.get('id') if isinstance(r, dict) else r)}")

    lines.append("")
    if failed_passes:
        lines.append(f"VERDICT: FAIL (need at least {min_ok} resolved per full pass)")
        for name, ok, total in failed_passes:
            lines.append(f"- pass {name}: {ok}/{total}")
        return 1, lines
    lines.append(f"VERDICT: PASS (every pass meets the at-least-{min_ok} rule)")
    return 0, lines


def _synthetic(ok_count, total):
    records = []
    for i in range(total):
        ok = i < ok_count
        records.append(
            {
                "id": "synthetic%03d" % i,
                "pass": "default",
                "index": i,
                "ok": ok,
                "wallMs": 3000 + i,
                "error": None if ok else "ERROR: synthetic failure",
            }
        )
    return {
        "label": "self-test",
        "ytdlpVersionBefore": "2025.11.12",
        "ytdlpVersionAfter": "2026.08.19",
        "updateStatus": "DONE",
        "updateChannel": "stable",
        "jsRuntimeLine": "[debug] JS runtimes: quickjs 0",
        "payloadInitMs": 1234,
        "passes": {
            "default": {
                "records": records,
                "summary": {"total": total, "ok": ok_count, "fail": total - ok_count, "coldMs": 3000, "warmMedianMs": 3010, "warmP90Ms": 3020, "minMs": 3000, "maxMs": 3023},
            }
        },
        "refused": [],
    }


def self_test():
    good_code, good_lines = evaluate(_synthetic(24, 24))
    bad_code, bad_lines = evaluate(_synthetic(19, 24))
    short_ok_code, _ = evaluate(_synthetic(5, 5))
    short_bad_code, _ = evaluate(_synthetic(4, 5))
    edge_code, _ = evaluate(_synthetic(20, 24))
    junk_code, _ = evaluate({"nothing": True})
    checks = [
        ("24/24 passes", good_code == 0),
        ("19/24 fails", bad_code == 1),
        ("5/5 short pass passes", short_ok_code == 0),
        ("4/5 short pass fails", short_bad_code == 1),
        ("20/24 passes (boundary)", edge_code == 0),
        ("junk input is exit 2", junk_code == 2),
        ("pass table is printed", any(line.startswith("| default | 24/24") for line in good_lines)),
        ("failures are listed", any("synthetic019" in line for line in bad_lines)),
    ]
    bad = [name for name, ok in checks if not ok]
    for name, ok in checks:
        print(("ok   " if ok else "FAIL ") + name)
    return 0 if not bad else 1


def main(argv):
    if "--self-test" in argv:
        return self_test()
    min_ok = DEFAULT_MIN_OK
    path = None
    i = 0
    while i < len(argv):
        arg = argv[i]
        if arg == "--min-ok":
            i += 1
            try:
                min_ok = int(argv[i])
            except (IndexError, ValueError):
                print("--min-ok needs an integer", file=sys.stderr)
                return 2
        elif arg.startswith("--"):
            print(f"unknown option {arg}", file=sys.stderr)
            return 2
        elif path is None:
            path = arg
        else:
            print("only one FILE is accepted", file=sys.stderr)
            return 2
        i += 1
    if path is None:
        print(__doc__, file=sys.stderr)
        return 2
    try:
        with open(path, encoding="utf-8") as handle:
            results = json.load(handle)
    except (OSError, ValueError) as err:
        print(f"cannot read {path}: {err}", file=sys.stderr)
        return 2
    code, lines = evaluate(results, min_ok)
    print("\n".join(lines))
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
