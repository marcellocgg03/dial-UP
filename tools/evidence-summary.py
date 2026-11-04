#!/usr/bin/env python3
"""Summarise the spike's JSONL evidence log (files/evidence/dialup-events.jsonl and rotated copies).

Run as:  python3 -I tools/evidence-summary.py FILE... [--since ISO] [--until ISO] [--src IP] [--json]
         python3 -I tools/evidence-summary.py --self-test

The input is untrusted LAN text (logged datagrams and request bodies), so every line goes through
json.loads only (no eval), unparsable lines are skipped and counted, control characters are escaped
before printing, and pairing codes are redacted so they never reach a committed results file.

Schema contract (written by the spike service, plans 01-03, 01-04, 01-06): one JSON object per line with
ts (ISO-8601 with offset), mono and kind first. Kinds used here: ssdp-rx, ssdp-tx, ssdp-bind, http-req,
heartbeat, net, lock, variant, exit-info (cmd and cmd-done are only counted). Unknown kinds and unknown
fields are kept and counted, never an error.

--src IP restricts the sender-bearing kinds (ssdp-rx and http-req by src, ssdp-tx by dest). Kinds that
have no sender (heartbeat, net, lock, variant, ssdp-bind, exit-info) are not filtered by --src.
--since/--until restrict every section. A timestamp without an offset is read as UTC.
"""
import argparse
import contextlib
import io
import json
import os
import re
import sys
import tempfile
from collections import Counter
from datetime import datetime, timezone

HEARTBEAT_GAP_SECONDS = 90
TIMELINE_KINDS = ("net", "lock", "variant", "ssdp-bind", "exit-info")
KNOWN_KINDS = ("ssdp-rx", "ssdp-tx", "ssdp-bind", "http-req", "heartbeat", "net", "lock", "variant",
               "exit-info", "cmd", "cmd-done")

_PAIRING_FORM = re.compile(r"(?i)(pairing[_-]?code)(=)(?!<redacted len )([^&\s\"']*)")
_PAIRING_JSON = re.compile(r"(?i)(\"pairing[_-]?code\"\s*:\s*\")(?!<redacted len )([^\"]*)(\")")
_CONTROL = re.compile(r"[\x00-\x08\x0b-\x1f\x7f]")


def redact(text):
    """Replace pairing code values with '<redacted len N>' (theme and other keys are kept)."""
    def form(m):
        value = m.group(3)
        if value.startswith("<redacted len"):
            return m.group(0)
        return "%s=<redacted len %d>" % (m.group(1), len(value))

    def js(m):
        value = m.group(2)
        if value.startswith("<redacted len"):
            return m.group(0)
        return "%s<redacted len %d>%s" % (m.group(1), len(value), m.group(3))

    return _PAIRING_JSON.sub(js, _PAIRING_FORM.sub(form, text))


def escape_controls(text):
    return _CONTROL.sub(lambda m: "\\x%02x" % ord(m.group(0)), text)


def scrub(value):
    """Recursively redact and escape every string in a report. Call once, on the finished report."""
    if isinstance(value, str):
        return escape_controls(redact(value))
    if isinstance(value, list):
        return [scrub(v) for v in value]
    if isinstance(value, dict):
        return {scrub(str(k)): scrub(v) for k, v in value.items()}
    return value


def parse_ts(text):
    """ISO-8601 to an aware datetime, or None. A missing offset means UTC."""
    if not isinstance(text, str) or not text:
        return None
    try:
        t = text.strip()
        if t.endswith("Z") or t.endswith("z"):
            t = t[:-1] + "+00:00"
        dt = datetime.fromisoformat(t)
    except ValueError:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt


def ip_of(endpoint):
    """'ip:port' (or '[v6]:port', or a bare ip) to the ip part."""
    if not isinstance(endpoint, str):
        return ""
    e = endpoint.strip()
    if e.startswith("["):
        return e[1:].split("]", 1)[0]
    if e.count(":") == 1:
        return e.split(":", 1)[0]
    return e


