#!/usr/bin/env python3
"""Soak verdict of the app's JSONL event log (plan 05-03: how long did the phone sleep, did the playlist advance).

Run as:  python3 -I tools/soak-report.py FILE... [--since HH:MM:SS] [--until HH:MM:SS] [--min-hours H]
                                          [--max-track-gap-s S] [--max-idle-housekeeping-gap-s S]
         python3 -I tools/soak-report.py --self-test

Files are given oldest first (spike-events.jsonl.2 .1 and the current file); events are sorted by ts anyway.
Prints one line per topic, in this order: SPAN, SERVICE, HEARTBEAT, SLEEP, IDLE, CASTING, TRACKS, NET (plus one
IP line per newly seen Wi-Fi address), LOUNGE, RETRY, PROBE, HOUSEKEEPING, LOCK, UPDATE, EXIT, and a final line:

  SOAK OK <hours> h          exit 0: at least 2 heartbeats, hours >= --min-hours (default 0), the largest gap
                             between track starts while casting <= --max-track-gap-s (default 900), and no
                             lounge-receiver "terminated" event
  SOAK FAIL <checks>         exit 1: comma list of hours, track-gap, lounge
  SOAK NONE                  exit 1: no heartbeat in range

HOUSEKEEPING compares the largest gap between SSDP housekeeping runs while the phone was idle (Doze) with
--max-idle-housekeeping-gap-s (default 540, the Doze bound stated by plan 05-11). It is reported as within_bound
for plans 05-12 and 05-13 to judge; it does not change the SOAK verdict. RETRY and PROBE read events of plans
05-04 and 05-02 and show zeros on a log from a build without them.

--since/--until compare with the wall-clock part (HH:MM:SS) of each event's ts, which is phone local time.

The input is untrusted text (some of it comes from LAN peers): json.loads only, unparsable lines are skipped
and counted, control characters are escaped, and only counts, times, short reason codes and dotted-quad
addresses are printed, so a URL or a token in an event never reaches the output. Standard library only.
"""
import argparse
import json
import os
import re
import sys
import tempfile
from collections import Counter
from datetime import datetime, timedelta, timezone

DEFAULT_TRACK_GAP_S = 900
DEFAULT_HOUSEKEEPING_BOUND_S = 540
HEARTBEAT_GAP_S = 90

_CONTROL = re.compile(r"[\x00-\x08\x0b-\x1f\x7f]")
_TS = re.compile(r"^(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d):(\d\d)(?:\.(\d{1,6}))?(Z|[+-]\d\d:\d\d)?$")
_CLOCK = re.compile(r"^\d\d:\d\d:\d\d$")
_QUAD = re.compile(r"^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$")
_CODE = re.compile(r"^[A-Za-z0-9_.:-]{1,40}$")


def esc(value):
    return _CONTROL.sub(lambda m: "\\x%02x" % ord(m.group(0)), str(value))


def code(value):
    """A short reason code from the log, or '?' when it is not made of plain characters."""
    if isinstance(value, bool):
        return "?"
    if isinstance(value, (int, float)):
        return str(value)
    return value if isinstance(value, str) and _CODE.match(value) else "?"


def quad(value):
    if isinstance(value, str):
        m = _QUAD.match(value)
        if m and all(int(g) <= 255 for g in m.groups()):
            return value
    return None


def parse_ts(text):
    """(datetime, 'HH:MM:SS') or (None, None)."""
    m = _TS.match(text) if isinstance(text, str) else None
    if not m:
        return None, None
    y, mo, d, h, mi, s, frac, tz = m.groups()
    micro = int((frac or "0").ljust(6, "0"))
    if tz in (None, "Z"):
        offset = timezone.utc
    else:
        sign = -1 if tz[0] == "-" else 1
        offset = timezone(sign * timedelta(hours=int(tz[1:3]), minutes=int(tz[4:6])))
    try:
        dt = datetime(int(y), int(mo), int(d), int(h), int(mi), int(s), micro, tzinfo=offset)
    except ValueError:
        return None, None
    return dt, "%s:%s:%s" % (h, mi, s)


