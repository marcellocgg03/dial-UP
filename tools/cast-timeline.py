#!/usr/bin/env python3
"""Per-action timeline of the app's JSONL evidence log (plan 02-15: count what a cast really did).

Run as:  python3 -I tools/cast-timeline.py FILE... [--since HH:MM:SS] [--until HH:MM:SS] [--export PATH]
         python3 -I tools/cast-timeline.py --self-test

Prints (a) a TIMELINE with one line per relevant event, (b) an ACTIONS table: a window starts at each
player event of type play, pause, resume, seek, stop, next or previous and ends at the next one; per window
it counts `sent` (renderer messages starting with "Sent"), `deferred` (those with playDeferred true),
`launches` (ffmpeg processes whose launch time, ts minus ms, is in the window, with their -ss values) and
`gets` (media GETs answered 200 or 206, counted at the time the log wrote them, which is when the response
ended). A seek window whose next action is a resume within 1000 ms of its last Sent is flagged
`auto_resume` (the old behaviour: a paused seek was followed by a Play). The last line is
`TIMELINE OK <events> events, <actions> actions` (exit 1 and no OK line when nothing relevant is in range).

--since/--until compare with the wall-clock part (HH:MM:SS) of each event's ts, which is phone local time.
--export PATH writes the events of the range whose kind is in an allowlist as compact JSON lines and prints
`EXPORTED <n> -> <path>`; ssdp and raw lounge kinds are never exported.

The input is untrusted text: json.loads only, unparsable lines are skipped and counted, control
characters are escaped before printing. Standard library only.
"""
import argparse
import json
import os
import re
import sys
import tempfile
from datetime import datetime, timedelta, timezone

ACTION_TYPES = ("play", "pause", "resume", "seek", "stop", "next", "previous")
TIMELINE_KINDS = ("player", "renderer", "ffmpeg", "http-req", "cast-count", "resolve", "playlist")
EXPORT_KINDS = ("player", "renderer", "ffmpeg", "playlist", "cast-count", "resolve", "bridge",
                "service-start", "service-stop", "lounge-app", "http-req")
AUTO_RESUME_MS = 1000

_CONTROL = re.compile(r"[\x00-\x08\x0b-\x1f\x7f]")
_TS = re.compile(r"^(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d):(\d\d)(?:\.(\d{1,6}))?(Z|[+-]\d\d:\d\d)?$")
_CLOCK = re.compile(r"^\d\d:\d\d:\d\d$")


def esc(value):
    return _CONTROL.sub(lambda m: "\\x%02x" % ord(m.group(0)), str(value))


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
    """(events, unparsable, unreadable): events are (datetime, 'HH:MM:SS', dict), oldest first."""
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
                events.append((dt, clock, obj))
    events.sort(key=lambda e: e[0])
    return events, bad, unreadable


def in_range(clock, since, until):
    return (since is None or clock >= since) and (until is None or clock <= until)


def ffmpeg_ss(ev):
    args = ev.get("args")
    if isinstance(args, list) and "-ss" in args:
        i = args.index("-ss")
        if i + 1 < len(args):
            return str(args[i + 1])
    return "0"


def ffmpeg_launch(dt, ev):
    ms = ev.get("ms")
    return dt - timedelta(milliseconds=ms) if isinstance(ms, (int, float)) and ms >= 0 else dt


def is_media_get(ev):
    return ev.get("kind") == "http-req" and ev.get("server") == "media"