def read_events(paths):
    """Return (events, stats). Each event is (dt_or_None, dict). Never raises on bad lines."""
    events = []
    stats = {"files": 0, "lines": 0, "unparsable": 0, "unreadable_files": []}
    for path in paths:
        try:
            fh = open(path, "r", encoding="utf-8", errors="replace")
        except OSError as exc:
            stats["unreadable_files"].append("%s: %s" % (path, exc.strerror or exc))
            continue
        stats["files"] += 1
        with fh:
            for raw in fh:
                line = raw.strip()
                if not line:
                    continue
                stats["lines"] += 1
                try:
                    obj = json.loads(line)
                except (ValueError, RecursionError):
                    stats["unparsable"] += 1
                    continue
                if not isinstance(obj, dict) or not isinstance(obj.get("kind"), str):
                    stats["unparsable"] += 1
                    continue
                events.append((parse_ts(obj.get("ts")), obj))
    return events, stats


def _label(ev):
    if ev.get("server") == "media":
        return "media"
    return str(ev.get("device") or ev.get("server") or "?")


def _count_rows(counter, names):
    rows = []
    for key, n in sorted(counter.items(), key=lambda kv: (-kv[1], [str(x) for x in kv[0]])):
        row = dict(zip(names, key))
        row["count"] = n
        rows.append(row)
    return rows


def analyze(events, stats, since=None, until=None, src=None):
    """Build the report dict (unscrubbed; callers pass it through scrub())."""
    selected = []
    for dt, ev in events:
        if since is not None or until is not None:
            if dt is None:
                continue
            if since is not None and dt < since:
                continue
            if until is not None and dt > until:
                continue
        kind = ev["kind"]
        if src:
            if kind in ("ssdp-rx", "http-req") and ip_of(ev.get("src")) != src:
                continue
            if kind == "ssdp-tx" and ip_of(ev.get("dest")) != src:
                continue
        selected.append((dt, ev))
    # time order; events without a usable timestamp go last in file order
    epoch = datetime.max.replace(tzinfo=timezone.utc)
    selected.sort(key=lambda p: p[0] or epoch)

    kinds = Counter(ev["kind"] for _, ev in selected)
    rx = Counter()
    tx = Counter()
    http = []
    launches = []
    beats = []
    timeline = []
    for dt, ev in selected:
        kind = ev["kind"]
        ts = ev.get("ts", "")
        if kind == "ssdp-rx":
            rx[(ip_of(ev.get("src")), str(ev.get("sock", "")), bool(ev.get("self")),
                str(ev.get("ST", "")), str(ev.get("USER-AGENT", "")))] += 1
        elif kind == "ssdp-tx":
            tx[(ip_of(ev.get("dest")), str(ev.get("device", "")))] += 1
        elif kind == "http-req":
            row = {"ts": ts, "server": _label(ev), "method": str(ev.get("method", "")),
                   "uri": str(ev.get("uri", "")), "src": str(ev.get("src", "")),
                   "status": ev.get("status"), "ua": str(ev.get("ua", "")),
                   "httpVersion": str(ev.get("httpVersion", "")), "range": str(ev.get("range", ""))}
            http.append(row)
            if row["method"].upper() == "POST" and "/apps/YouTube" in row["uri"]:
                launches.append({"ts": ts, "server": row["server"], "uri": row["uri"], "src": row["src"],
                                 "status": row["status"], "ua": row["ua"],
                                 "body": str(ev.get("body", ""))[:400]})
        elif kind == "heartbeat":
            beats.append((dt, ev))
        if kind in TIMELINE_KINDS:
            detail = {k: v for k, v in ev.items() if k not in ("ts", "mono", "kind")}
            timeline.append({"ts": ts, "kind": kind, "detail": detail})

    gaps = []
    timed = [(dt, ev) for dt, ev in beats if dt is not None]
    for (d1, e1), (d2, e2) in zip(timed, timed[1:]):
        seconds = (d2 - d1).total_seconds()
        if seconds > HEARTBEAT_GAP_SECONDS:
            gaps.append({"from": e1.get("ts", ""), "to": e2.get("ts", ""), "seconds": round(seconds, 1),
                         "before": {"idle": e1.get("idle"), "interactive": e1.get("interactive")},
                         "after": {"idle": e2.get("idle"), "interactive": e2.get("interactive")}})

    return {
        "input": {"files": stats["files"], "lines": stats["lines"], "unparsable_skipped": stats["unparsable"],
                  "unreadable_files": stats["unreadable_files"], "events_in_scope": len(selected),
                  "window": {"since": since.isoformat() if since else None,
                             "until": until.isoformat() if until else None, "src": src}},
        "kinds": dict(sorted(kinds.items())),
        "unknown_kinds": {k: n for k, n in sorted(kinds.items()) if k not in KNOWN_KINDS},
        "ssdp_rx": _count_rows(rx, ("src", "sock", "self", "ST", "user_agent")),
        "ssdp_tx": _count_rows(tx, ("dest", "device")),
        "http_requests": http,
        "launches": launches,
        "heartbeats": {"count": len(beats), "gap_threshold_seconds": HEARTBEAT_GAP_SECONDS, "gaps": gaps},
        "timeline": timeline,
    }