def read_events(paths):
    """(events, unparsable, unreadable): events are (datetime, 'HH:MM:SS', ts text, dict), oldest first."""
    events = []
    bad = 0
    unreadable = []
    for path in paths:
        try:
            fh = open(path, "r", encoding="utf-8", errors="replace")
        except OSError as exc:
            unreadable.append("%s: %s" % (path, exc.strerror or exc))
            continue
        with fh:
            for raw in fh:
                line = raw.strip()
                if not line:
                    continue
                try:
                    obj = json.loads(line)
                except (ValueError, RecursionError):
                    bad += 1
                    continue
                if not isinstance(obj, dict) or not isinstance(obj.get("kind"), str):
                    bad += 1
                    continue
                dt, clock = parse_ts(obj.get("ts"))
                if dt is None:
                    bad += 1
                    continue
                events.append((dt, clock, obj["ts"], obj))
    events.sort(key=lambda e: e[0])
    return events, bad, unreadable


def in_range(clock, since, until):
    return (since is None or clock >= since) and (until is None or clock <= until)


def is_int(value):
    return isinstance(value, int) and not isinstance(value, bool)


def is_track_start(ev):
    msg = ev.get("message")
    return isinstance(msg, str) and (msg.startswith("Sent ") or "(continued gaplessly)" in msg)


def analyse(events, max_track_gap_s, bound_s):
    """Walks the events once and returns a dict of the numbers every report line needs."""
    r = {
        "first": None, "last": None, "starts": 0, "reasons": Counter(),
        "hb": 0, "hb_prev": None, "hb_max_gap": 0.0, "hb_gaps": 0,
        "elapsed_s": 0.0, "awake_s": 0.0,
        "idle_hb": 0, "idle_windows": 0, "idle_longest": 0.0, "idle_run_start": None, "idle_run_last": None,
        "casting_hb": 0, "min_total": None,
        "tracks": 0, "track_max_gap": 0.0,
        "net": 0, "rebinds": 0, "skipped": 0, "ips": [], "ip_lines": [], "ip_unknown": set(),
        "reconnects": 0, "refreshes": 0, "ends": 0, "terminated": 0,
        "retry": 0, "retry_ok": 0,
        "probe_ok": 0, "probe_failed": 0, "probe_restarts": 0,
        "hk_runs": 0, "hk_max_gap": 0.0, "hk_idle_max_gap": 0.0, "alarm_late_s": 0.0,
        "lock": 0, "acquired": 0, "released": 0,
        "upd": 0, "upd_ok": 0, "exits": 0, "exit_reasons": [],
    }
    prev_hb = None            # (dt, elapsed, uptime) of the previous heartbeat carrying both clocks
    idle_now = False          # the idle flag of the nearest earlier heartbeat
    casting_now = None        # None: not known yet
    min_casting_since = None  # lowest casting count seen since the previous track start
    prev_track = None
    prev_hk = None
    for dt, _clock, ts, ev in events:
        kind = ev["kind"]
        if r["first"] is None:
            r["first"] = (dt, ts)
        r["last"] = (dt, ts)
        if kind == "service-start":
            r["starts"] += 1
            r["reasons"][code(ev["reason"]) if "reason" in ev else "unknown"] += 1
        elif kind == "heartbeat":
            r["hb"] += 1
            if r["hb_prev"] is not None:
                gap = (dt - r["hb_prev"]).total_seconds()
                r["hb_max_gap"] = max(r["hb_max_gap"], gap)
                if gap > HEARTBEAT_GAP_S:
                    r["hb_gaps"] += 1
            r["hb_prev"] = dt
            el, up = ev.get("elapsedRealtimeMs"), ev.get("uptimeMillis")
            if is_int(el) and is_int(up):
                if prev_hb is not None:
                    d_el = (el - prev_hb[1]) / 1000.0
                    d_up = (up - prev_hb[2]) / 1000.0
                    if d_el > 0:
                        r["elapsed_s"] += d_el
                        r["awake_s"] += min(max(d_up, 0.0), d_el)
                prev_hb = (dt, el, up)
            idle = ev.get("idle") is True
            idle_now = idle
            if idle:
                r["idle_hb"] += 1
                if r["idle_run_start"] is None:
                    r["idle_run_start"] = dt
                    r["idle_windows"] += 1
                r["idle_run_last"] = dt
                r["idle_longest"] = max(r["idle_longest"], (dt - r["idle_run_start"]).total_seconds())
            else:
                r["idle_run_start"] = None
            c = ev.get("casting")
            if is_int(c):
                if c > 0:
                    r["casting_hb"] += 1
                casting_now = c
                min_casting_since = c if min_casting_since is None else min(min_casting_since, c)
            ip = ev.get("wifiIp")
            if ip is not None:
                q = quad(ip)
                if q is not None:
                    if q not in r["ips"]:
                        r["ips"].append(q)
                        r["ip_lines"].append((ts, q))
                elif code(ip) not in r["ip_unknown"]:
                    r["ip_unknown"].add(code(ip))
                    r["ip_lines"].append((ts, "?"))
        elif kind == "cast-count":
            c, total = ev.get("casting"), ev.get("total")
            if is_int(c):
                casting_now = c
                min_casting_since = c if min_casting_since is None else min(min_casting_since, c)
                if c > 0 and is_int(total):
                    r["min_total"] = total if r["min_total"] is None else min(r["min_total"], total)
        elif kind == "renderer" and is_track_start(ev):
            r["tracks"] += 1
            if prev_track is not None and (min_casting_since is None or min_casting_since > 0):
                r["track_max_gap"] = max(r["track_max_gap"], (dt - prev_track).total_seconds())
            prev_track = dt
            min_casting_since = casting_now
        elif kind == "net":
            r["net"] += 1
            if ev.get("rebind") is True:
                r["rebinds"] += 1
            if "skipped" in ev:
                r["skipped"] += 1
        elif kind == "lounge-session":
            what = ev.get("what")
            if what == "rpc-reconnect":
                r["reconnects"] += 1
            elif what == "refresh":
                r["refreshes"] += 1
            elif what == "end":
                r["ends"] += 1
        elif kind == "lounge-receiver":
            if ev.get("type") == "terminated":
                r["terminated"] += 1
        elif kind == "retry":
            r["retry"] += 1
            if ev.get("outcome") in ("added", "running"):
                r["retry_ok"] += 1
        elif kind == "ssdp-probe":
            if ev.get("ok") is True:
                r["probe_ok"] += 1
            elif ev.get("ok") is False:
                r["probe_failed"] += 1
            if ev.get("restarted") is True:
                r["probe_restarts"] += 1
        elif kind == "housekeeping":
            if ev.get("ran") is True:
                r["hk_runs"] += 1
                if prev_hk is not None:
                    gap = (dt - prev_hk).total_seconds()
                    r["hk_max_gap"] = max(r["hk_max_gap"], gap)
                    if idle_now:
                        r["hk_idle_max_gap"] = max(r["hk_idle_max_gap"], gap)
                prev_hk = dt
        elif kind == "housekeeping-alarm":
            late = ev.get("lateMs")
            if isinstance(late, (int, float)) and not isinstance(late, bool):
                r["alarm_late_s"] = max(r["alarm_late_s"], late / 1000.0)
        elif kind == "lock":
            r["lock"] += 1
            if ev.get("isHeld") is True:
                r["acquired"] += 1
            elif ev.get("isHeld") is False:
                r["released"] += 1
        elif kind == "ytdlp-update":
            if "ok" in ev:
                r["upd"] += 1
                if ev.get("ok") is True:
                    r["upd_ok"] += 1
        elif kind == "exit-info":
            r["exits"] += 1
            r["exit_reasons"].append(code(ev.get("reason")))
    return r