def timeline_line(dt, ev):
    kind = ev["kind"]
    stamp = dt.strftime("%H:%M:%S.") + "%03d" % (dt.microsecond // 1000)
    if kind == "player":
        body = "%s%s" % (ev.get("type", "?"), " position=%s" % ev["position"] if "position" in ev else "")
    elif kind == "renderer":
        extra = "".join(" %s=%s" % (k, ev[k]) for k in ("playDeferred", "artistChars", "creatorChars") if k in ev)
        body = "%s%s" % (ev.get("message", ""), extra)
    elif kind == "ffmpeg":
        body = "launch %s -ss %s exit=%s ms=%s" % (
            ffmpeg_launch(dt, ev).strftime("%H:%M:%S.%f")[:-3], ffmpeg_ss(ev), ev.get("exitCode"), ev.get("ms"))
    elif kind == "http-req":
        body = "%s ...%s src=%s status=%s bytes=%s range=%s note=%s" % (
            ev.get("method"), str(ev.get("uri", ""))[-12:], ev.get("src"), ev.get("status"), ev.get("bytes"),
            ev.get("range"), ev.get("note"))
    elif kind == "cast-count":
        body = "casting=%s total=%s" % (ev.get("casting"), ev.get("total"))
    elif kind == "resolve":
        body = "id=%s result=%s ms=%s" % (ev.get("id"), ev.get("result"), ev.get("ms"))
    else:
        body = "%s videoId=%s" % (ev.get("type"), ev.get("videoId"))
    return "%s %-10s %s" % (stamp, kind, esc(body))


def build(events, since=None, until=None):
    """Returns (timeline lines, action rows) for the events in range."""
    rows = [(dt, ev) for dt, clock, ev in events if in_range(clock, since, until)]
    lines = []
    for dt, ev in rows:
        if ev["kind"] in TIMELINE_KINDS and (ev["kind"] != "http-req" or is_media_get(ev)):
            lines.append(timeline_line(dt, ev))
    # Windows are cut by position in the (stable) time order, so events stamped in the same millisecond
    # as the next action stay on the side the log wrote them.
    acts = [i for i, (dt, ev) in enumerate(rows) if ev["kind"] == "player" and ev.get("type") in ACTION_TYPES]
    actions = []
    for n, si in enumerate(acts):
        start, act = rows[si]
        ei = acts[n + 1] if n + 1 < len(acts) else len(rows)
        nxt = rows[ei] if n + 1 < len(acts) else None
        end = nxt[0] if nxt else None
        window = rows[si:ei]

        sent = [(dt, ev) for dt, ev in window
                if ev["kind"] == "renderer" and str(ev.get("message", "")).startswith("Sent")]
        launches = [ffmpeg_ss(ev) for dt, ev in rows if ev["kind"] == "ffmpeg"
                    and ffmpeg_launch(dt, ev) >= start and (end is None or ffmpeg_launch(dt, ev) < end)]
        gets = [1 for dt, ev in window if is_media_get(ev) and ev.get("method") == "GET"
                and ev.get("status") in (200, 206)]
        auto = bool(
            act.get("type") == "seek" and nxt is not None and nxt[1].get("type") == "resume" and sent
            and 0 <= (nxt[0] - sent[-1][0]).total_seconds() * 1000 <= AUTO_RESUME_MS
        )
        actions.append({
            "at": start.strftime("%H:%M:%S.") + "%03d" % (start.microsecond // 1000),
            "type": act.get("type"),
            "position": act.get("position"),
            "sent": len(sent),
            "deferred": sum(1 for _, ev in sent if ev.get("playDeferred") is True),
            "launches": launches,
            "gets": len(gets),
            "auto_resume": auto,
        })
    return lines, actions


def action_line(a):
    pos = " position=%s" % a["position"] if a["position"] is not None else ""
    ss = " (-ss %s)" % ",".join(a["launches"]) if a["launches"] else ""
    return "%s %-8s%s sent=%d deferred=%d launches=%d%s gets=%d%s" % (
        a["at"], a["type"], pos, a["sent"], a["deferred"], len(a["launches"]), ss, a["gets"],
        " auto_resume" if a["auto_resume"] else "")


def export(events, since, until, path):
    kept = [ev for dt, clock, ev in events
            if in_range(clock, since, until) and ev["kind"] in EXPORT_KINDS
            and (ev["kind"] != "http-req" or ev.get("server") == "media")]
    with open(path, "w", encoding="utf-8") as fh:
        for ev in kept:
            fh.write(json.dumps(ev, separators=(",", ":"), ensure_ascii=False) + "\n")
    return len(kept)


def run(paths, since, until, export_path, out):
    events, bad, unreadable = read_events(paths)
    for u in unreadable:
        print("UNREADABLE %s" % esc(u), file=out)
    lines, actions = build(events, since, until)
    print("TIMELINE", file=out)
    for line in lines:
        print(line, file=out)
    print("ACTIONS", file=out)
    for a in actions:
        print(action_line(a), file=out)
    if bad:
        print("skipped %d unparsable lines" % bad, file=out)
    if export_path:
        print("EXPORTED %d -> %s" % (export(events, since, until, export_path), esc(export_path)), file=out)
    if not lines:
        print("nothing relevant in range", file=out)
        return 1
    print("TIMELINE OK %d events, %d actions" % (len(lines), len(actions)), file=out)
    return 0


def self_test():
    def ev(clock, kind, **kw):
        d = {"ts": "2026-10-20T%s-04:00" % clock, "mono": 1, "kind": kind}
        d.update(kw)
        return d

    media = dict(server="media", method="GET", uri="/stream/abcdef0123456789.aac", src="192.168.0.5:1", status=200,
                 bytes=100, range=None, note=None)
    sample = [
        # Deferred paused seek, then a manual Play with one launch at -ss 50.
        ev("10:00:00.000", "player", type="pause"),
        ev("10:00:05.000", "player", type="seek", position=50.0),
        ev("10:00:05.200", "renderer", level="info", message='Sent "T" to renderer as adts', playDeferred=True,
           artistChars=4),
        ev("10:00:10.000", "player", type="resume"),
        ev("10:00:10.900", "ffmpeg", args=["-i", "u", "-ss", "50", "-f", "adts"], exitCode=0, ms=800),
        ev("10:00:11.000", "http-req", **media),
        # Old behaviour: a paused seek followed 1 ms after Sent by a resume.
        ev("10:01:00.000", "player", type="seek", position=123.0),
        ev("10:01:00.200", "renderer", level="info", message='Sent "T" to renderer as adts', playDeferred=False),
        ev("10:01:00.201", "player", type="resume"),
        ev("10:01:00.700", "ffmpeg", args=["-i", "u", "-ss", "123"], exitCode=0, ms=300),
        # Kinds that are never exported.
        ev("10:01:01.000", "ssdp-rx", src="192.168.0.9:1900", raw="NOTIFY"),
        ev("10:01:01.100", "lounge-raw", frame="[[1,[\"x\"]]]"),
    ]
    with tempfile.TemporaryDirectory(prefix="cast-timeline-") as tmp:
        src = os.path.join(tmp, "events.jsonl")
        with open(src, "w", encoding="utf-8") as fh:
            for e in sample:
                fh.write(json.dumps(e) + "\n")
            fh.write("not json\n")
        events, bad, unreadable = read_events([src])
        assert bad == 1 and not unreadable, (bad, unreadable)
        lines, actions = build(events)
        by = {(a["at"], a["type"]): a for a in actions}
        seek1 = by[("10:00:05.000", "seek")]
        assert (seek1["sent"], seek1["deferred"], seek1["launches"], seek1["gets"], seek1["auto_resume"]) == \
            (1, 1, [], 0, False), seek1
        play = by[("10:00:10.000", "resume")]
        assert (play["sent"], play["launches"], play["gets"]) == (0, ["50"], 1), play
        seek2 = by[("10:01:00.000", "seek")]
        assert seek2["auto_resume"] is True and seek2["launches"] == [], seek2
        assert sum(1 for a in actions if a["auto_resume"]) == 1
        assert len(build(events, since="10:01:00", until="10:01:30")[1]) == 2
        out = os.path.join(tmp, "export.jsonl")
        n = export(events, None, None, out)
        with open(out, encoding="utf-8") as fh:
            kinds = [json.loads(l)["kind"] for l in fh]
        assert n == len(kinds) == 10 and "ssdp-rx" not in kinds and "lounge-raw" not in kinds, kinds
        assert "\\x1b" in esc("a\x1bb")
    print("SELF-TEST OK")
    return 0


def main(argv):
    ap = argparse.ArgumentParser(description="Per-action timeline of the app's evidence log")
    ap.add_argument("files", nargs="*")
    ap.add_argument("--since")
    ap.add_argument("--until")
    ap.add_argument("--export")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)
    if args.self_test:
        return self_test()
    for name, value in (("--since", args.since), ("--until", args.until)):
        if value is not None and not _CLOCK.match(value):
            ap.error("%s needs HH:MM:SS" % name)
    if not args.files:
        ap.error("no input files")
    return run(args.files, args.since, args.until, args.export, sys.stdout)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