def render_text(report):
    out = []
    inp = report["input"]
    out.append("== input ==")
    out.append("files=%d lines=%d unparsable_skipped=%d events_in_scope=%d" % (
        inp["files"], inp["lines"], inp["unparsable_skipped"], inp["events_in_scope"]))
    w = inp["window"]
    out.append("window since=%s until=%s src=%s" % (w["since"] or "-", w["until"] or "-", w["src"] or "-"))
    for item in inp["unreadable_files"]:
        out.append("unreadable: %s" % item)
    out.append("kinds: " + (", ".join("%s=%d" % kv for kv in report["kinds"].items()) or "-"))
    if report["unknown_kinds"]:
        out.append("unknown kinds kept: " + ", ".join("%s=%d" % kv for kv in report["unknown_kinds"].items()))

    out.append("")
    out.append("== ssdp-rx by src / sock / self / ST / USER-AGENT ==")
    for r in report["ssdp_rx"]:
        out.append("%6d  src=%s sock=%s self=%s ST=%s UA=%s" % (
            r["count"], r["src"] or "-", r["sock"] or "-", str(r["self"]).lower(), r["ST"] or "-",
            r["user_agent"] or "-"))
    if not report["ssdp_rx"]:
        out.append("(none)")

    out.append("")
    out.append("== ssdp-tx by dest / device ==")
    for r in report["ssdp_tx"]:
        out.append("%6d  dest=%s device=%s" % (r["count"], r["dest"] or "-", r["device"] or "-"))
    if not report["ssdp_tx"]:
        out.append("(none)")

    out.append("")
    out.append("== http-req (ts server method uri src status ua httpVersion range) ==")
    for r in report["http_requests"]:
        out.append("%s %s %s %s %s %s %s %s %s" % (
            r["ts"], r["server"], r["method"], r["uri"], r["src"], r["status"] if r["status"] is not None else "-",
            r["ua"] or "-", r["httpVersion"] or "-", r["range"] or "-"))
    if not report["http_requests"]:
        out.append("(none)")

    out.append("")
    out.append("== launches (POST /ytcr/apps/YouTube, pairing code redacted) ==")
    for r in report["launches"]:
        out.append("%s %s %s src=%s status=%s ua=%s body=%s" % (
            r["ts"], r["server"], r["uri"], r["src"], r["status"] if r["status"] is not None else "-",
            r["ua"] or "-", r["body"] or "-"))
    if not report["launches"]:
        out.append("(none)")

    hb = report["heartbeats"]
    out.append("")
    out.append("== heartbeat gaps over %d s (heartbeats=%d) ==" % (hb["gap_threshold_seconds"], hb["count"]))
    for g in hb["gaps"]:
        out.append("gap %.1f s  %s -> %s  before idle=%s interactive=%s  after idle=%s interactive=%s" % (
            g["seconds"], g["from"], g["to"], g["before"]["idle"], g["before"]["interactive"],
            g["after"]["idle"], g["after"]["interactive"]))
    if not hb["gaps"]:
        out.append("(none)")

    out.append("")
    out.append("== net / lock / variant / ssdp-bind / exit-info (time order) ==")
    for e in report["timeline"]:
        detail = " ".join("%s=%s" % (k, json.dumps(v, ensure_ascii=False, separators=(",", ":")))
                          for k, v in e["detail"].items())
        out.append("%s %s %s" % (e["ts"], e["kind"], detail))
    if not report["timeline"]:
        out.append("(none)")
    return "\n".join(out) + "\n"


