#!/usr/bin/env python3
"""Soak verdict of the app's JSONL event log (plan 05-03: how long did the phone sleep, did the playlist advance).

Run as:  python3 -I tools/soak-report.py FILE... [--since T] [--until T] [--min-hours H]
                                          [--min-casting-hours H] [--min-tracks N] [--allow-idle]
                                          [--max-track-gap-s S] [--max-idle-housekeeping-gap-s S]
         python3 -I tools/soak-report.py --self-test

Files are given oldest first (spike-events.jsonl.2 .1 and the current file); events are sorted by ts anyway.
Prints one line per topic, in this order: WINDOW (only with --since/--until), SPAN, SERVICE, RUN, HEARTBEAT, SLEEP,
IDLE, CASTING, TRACKS, NET (plus one IP line per newly seen Wi-Fi address), LOUNGE, RETRY, PROBE, REPAIR,
HOUSEKEEPING, LOCK, UPDATE, EXIT, and a final line:

  SOAK OK <hours> h          exit 0: every check below passed; <hours> is the longest uninterrupted service run
  SOAK FAIL <checks>         exit 1: comma list, in this order, of
                               hours     fewer than 2 heartbeats, or the longest run is under --min-hours (default 0)
                               casting   no heartbeat with casting above 0 (waived by --allow-idle), or the casting
                                         hours are under --min-casting-hours (default 0)
                               tracks    fewer track starts than --min-tracks (default 1, or 0 with --allow-idle)
                               track-gap the largest gap between track starts while casting is above
                                         --max-track-gap-s (default 900)
                               lounge    a lounge-receiver "terminated" event
  SOAK NONE                  exit 1: no heartbeat in range

Hours: the longest uninterrupted service run, from the first to the last heartbeat between two service-start events
(a log that begins mid-run counts from its first heartbeat). SPAN still shows the whole log span. RUN shows the
longest run and the number of runs. CASTING hours is the time covered by consecutive heartbeats of one run, the
earlier one with casting above 0, at most 300 s apart. --allow-idle waives only "some casting happened" and the
default one-track minimum; an explicit --min-tracks or --min-casting-hours still applies. When casting heartbeats
exist but no track start matched, a NOTE line says the detector may not match the renderer's wording (is_track_start
reads the message of a renderer event) and the tracks check fails.

Suggested invocation for the overnight acceptance run (D-17):
  python3 -I tools/soak-report.py spike-events.jsonl* --since 2026-10-29T22:00:00 --until 2026-10-30T08:00:00 \\
      --min-hours 3 --min-casting-hours 3 --min-tracks 2 --max-idle-housekeeping-gap-s 540

HOUSEKEEPING compares the largest gap between SSDP housekeeping runs while the phone was idle (Doze) with
--max-idle-housekeeping-gap-s (default 540, the Doze bound stated by plan 05-11). It is reported as within_bound
for plans 05-12 and 05-13 to judge; it does not change the SOAK verdict. RETRY and PROBE read events of plans
05-04 and 05-02 and show zeros on a log from a build without them. PROBE restarts counts ssdp-probe events whose
restarted field is true or an integer above 0 (the app logs the number of listeners bound). REPAIR counts
ssdp-repair events: responder and client count what was repaired with ok true, failed counts ok false.

--since/--until take a time of day (HH:MM:SS) or a full moment (YYYY-MM-DDTHH:MM:SS, optional fraction, optional Z or
+-HH:MM offset; a space may replace the T). Both bounds must be the same kind.
  time of day   compared with the wall-clock part of each event's ts (phone local time) and matched on every day
                in the log, so use a moment for a log longer than a day. With --since later than --until the window
                wraps midnight (clock >= since or clock <= until).
  moment        a moment without an offset is read in the event's own local time (the phone writes its offset into
                every ts); a moment with an offset is compared as an instant. A --since later than --until is a
                usage error (exit status 2), like any value that is neither form.
When either bound is given the first output line is
  WINDOW since=<v> until=<v> mode=<time-of-day|time-of-day-wrapping|moment> kept=<n> dropped=<m>
and a window that keeps nothing ends in SOAK NONE.

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
CASTING_GAP_S = 300       # consecutive heartbeats further apart than this do not count as casting time

_CONTROL = re.compile(r"[\x00-\x08\x0b-\x1f\x7f]")
_TS = re.compile(r"^(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d):(\d\d)(?:\.(\d{1,6}))?(Z|[+-]\d\d:\d\d)?$")
_CLOCK = re.compile(r"^(\d\d):(\d\d):(\d\d)$")
_MOMENT = re.compile(r"^(\d{4})-(\d\d)-(\d\d)[T ](\d\d):(\d\d):(\d\d)(?:\.(\d{1,6}))?(Z|[+-]\d\d:\d\d)?$")
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


class UsageError(ValueError):
    pass


def parse_bound(text, name):
    """('clock', 'HH:MM:SS') or ('moment', datetime); the datetime is naive when the text carries no offset."""
    m = _CLOCK.match(text)
    if m:
        h, mi, s = (int(g) for g in m.groups())
        if h > 23 or mi > 59 or s > 59:
            raise UsageError("%s: %s is not a valid time of day" % (name, esc(text)))
        return "clock", text
    m = _MOMENT.match(text)
    if not m:
        raise UsageError("%s needs HH:MM:SS or YYYY-MM-DDTHH:MM:SS (optional fraction and Z or +-HH:MM)" % name)
    y, mo, d, h, mi, s, frac, tz = m.groups()
    try:
        if tz is None:
            tzinfo = None
        elif tz == "Z":
            tzinfo = timezone.utc
        else:
            sign = -1 if tz[0] == "-" else 1
            tzinfo = timezone(sign * timedelta(hours=int(tz[1:3]), minutes=int(tz[4:6])))
        micro = int((frac or "0").ljust(6, "0"))
        return "moment", datetime(int(y), int(mo), int(d), int(h), int(mi), int(s), micro, tzinfo=tzinfo)
    except (ValueError, OverflowError):
        raise UsageError("%s: %s is not a valid moment" % (name, esc(text)))


class Window:
    """The --since/--until window: a time of day (matches every day in the log, wraps midnight when since is
    later than until) or a full moment (a naive moment is read in the event's own local time)."""

    def __init__(self, since_text, until_text):
        self.since_text, self.until_text = since_text, until_text
        bounds = [parse_bound(t, n) for n, t in (("--since", since_text), ("--until", until_text)) if t is not None]
        kinds = {kind for kind, _v in bounds}
        if len(kinds) > 1:
            raise UsageError("--since and --until must both be a time of day or both be a full moment")
        self.kind = kinds.pop()
        self.since = parse_bound(since_text, "--since")[1] if since_text is not None else None
        self.until = parse_bound(until_text, "--until")[1] if until_text is not None else None
        if self.kind == "clock":
            wraps = self.since is not None and self.until is not None and self.since > self.until
            self.mode = "time-of-day-wrapping" if wraps else "time-of-day"
        else:
            self.mode = "moment"
            s, u = self.since, self.until
            if s is not None and u is not None and (s.tzinfo is None) == (u.tzinfo is None) and s > u:
                raise UsageError("--since is later than --until")

    @staticmethod
    def _at_or_after(dt, bound):
        return (dt.replace(tzinfo=None) if bound.tzinfo is None else dt) >= bound

    def keeps(self, dt, clock):
        s, u = self.since, self.until
        if self.kind == "clock":
            if s is not None and u is not None and s > u:
                return clock >= s or clock <= u
            return (s is None or clock >= s) and (u is None or clock <= u)
        return (s is None or self._at_or_after(dt, s)) and (u is None or not self._after(dt, u))

    @staticmethod
    def _after(dt, bound):
        return (dt.replace(tzinfo=None) if bound.tzinfo is None else dt) > bound

    def line(self, kept, dropped):
        return "WINDOW since=%s until=%s mode=%s kept=%d dropped=%d" % (
            esc(self.since_text if self.since_text is not None else "-"),
            esc(self.until_text if self.until_text is not None else "-"), self.mode, kept, dropped)


def parse_window(since, until):
    return None if since is None and until is None else Window(since, until)


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
        "casting_hb": 0, "min_total": None, "casting_s": 0.0,
        "longest_run_s": 0.0, "runs": 0,
        "repair_responder": 0, "repair_client": 0, "repair_failed": 0,
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
    run_first = run_last = None   # first and last heartbeat of the current service run
    run_prev = None               # (dt, casting above 0) of the previous heartbeat in this run

    def close_run():
        if run_first is not None:
            r["runs"] += 1
            r["longest_run_s"] = max(r["longest_run_s"], (run_last - run_first).total_seconds())

    for dt, _clock, ts, ev in events:
        kind = ev["kind"]
        if r["first"] is None:
            r["first"] = (dt, ts)
        r["last"] = (dt, ts)
        if kind == "service-start":
            r["starts"] += 1
            close_run()
            run_first = run_last = run_prev = None
            r["reasons"][code(ev["reason"]) if "reason" in ev else "unknown"] += 1
        elif kind == "heartbeat":
            r["hb"] += 1
            c_now = ev.get("casting")
            if run_first is None:
                run_first = dt
            run_last = dt
            if run_prev is not None and run_prev[1]:
                gap_c = (dt - run_prev[0]).total_seconds()
                if 0 <= gap_c <= CASTING_GAP_S:
                    r["casting_s"] += gap_c
            run_prev = (dt, is_int(c_now) and c_now > 0)
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
            restarted = ev.get("restarted")
            if restarted is True or (is_int(restarted) and restarted > 0):
                r["probe_restarts"] += 1
        elif kind == "ssdp-repair":
            ok, what = ev.get("ok"), ev.get("what")
            if ok is True and what == "responder":
                r["repair_responder"] += 1
            elif ok is True and what == "client":
                r["repair_client"] += 1
            elif ok is False:
                r["repair_failed"] += 1
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
    close_run()
    return r


def report(r, min_hours, max_track_gap_s, bound_s, min_casting_hours=0.0, min_tracks=None, allow_idle=False):
    """(lines, exit code) for an analysed log."""
    if r["hb"] == 0:
        return ["SOAK NONE"], 1
    lines = []
    span_hours = (r["last"][0] - r["first"][0]).total_seconds() / 3600.0
    hours = r["longest_run_s"] / 3600.0
    casting_hours = r["casting_s"] / 3600.0
    lines.append("SPAN first=%s last=%s hours=%.2f" % (esc(r["first"][1]), esc(r["last"][1]), span_hours))
    reasons = ",".join("%s:%d" % (k, v) for k, v in sorted(r["reasons"].items())) or "-"
    lines.append("SERVICE starts=%d reasons=%s" % (r["starts"], reasons))
    lines.append("RUN longest_h=%.2f runs=%d" % (hours, r["runs"]))
    lines.append("HEARTBEAT count=%d max_gap_s=%.0f gaps_over_90s=%d" % (r["hb"], r["hb_max_gap"], r["hb_gaps"]))
    if r["elapsed_s"] > 0:
        asleep = (r["elapsed_s"] - r["awake_s"]) / r["elapsed_s"] * 100.0
        lines.append("SLEEP elapsed_s=%.0f awake_s=%.0f asleep_pct=%.1f" % (r["elapsed_s"], r["awake_s"], asleep))
    else:
        lines.append("SLEEP elapsed_s=0 awake_s=0 asleep_pct=-")
    lines.append("IDLE heartbeats=%d windows=%d longest_s=%.0f" % (r["idle_hb"], r["idle_windows"], r["idle_longest"]))
    lines.append("CASTING heartbeats=%d min_total=%s hours=%.2f"
                 % (r["casting_hb"], "-" if r["min_total"] is None else r["min_total"], casting_hours))
    lines.append("TRACKS starts=%d max_gap_s=%.0f" % (r["tracks"], r["track_max_gap"]))
    lines.append("NET events=%d rebinds=%d skipped=%d ips=%d" % (r["net"], r["rebinds"], r["skipped"], len(r["ips"])))
    for ts, address in r["ip_lines"]:
        lines.append("IP %s %s" % (esc(ts), address))
    lines.append("LOUNGE reconnects=%d refreshes=%d ends=%d terminated=%d"
                 % (r["reconnects"], r["refreshes"], r["ends"], r["terminated"]))
    lines.append("RETRY attempts=%d ok=%d" % (r["retry"], r["retry_ok"]))
    lines.append("PROBE ok=%d failed=%d restarts=%d" % (r["probe_ok"], r["probe_failed"], r["probe_restarts"]))
    lines.append("REPAIR responder=%d client=%d failed=%d" % (r["repair_responder"], r["repair_client"], r["repair_failed"]))
    within = "yes" if r["hk_idle_max_gap"] <= bound_s else "no"
    lines.append("HOUSEKEEPING runs=%d max_gap_s=%.0f idle_max_gap_s=%.0f alarm_late_max_s=%.0f bound_s=%s within_bound=%s"
                 % (r["hk_runs"], r["hk_max_gap"], r["hk_idle_max_gap"], r["alarm_late_s"], fmt_num(bound_s), within))
    lines.append("LOCK events=%d acquired=%d released=%d" % (r["lock"], r["acquired"], r["released"]))
    lines.append("UPDATE attempts=%d ok=%d" % (r["upd"], r["upd_ok"]))
    lines.append("EXIT entries=%d reasons=%s" % (r["exits"], ",".join(r["exit_reasons"]) or "-"))
    if r["casting_hb"] > 0 and r["tracks"] == 0:
        lines.append("NOTE casting seen but no track start matched; the detector may not match the renderer's wording")
    failed = []
    if r["hb"] < 2 or hours < min_hours:
        failed.append("hours")
    if (r["casting_hb"] == 0 and not allow_idle) or casting_hours < min_casting_hours:
        failed.append("casting")
    needed = min_tracks if min_tracks is not None else (0 if allow_idle else 1)
    if r["tracks"] < needed:
        failed.append("tracks")
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


def run(paths, since, until, min_hours, max_track_gap_s, bound_s, min_casting_hours, min_tracks, allow_idle, out):
    events, bad, unreadable = read_events(paths)
    window = parse_window(since, until)
    window_line = None
    if window is not None:
        total = len(events)
        events = [e for e in events if window.keeps(e[0], e[1])]
        window_line = window.line(len(events), total - len(events))
    if window_line is not None:
        print(window_line, file=out)
    for u in unreadable:
        print("UNREADABLE %s" % esc(u), file=out)
    lines, rc = report(analyse(events, max_track_gap_s, bound_s), min_hours, max_track_gap_s, bound_s,
                       min_casting_hours, min_tracks, allow_idle)
    # SOAK NONE stands alone; any other verdict is the last line
    if bad:
        print("skipped %d unparsable lines" % bad, file=out)
    for line in lines:
        print(line, file=out)
    return rc


def self_test():
    import io
    import contextlib

    def helpers(base):
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

        return ev, hb, sent

    ev, hb, sent = helpers(datetime(2026, 10, 20, 22, 0, 0, tzinfo=timezone(timedelta(hours=-4))))

    def verdict(rows, *extra, expect_rc, expect_last, since=None, until=None, min_hours=0.0,
                min_casting_hours=0.0, min_tracks=None, allow_idle=False):
        with tempfile.TemporaryDirectory(prefix="soak-report-") as tmp:
            path = os.path.join(tmp, "events.jsonl")
            with open(path, "w", encoding="utf-8") as fh:
                for e in rows:
                    fh.write(json.dumps(e) + "\n")
                for junk in extra:
                    fh.write(junk + "\n")
            buf = io.StringIO()
            rc = run([path], since, until, min_hours, DEFAULT_TRACK_GAP_S, DEFAULT_HOUSEKEEPING_BOUND_S,
                     min_casting_hours, min_tracks, allow_idle, buf)
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
    text = verdict([hb(0, 1_000_000, 100_000), hb(600, 1_600_000, 160_000)], expect_rc=0, expect_last="SOAK OK",
                   allow_idle=True)
    assert "SLEEP elapsed_s=600 awake_s=60 asleep_pct=90.0" in text, text

    # housekeeping 300 s apart is within the bound; a 700 s gap during idle is not, and the verdict is unchanged
    rows = [hb(0, idle=True), hb(2000, idle=True)] + [ev(s, "housekeeping", trigger="timer", ran=True) for s in (10, 310, 610)]
    text = verdict(rows, expect_rc=0, expect_last="SOAK OK", allow_idle=True)
    assert "idle_max_gap_s=300" in text and "within_bound=yes" in text, text
    rows = [hb(0, idle=True), hb(2000, idle=True)] + [ev(s, "housekeeping", trigger="timer", ran=True) for s in (10, 710)]
    text = verdict(rows, expect_rc=0, expect_last="SOAK OK", allow_idle=True)
    assert "idle_max_gap_s=700" in text and "within_bound=no" in text, text

    # a new address prints one IP line; a hostile reason or address never reaches the output
    rows = [hb(0, ip="192.168.0.20"), hb(30, ip="192.168.0.21"), hb(60, ip="192.168.0.21"),
            ev(61, "service-start", reason="http://evil/?token=SECRET"), hb(90, ip="evil\x1b[31mSECRET")]
    text = verdict(rows, expect_rc=0, expect_last="SOAK OK", allow_idle=True)
    assert "NET events=0 rebinds=0 skipped=0 ips=2" in text and text.count("\nIP ") == 3, text
    assert "SECRET" not in text and "evil" not in text and "\x1b" not in text, text
    assert "\\x1b" in esc("a\x1bb")

    # --- window (WR-08): a night across midnight after an early session the same evening
    wev, whb, wsent = helpers(datetime(2026, 10, 28, 21, 5, 0, tzinfo=timezone(timedelta(hours=-4))))
    early = [wev(0, "service-start", reason="boot")] + [whb(i) for i in range(0, 1501, 30)] + [wsent(i) for i in range(10, 1500, 240)]
    night = [wev(1680, "service-start", reason="boot")] + [whb(1680 + i) for i in range(0, 32221, 60)]
    night += [wsent(1690 + i) for i in range(0, 32220, 240)]
    both = early + night

    def kept_dropped(text):
        m = re.search(r"kept=(\d+) dropped=(\d+)", text.splitlines()[0])
        assert m, text
        return int(m.group(1)), int(m.group(2))

    text = verdict(both, expect_rc=0, expect_last="SOAK OK 8.95 h",
                   since="2026-10-28T21:33:00", until="2026-10-29T07:00:00")
    assert text.splitlines()[0].startswith(
        "WINDOW since=2026-10-28T21:33:00 until=2026-10-29T07:00:00 mode=moment kept="), text
    assert kept_dropped(text) == (len(night), len(early)), (kept_dropped(text), len(night), len(early))
    assert "SPAN first=2026-10-28T21:33:00.000-04:00 last=2026-10-29T06:30:00.000-04:00" in text, text
    text = verdict(both, expect_rc=0, expect_last="SOAK OK 8.95 h", since="2026-10-28 21:33:00", until="2026-10-29T07:00:00")
    assert "mode=moment" in text and kept_dropped(text) == (len(night), len(early)), text
    text = verdict(both, expect_rc=0, expect_last="SOAK OK 8.95 h", since="21:33:00", until="07:00:00")
    assert "mode=time-of-day-wrapping" in text.splitlines()[0], text
    assert kept_dropped(text) == (len(night), len(early)), text
    text = verdict(both, expect_rc=0, expect_last="SOAK OK", since="06:00:00", until="07:00:00")
    assert "mode=time-of-day " in text.splitlines()[0] and "SPAN first=2026-10-29T06:00:00.000-04:00 last=2026-10-29T06:30:00.000-04:00" in text, text
    # an explicit offset is an instant: 10:00+00:00 is 06:00 in the phone's -04:00
    a = verdict(both, expect_rc=0, expect_last="SOAK OK", since="2026-10-29T10:00:00+00:00", until="2026-10-29T10:20:00Z")
    b = verdict(both, expect_rc=0, expect_last="SOAK OK", since="2026-10-29T06:00:00", until="2026-10-29T06:20:00")
    assert kept_dropped(a) == kept_dropped(b) and kept_dropped(a)[0] > 0, (a, b)
    # a window that keeps nothing still says what it was and ends in NONE
    text = verdict(both, expect_rc=1, expect_last="SOAK NONE", since="2026-11-01T00:00:00", until="2026-11-02T00:00:00")
    assert text.startswith("WINDOW ") and kept_dropped(text)[0] == 0, text
    # usage errors exit with status 2
    for bad in (["--since", "2026-10-29T07:00:00", "--until", "2026-10-28T21:33:00"],
                ["--since", "25:61:00"], ["--since", "tomorrow"], ["--until", "2026-13-45T00:00:00"],
                ["--since", "21:00:00", "--until", "2026-10-29T07:00:00"]):
        with contextlib.redirect_stderr(io.StringIO()):
            try:
                main(bad + ["/dev/null"])
            except SystemExit as exc:
                assert exc.code == 2, (bad, exc.code)
            else:
                raise AssertionError("no usage error for %r" % bad)

    # --- verdict (WR-09, IN-06): casting and tracks are required, hours are the longest service run
    def cast_log(hours, track_every=240, casting=1, step=30):
        rows = [ev(0, "service-start", reason="boot")]
        rows += [hb(i, casting=casting) for i in range(0, int(hours * 3600) + 1, step)]
        if track_every:
            rows += [sent(i) for i in range(10, int(hours * 3600), track_every)]
        return rows

    # nothing cast: no casting heartbeat and no track start
    rows = cast_log(1, track_every=0, casting=0)
    verdict(rows, expect_rc=1, expect_last="SOAK FAIL casting,tracks")
    verdict(rows, expect_rc=0, expect_last="SOAK OK 1.00 h", allow_idle=True)
    # --allow-idle waives only the defaults: an explicit minimum still applies
    verdict(rows, expect_rc=1, expect_last="SOAK FAIL tracks", allow_idle=True, min_tracks=1)
    verdict(rows, expect_rc=1, expect_last="SOAK FAIL casting", allow_idle=True, min_casting_hours=0.5)
    # casting heartbeats but the detector found no track start
    text = verdict(cast_log(1, track_every=0), expect_rc=1, expect_last="SOAK FAIL tracks")
    assert "NOTE casting seen but no track start matched; the detector may not match the renderer's wording" in text, text
    # casting hours
    verdict(cast_log(1), expect_rc=1, expect_last="SOAK FAIL casting", min_casting_hours=3)
    text = verdict(cast_log(4), expect_rc=0, expect_last="SOAK OK 4.00 h", min_casting_hours=3)
    assert "CASTING heartbeats=481 min_total=- hours=4.00" in text, text
    # track starts
    one = [e for e in cast_log(1, track_every=0)] + [sent(100)]
    verdict(one, expect_rc=1, expect_last="SOAK FAIL tracks", min_tracks=2)
    verdict(one + [sent(200)], expect_rc=0, expect_last="SOAK OK", min_tracks=2)
    # service restarts split the runs: hours are the longest uninterrupted run, SPAN stays the whole log
    def two_runs(restarts):
        rows = []
        edges = [0] + restarts + [25200]
        for n, (a, b) in enumerate(zip(edges, edges[1:])):
            rows.append(ev(a, "service-start", reason="boot"))
            rows += [hb(i) for i in range(a, b if b != 25200 else b + 1, 60)]
        rows += [sent(i) for i in range(10, 25200, 240)]
        return rows
    text = verdict(two_runs([18000]), expect_rc=0, expect_last="SOAK OK 4.98 h", min_hours=3)
    assert "RUN longest_h=4.98 runs=2" in text and "hours=7.00" in text.splitlines()[0], text
    text = verdict(two_runs([9000, 18000]), expect_rc=1, expect_last="SOAK FAIL hours", min_hours=3)
    assert "RUN longest_h=2.48 runs=3" in text and "SPAN" in text and "hours=7.00" in text, text
    # a log that begins mid-run counts from its first heartbeat
    text = verdict([hb(i) for i in range(100, 7301, 60)] + [sent(120)], expect_rc=0, expect_last="SOAK OK 2.00 h")
    assert "RUN longest_h=2.00 runs=1" in text, text
    # casting time: a 600 s gap is not casting, nor is the gap before the first casting heartbeat or across a restart
    text = verdict([hb(0), hb(600)], expect_rc=0, expect_last="SOAK OK", allow_idle=True)
    assert "hours=0.00" in text.split("CASTING")[1].splitlines()[0], text
    text = verdict([hb(0), hb(200), hb(400)], expect_rc=0, expect_last="SOAK OK", allow_idle=True)
    assert "CASTING heartbeats=3 min_total=- hours=0.11" in text, text
    text = verdict([hb(0, casting=0), hb(100), hb(200, casting=0)], expect_rc=0, expect_last="SOAK OK", allow_idle=True)
    assert "hours=0.03" in text.split("CASTING")[1].splitlines()[0], text
    text = verdict([hb(0), hb(100), ev(150, "service-start"), hb(200), hb(300)], expect_rc=0, expect_last="SOAK OK",
                   allow_idle=True)
    assert "hours=0.06" in text.split("CASTING")[1].splitlines()[0], text
    # probe restarts: the app logs the number of listeners bound
    rows = [hb(0), hb(60)] + [ev(10 + n, "ssdp-probe", ok=True, restarted=v) for n, v in enumerate((False, 1, True, 0, 2.5, "x"))]
    text = verdict(rows, expect_rc=0, expect_last="SOAK OK", allow_idle=True)
    assert "PROBE ok=6 failed=0 restarts=2" in text, text
    # repairs
    rows = [hb(0), hb(60), ev(10, "ssdp-repair", what="responder", ok=True, trigger="x"),
            ev(11, "ssdp-repair", what="client", ok=False, trigger="x"), ev(12, "ssdp-repair", what="client", ok="yes")]
    text = verdict(rows, expect_rc=0, expect_last="SOAK OK", allow_idle=True)
    assert "REPAIR responder=1 client=0 failed=1" in text, text
    # the check order is hours, casting, tracks, track-gap, lounge
    verdict(cast_log(0.01, track_every=0, casting=0) + [ev(5, "lounge-receiver", type="terminated")],
            expect_rc=1, expect_last="SOAK FAIL hours,casting,tracks,lounge", min_hours=1)
    rows = [e for e in cast_log(1, track_every=0)] + [sent(100), sent(2100), ev(5, "lounge-receiver", type="terminated")]
    verdict(rows, expect_rc=1, expect_last="SOAK FAIL hours,tracks,track-gap,lounge", min_hours=5, min_tracks=3)
    print("SELF-TEST OK")
    return 0


def main(argv):
    ap = argparse.ArgumentParser(description="Soak verdict of the app's evidence log")
    ap.add_argument("files", nargs="*")
    ap.add_argument("--since")
    ap.add_argument("--until")
    ap.add_argument("--min-hours", type=float, default=0.0)
    ap.add_argument("--min-casting-hours", type=float, default=0.0)
    ap.add_argument("--min-tracks", type=int, default=None)
    ap.add_argument("--allow-idle", action="store_true")
    ap.add_argument("--max-track-gap-s", type=float, default=DEFAULT_TRACK_GAP_S)
    ap.add_argument("--max-idle-housekeeping-gap-s", type=float, default=DEFAULT_HOUSEKEEPING_BOUND_S)
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)
    if args.self_test:
        return self_test()
    try:
        parse_window(args.since, args.until)
    except UsageError as exc:
        ap.error(str(exc))
    if not args.files:
        ap.error("no input files")
    return run(args.files, args.since, args.until, args.min_hours, args.max_track_gap_s,
               args.max_idle_housekeeping_gap_s, args.min_casting_hours, args.min_tracks, args.allow_idle, sys.stdout)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