def report(r, min_hours, max_track_gap_s, bound_s):
    """(lines, exit code) for an analysed log."""
    if r["hb"] == 0:
        return ["SOAK NONE"], 1
    lines = []
    hours = (r["last"][0] - r["first"][0]).total_seconds() / 3600.0
    lines.append("SPAN first=%s last=%s hours=%.2f" % (esc(r["first"][1]), esc(r["last"][1]), hours))
    reasons = ",".join("%s:%d" % (k, v) for k, v in sorted(r["reasons"].items())) or "-"
    lines.append("SERVICE starts=%d reasons=%s" % (r["starts"], reasons))
    lines.append("HEARTBEAT count=%d max_gap_s=%.0f gaps_over_90s=%d" % (r["hb"], r["hb_max_gap"], r["hb_gaps"]))
    if r["elapsed_s"] > 0:
        asleep = (r["elapsed_s"] - r["awake_s"]) / r["elapsed_s"] * 100.0
        lines.append("SLEEP elapsed_s=%.0f awake_s=%.0f asleep_pct=%.1f" % (r["elapsed_s"], r["awake_s"], asleep))
    else:
        lines.append("SLEEP elapsed_s=0 awake_s=0 asleep_pct=-")
    lines.append("IDLE heartbeats=%d windows=%d longest_s=%.0f" % (r["idle_hb"], r["idle_windows"], r["idle_longest"]))
    lines.append("CASTING heartbeats=%d min_total=%s" % (r["casting_hb"], "-" if r["min_total"] is None else r["min_total"]))
    lines.append("TRACKS starts=%d max_gap_s=%.0f" % (r["tracks"], r["track_max_gap"]))
    lines.append("NET events=%d rebinds=%d skipped=%d ips=%d" % (r["net"], r["rebinds"], r["skipped"], len(r["ips"])))
    for ts, address in r["ip_lines"]:
        lines.append("IP %s %s" % (esc(ts), address))
    lines.append("LOUNGE reconnects=%d refreshes=%d ends=%d terminated=%d"
                 % (r["reconnects"], r["refreshes"], r["ends"], r["terminated"]))
    lines.append("RETRY attempts=%d ok=%d" % (r["retry"], r["retry_ok"]))
    lines.append("PROBE ok=%d failed=%d restarts=%d" % (r["probe_ok"], r["probe_failed"], r["probe_restarts"]))
    within = "yes" if r["hk_idle_max_gap"] <= bound_s else "no"
    lines.append("HOUSEKEEPING runs=%d max_gap_s=%.0f idle_max_gap_s=%.0f alarm_late_max_s=%.0f bound_s=%s within_bound=%s"
                 % (r["hk_runs"], r["hk_max_gap"], r["hk_idle_max_gap"], r["alarm_late_s"], fmt_num(bound_s), within))
    lines.append("LOCK events=%d acquired=%d released=%d" % (r["lock"], r["acquired"], r["released"]))
    lines.append("UPDATE attempts=%d ok=%d" % (r["upd"], r["upd_ok"]))
    lines.append("EXIT entries=%d reasons=%s" % (r["exits"], ",".join(r["exit_reasons"]) or "-"))
    failed = []
    if r["hb"] < 2 or hours < min_hours:
        failed.append("hours")
    if r["tracks"] and r["track_max_gap"] > max_track_gap_s:
        failed.append("track-gap")
    if r["terminated"]:
        failed.append("lounge")
    if failed:
        lines.append("SOAK FAIL %s" % ",".join(failed))
        return lines, 1
    lines.append("SOAK OK %.2f h" % hours)
    return lines, 0