def run(paths, since=None, until=None, src=None, as_json=False, out=None):
    out = out or sys.stdout
    events, stats = read_events(paths)
    report = scrub(analyze(events, stats, since, until, src))
    if as_json:
        out.write(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    else:
        out.write(render_text(report))
    return 0


def _cli_ts(text):
    dt = parse_ts(text)
    if dt is None:
        raise argparse.ArgumentTypeError("not an ISO-8601 timestamp: %r" % text)
    return dt


# ----------------------------------------------------------------------------------------------- self test

def _line(**kw):
    return json.dumps(kw)


def self_test():
    failures = []

    def check(name, cond, extra=""):
        if not cond:
            failures.append("%s %s" % (name, extra))

    lines = [
        _line(ts="2026-10-19T10:00:00.000-04:00", mono=1, kind="heartbeat", mcast=True, wifi=True, wake=True,
              wifiIp="192.168.50.50", iface="wlan0", idle=False, interactive=True),
        _line(ts="2026-10-19T10:00:05.000-04:00", mono=2, kind="ssdp-rx", sock="1900", src="192.168.50.50:51000",
              self=True, ST="urn:dial-multiscreen-org:service:dial:1", **{"USER-AGENT": "Google Play Services"}),
        _line(ts="2026-10-19T10:00:06.000-04:00", mono=3, kind="ssdp-rx", sock="1900", src="192.168.50.20:40000",
              self=False, ST="ssdp:all", **{"USER-AGENT": "t2u-probe/1.0 (mcast)"}),
        _line(ts="2026-10-19T10:00:06.100-04:00", mono=4, kind="ssdp-tx", dest="192.168.50.20:40000",
              st="ssdp:all", usn="uuid:x", device="T2U-SPIKE A"),
        _line(ts="2026-10-19T10:00:07.000-04:00", mono=5, kind="http-req", server="dial", device="T2U-SPIKE A",
              method="GET", uri="/ytcr/ssdp/device-desc.xml", httpVersion="HTTP/1.1", src="192.168.50.50:52000",
              self=True, status=200, ua="com.google.android.youtube", range=""),
        _line(ts="2026-10-19T10:00:08.000-04:00", mono=6, kind="http-req", server="dial", device="T2U-SPIKE A",
              method="POST", uri="/ytcr/apps/YouTube", httpVersion="HTTP/1.1", src="192.168.50.50:52001",
              self=True, status=201, ua="com.google.android.youtube", range="",
              body="pairingCode=abcd1234efgh&theme=cl"),
        _line(ts="2026-10-19T10:00:09.000-04:00", mono=7, kind="http-req", server="media", method="GET",
              uri="/t/abc.m4a", httpVersion="HTTP/1.1", src="192.168.50.30:1", status=206, ua="Sonos", range="bytes=0-9"),
        _line(ts="2026-10-19T10:05:05.000-04:00", mono=8, kind="heartbeat", mcast=True, wifi=True, wake=True,
              wifiIp="192.168.50.50", iface="wlan0", idle=True, interactive=False),
        _line(ts="2026-10-19T10:05:35.000-04:00", mono=9, kind="heartbeat", mcast=True, wifi=True, wake=True,
              wifiIp="192.168.50.50", iface="wlan0", idle=True, interactive=False),
        _line(ts="2026-10-19T10:06:00.000-04:00", mono=10, kind="net", event="onLost", iface="wlan0"),
        _line(ts="2026-10-19T10:06:01.000-04:00", mono=11, kind="future-kind", extra="kept"),
        "this is not json {",
    ]

    tmp_dir = tempfile.mkdtemp(prefix="t2u-evidence-selftest-")
    path = os.path.join(tmp_dir, "dialup-events.jsonl")
    try:
        with open(path, "w", encoding="utf-8") as fh:
            fh.write("\n".join(lines) + "\n")

        def run_capture(**kw):
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                rc = run([path], out=sys.stdout, **kw)
            return rc, buf.getvalue()

        # 1. redaction in text and JSON output, theme kept, no raw code anywhere
        rc, text = run_capture()
        check("exit code stays 0 with an unparsable line", rc == 0)
        check("redaction format", "pairingCode=<redacted len 12>&theme=cl" in text, text)
        check("no raw pairing code (text)", "abcd1234efgh" not in text)
        rc, js = run_capture(as_json=True)
        check("no raw pairing code (json)", "abcd1234efgh" not in js)
        check("redaction in json", "pairingCode=<redacted len 12>&theme=cl" in json.loads(js)["launches"][0]["body"])
        check("redact idempotent", redact(redact("pairingCode=abc")) == "pairingCode=<redacted len 3>")
        check("redact json form", redact('{"pairingCode":"zz9"}') == '{"pairingCode":"<redacted len 3>"}')

        # 2. heartbeat gap about 300 s with idle/interactive on both sides; 30 s spacing is not a gap
        report = json.loads(js)
        gaps = report["heartbeats"]["gaps"]
        check("exactly one gap", len(gaps) == 1, str(gaps))
        if len(gaps) == 1:
            g = gaps[0]
            check("gap about 300 s", abs(g["seconds"] - 305.0) < 10 and 290 <= g["seconds"] <= 310, str(g))
            check("gap before values", g["before"] == {"idle": False, "interactive": True}, str(g))
            check("gap after values", g["after"] == {"idle": True, "interactive": False}, str(g))
        check("gap text line", "gap 305.0 s" in text, text)

        # 3. unparsable line skipped and counted; unknown kind kept and counted
        check("unparsable counted", report["input"]["unparsable_skipped"] == 1)
        check("unknown kind counted", report["unknown_kinds"].get("future-kind") == 1)

        # 4. window and sender filters restrict every section
        rc, js = run_capture(as_json=True, since=parse_ts("2026-10-19T10:00:07.500-04:00"),
                             until=parse_ts("2026-10-19T10:00:30-04:00"))
        r = json.loads(js)
        check("window http count", [h["method"] for h in r["http_requests"]] == ["POST", "GET"], str(r["http_requests"]))
        check("window drops ssdp", r["ssdp_rx"] == [] and r["ssdp_tx"] == [])
        check("window drops heartbeats", r["heartbeats"]["count"] == 0 and r["timeline"] == [])
        rc, js = run_capture(as_json=True, src="192.168.50.20")
        r = json.loads(js)
        check("src ssdp-rx only laptop", [x["src"] for x in r["ssdp_rx"]] == ["192.168.50.20"], str(r["ssdp_rx"]))
        check("src ssdp-tx dest", [x["dest"] for x in r["ssdp_tx"]] == ["192.168.50.20"])
        check("src drops other http", r["http_requests"] == [])
        rc, js = run_capture(as_json=True, src="192.168.50.50")
        r = json.loads(js)
        check("src phone http", len(r["http_requests"]) == 2 and len(r["launches"]) == 1, str(r["http_requests"]))
        check("media label", all(h["server"] != "media" for h in r["http_requests"]))

        # 5. control characters are escaped
        check("control chars escaped", escape_controls("a\x1b[31mb") == "a\\x1b[31mb")

        # 6. missing file does not crash
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = run([os.path.join(tmp_dir, "missing.jsonl")], out=sys.stdout)
        check("missing file exit 0", rc == 0 and "unreadable" in buf.getvalue())
    finally:
        try:
            os.remove(path)
            os.rmdir(tmp_dir)
        except OSError:
            pass

    if failures:
        for f in failures:
            sys.stderr.write("SELF-TEST FAIL: %s\n" % f)
        return 1
    sys.stdout.write("self-test ok\n")
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description="Summarise dialup-events.jsonl evidence (pairing codes redacted).")
    parser.add_argument("files", nargs="*", help="JSONL evidence files (rotated copies .1 .2 can be passed too)")
    parser.add_argument("--since", type=_cli_ts, help="only events at or after this ISO-8601 time")
    parser.add_argument("--until", type=_cli_ts, help="only events at or before this ISO-8601 time")
    parser.add_argument("--src", help="only sender-bearing events (ssdp-rx, http-req, ssdp-tx) for this IP")
    parser.add_argument("--json", action="store_true", help="print one JSON object instead of text")
    parser.add_argument("--self-test", action="store_true", help="run the built-in checks and exit")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    if not args.files:
        parser.error("no input files (or use --self-test)")
    return run(args.files, args.since, args.until, args.src, args.json)


if __name__ == "__main__":
    sys.exit(main())