def fmt_num(value):
    return "%d" % value if float(value).is_integer() else "%g" % value


def run(paths, since, until, min_hours, max_track_gap_s, bound_s, out):
    events, bad, unreadable = read_events(paths)
    for u in unreadable:
        print("UNREADABLE %s" % esc(u), file=out)
    events = [e for e in events if in_range(e[1], since, until)]
    lines, rc = report(analyse(events, max_track_gap_s, bound_s), min_hours, max_track_gap_s, bound_s)
    # SOAK NONE stands alone; any other verdict is the last line
    if bad:
        print("skipped %d unparsable lines" % bad, file=out)
    for line in lines:
        print(line, file=out)
    return rc


def self_test():
    base = datetime(2026, 10, 20, 22, 0, 0, tzinfo=timezone(timedelta(hours=-4)))

    def ts(sec):
        return (base + timedelta(seconds=sec)).strftime("%Y-%m-%dT%H:%M:%S.000-04:00")

    def ev(sec, kind, **kw):
        d = {"ts": ts(sec), "mono": 1, "kind": kind}
        d.update(kw)
        return d

    def hb(sec, elapsed_ms=None, uptime_ms=None, idle=False, casting=1, ip="192.168.0.20"):
        d = dict(idle=idle, casting=casting, wifiIp=ip)
        if elapsed_ms is not None:
            d.update(elapsedRealtimeMs=elapsed_ms, uptimeMillis=uptime_ms)
        return ev(sec, "heartbeat", **d)

    def sent(sec):
        return ev(sec, "renderer", level="info", message='Sent "T" to renderer as adts')

    def verdict(rows, *extra, expect_rc, expect_last):
        with tempfile.TemporaryDirectory(prefix="soak-report-") as tmp:
            path = os.path.join(tmp, "events.jsonl")
            with open(path, "w", encoding="utf-8") as fh:
                for e in rows:
                    fh.write(json.dumps(e) + "\n")
                for junk in extra:
                    fh.write(junk + "\n")
            import io
            buf = io.StringIO()
            rc = run([path], None, None, 0.0, DEFAULT_TRACK_GAP_S, DEFAULT_HOUSEKEEPING_BOUND_S, buf)
            text = buf.getvalue()
        last = text.strip().splitlines()[-1]
        assert rc == expect_rc and last.startswith(expect_last), (rc, last, text)
        return text

    # a clean two-hour run: heartbeats every 30 s, a track every 4 minutes, cast-count casting 1
    clean = [ev(0, "service-start", reason="boot"), ev(1, "cast-count", casting=1, total=2)]
    for i in range(0, 7201, 30):
        clean.append(hb(i, 1_000_000 + i * 1000, 500_000 + i * 100))
    for i in range(10, 7200, 240):
        clean.append(sent(i))
    text = verdict(clean, "this is not json", expect_rc=0, expect_last="SOAK OK 2.00 h")
    assert "skipped 1 unparsable lines" in text, text
    assert "SERVICE starts=1 reasons=boot:1" in text and "min_total=2" in text, text
    assert "asleep_pct=90.0" in text, text

    # a 20-minute gap between track starts while casting fails the track-gap check
    gap = [e for e in clean if e["kind"] != "renderer"]
    for s in (10, 250, 1450, 1690):
        gap.append(sent(s))
    verdict(gap, expect_rc=1, expect_last="SOAK FAIL track-gap")

    # a lounge-receiver terminated fails the lounge check
    verdict(clean + [ev(60, "lounge-receiver", type="terminated")], expect_rc=1, expect_last="SOAK FAIL lounge")

    # no heartbeat gives NONE
    verdict([ev(0, "service-start"), sent(5)], expect_rc=1, expect_last="SOAK NONE")

    # two heartbeats 600 s apart in elapsed time and 60 s apart in uptime sleep 90 percent
    text = verdict([hb(0, 1_000_000, 100_000), hb(600, 1_600_000, 160_000)], expect_rc=0, expect_last="SOAK OK")
    assert "SLEEP elapsed_s=600 awake_s=60 asleep_pct=90.0" in text, text

    # housekeeping 300 s apart is within the bound; a 700 s gap during idle is not, and the verdict is unchanged
    rows = [hb(0, idle=True), hb(2000, idle=True)] + [ev(s, "housekeeping", trigger="timer", ran=True) for s in (10, 310, 610)]
    text = verdict(rows, expect_rc=0, expect_last="SOAK OK")
    assert "idle_max_gap_s=300" in text and "within_bound=yes" in text, text
    rows = [hb(0, idle=True), hb(2000, idle=True)] + [ev(s, "housekeeping", trigger="timer", ran=True) for s in (10, 710)]
    text = verdict(rows, expect_rc=0, expect_last="SOAK OK")
    assert "idle_max_gap_s=700" in text and "within_bound=no" in text, text

    # a new address prints one IP line; a hostile reason or address never reaches the output
    rows = [hb(0, ip="192.168.0.20"), hb(30, ip="192.168.0.21"), hb(60, ip="192.168.0.21"),
            ev(61, "service-start", reason="http://evil/?token=SECRET"), hb(90, ip="evil\x1b[31mSECRET")]
    text = verdict(rows, expect_rc=0, expect_last="SOAK OK")
    assert "NET events=0 rebinds=0 skipped=0 ips=2" in text and text.count("\nIP ") == 3, text
    assert "SECRET" not in text and "evil" not in text and "\x1b" not in text, text
    assert "\\x1b" in esc("a\x1bb")
    print("SELF-TEST OK")
    return 0


def main(argv):
    ap = argparse.ArgumentParser(description="Soak verdict of the app's evidence log")
    ap.add_argument("files", nargs="*")
    ap.add_argument("--since")
    ap.add_argument("--until")
    ap.add_argument("--min-hours", type=float, default=0.0)
    ap.add_argument("--max-track-gap-s", type=float, default=DEFAULT_TRACK_GAP_S)
    ap.add_argument("--max-idle-housekeeping-gap-s", type=float, default=DEFAULT_HOUSEKEEPING_BOUND_S)
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)
    if args.self_test:
        return self_test()
    for name, value in (("--since", args.since), ("--until", args.until)):
        if value is not None and not _CLOCK.match(value):
            ap.error("%s needs HH:MM:SS" % name)
    if not args.files:
        ap.error("no input files")
    return run(args.files, args.since, args.until, args.min_hours, args.max_track_gap_s,
               args.max_idle_housekeeping_gap_s, sys.stdout)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
