#!/usr/bin/env python3
"""One line per track change of a gapless session, read back from the app's JSONL evidence log (plan 04-09).

Run as:  python3 -I tools/gapless-report.py FILE... [--since HH:MM:SS] [--until HH:MM:SS]
         python3 -I tools/gapless-report.py --self-test

For every renderer event "Renderer continued into the next track" (the speaker played on into the track the app
queued with SetNextAVTransportURI) it prints

  TRANSITION speaker=<name> video=<videoId> via=<field> prepared_lead_s=<s> queued_lead_s=<s> adopted=<yes|no> reload=<yes|no>

  prepared_lead_s  seconds since that video's latest "Prepared next track" on that speaker ("-" when none)
  queued_lead_s    seconds since its latest "Queued the next track on the speaker" ("-" when none)
  adopted          yes when a "... (continued gaplessly)" event for that video follows within 15 s
  reload           yes when a "Sent ..." event for that video follows within 15 s (a Stop or a second
                   SetAVTransportURI for it, which is what gapless must avoid)

For every "Sent ..." load whose video had a "Using prepared track" note just before it prints

  LOADED speaker=<name> video=<videoId> prepared=yes format=<format>     (the normal track-end path)

From filebuild events with event cache-prune it prints one line

  CACHE max_total_bytes=<n> limit_bytes=<n> evictions=<n> max_pinned=<n>      (or "CACHE none")

The last line is `GAPLESS OK <n> transitions` (exit 0) when n >= 1 and every transition has adopted=yes, reload=no and a
positive queued lead; `GAPLESS FAIL <k> of <n> transitions` (exit 1) otherwise; `GAPLESS NONE` (exit 1) when there is no
transition. Only the fields above are printed, each cut down to a safe character set, so a URL, token or title inside
an event can never reach the output.

--since/--until compare with the wall-clock part (HH:MM:SS) of each event's ts, which is phone local time.

The input is untrusted text: json.loads only, unparsable lines are skipped and counted, control characters are
escaped before printing. Standard library only.
"""
import argparse
import json
import os
import re
import sys
import tempfile
from datetime import datetime, timedelta, timezone

WINDOW_S = 15.0
PREPARED = "Prepared next track"
QUEUED = "Queued the next track on the speaker"
CONTINUED = "Renderer continued into the next track"
ADOPTED = "(continued gaplessly)"
USING = "Using prepared track"
SENT = "Sent "

_CONTROL = re.compile(r"[\x00-\x08\x0b-\x1f\x7f]")
_TS = re.compile(r"^(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d):(\d\d)(?:\.(\d{1,6}))?(Z|[+-]\d\d:\d\d)?$")
_CLOCK = re.compile(r"^\d\d:\d\d:\d\d$")
_SAFE = re.compile(r"[^A-Za-z0-9 ._:\-]")
_FORMAT = re.compile(r" to renderer as ([A-Za-z0-9]{1,8}) ")


def esc(value):
    return _CONTROL.sub(lambda m: "\\x%02x" % ord(m.group(0)), str(value))


def safe(value, limit=40):
    """A field for printing: a fixed character set and a length cap, never the raw value."""
    if value is None:
        return "?"
    text = _SAFE.sub("_", esc(value))[:limit].strip()
    return text.replace(" ", "_") if text else "?"


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


def message(ev):
    msg = ev.get("message")
    return msg if isinstance(msg, str) else ""


def renderer_rows(rows):
    return [(dt, ev) for dt, ev in rows if ev.get("kind") == "renderer"]


def latest_before(rows, end_index, predicate):
    """Time of the latest row before end_index that satisfies predicate, or None."""
    for i in range(end_index - 1, -1, -1):
        dt, ev = rows[i]
        if predicate(ev):
            return dt
    return None


def lead(then, now):
    return "-" if then is None else "%.1f" % (now - then).total_seconds()


def analyse(events, since=None, until=None):
    """Returns (transition dicts, loaded dicts, cache dict or None)."""
    rows = [(dt, ev) for dt, clock, ev in events if in_range(clock, since, until)]
    rend = renderer_rows(rows)
    transitions = []
    loaded = []
    for i, (dt, ev) in enumerate(rend):
        msg = message(ev)
        speaker, video = ev.get("speaker"), ev.get("videoId")
        if msg.startswith(CONTINUED):
            def same(e, text):
                return (e.get("speaker") == speaker and e.get("videoId") == video
                        and message(e).startswith(text))

            prepared = latest_before(rend, i, lambda e: same(e, PREPARED))
            queued = latest_before(rend, i, lambda e: same(e, QUEUED))
            after = [(d, e) for d, e in rend[i + 1:] if (d - dt).total_seconds() <= WINDOW_S]
            adopted = any(e.get("speaker") == speaker and e.get("videoId") == video and ADOPTED in message(e)
                          for _, e in after)
            reload_ = any(e.get("speaker") == speaker and e.get("videoId") == video and message(e).startswith(SENT)
                          for _, e in after)
            queued_s = None if queued is None else (dt - queued).total_seconds()
            transitions.append({
                "speaker": speaker, "video": video, "via": ev.get("via"),
                "prepared": lead(prepared, dt), "queued": lead(queued, dt),
                "adopted": adopted, "reload": reload_,
                "ok": adopted and not reload_ and queued_s is not None and queued_s > 0,
            })
        elif msg.startswith(SENT):
            used = any(e.get("speaker") == speaker and e.get("videoId") == video and message(e).startswith(USING)
                       and 0 <= (dt - d).total_seconds() <= WINDOW_S
                       for d, e in rend[:i])
            if used:
                m = _FORMAT.search(msg)
                loaded.append({"speaker": speaker, "video": video, "format": m.group(1) if m else None})
    prunes = [ev for dt, ev in rows if ev.get("kind") == "filebuild" and ev.get("event") == "cache-prune"]
    cache = None
    if prunes:
        def num(ev, key):
            v = ev.get(key)
            return v if isinstance(v, int) and not isinstance(v, bool) else 0

        cache = {
            "max_total": max(num(e, "totalBytes") for e in prunes),
            "limit": max(num(e, "limitBytes") for e in prunes),
            "evictions": sum(num(e, "evicted") for e in prunes),
            "max_pinned": max(num(e, "pinned") for e in prunes),
        }
    return transitions, loaded, cache


def run(paths, since, until, out):
    events, bad, unreadable = read_events(paths)
    for u in unreadable:
        print("UNREADABLE %s" % esc(u), file=out)
    transitions, loaded, cache = analyse(events, since, until)
    for t in transitions:
        print("TRANSITION speaker=%s video=%s via=%s prepared_lead_s=%s queued_lead_s=%s adopted=%s reload=%s" % (
            safe(t["speaker"]), safe(t["video"], 20), safe(t["via"]), t["prepared"], t["queued"],
            "yes" if t["adopted"] else "no", "yes" if t["reload"] else "no"), file=out)
    for l in loaded:
        print("LOADED speaker=%s video=%s prepared=yes format=%s" % (
            safe(l["speaker"]), safe(l["video"], 20), safe(l["format"], 8)), file=out)
    if cache is None:
        print("CACHE none", file=out)
    else:
        print("CACHE max_total_bytes=%d limit_bytes=%d evictions=%d max_pinned=%d" % (
            cache["max_total"], cache["limit"], cache["evictions"], cache["max_pinned"]), file=out)
    if bad:
        print("skipped %d unparsable lines" % bad, file=out)
    n = len(transitions)
    if n == 0:
        print("GAPLESS NONE", file=out)
        return 1
    failed = sum(1 for t in transitions if not t["ok"])
    if failed:
        print("GAPLESS FAIL %d of %d transitions" % (failed, n), file=out)
        return 1
    print("GAPLESS OK %d transitions" % n, file=out)
    return 0


def self_test():
    import io

    def ev(clock, kind="renderer", **kw):
        d = {"ts": "2026-10-22T%s-04:00" % clock, "mono": 1, "kind": kind}
        d.update(kw)
        return d

    def rend(clock, msg, video, speaker="Lamp", **kw):
        return ev(clock, message=msg, videoId=video, speaker=speaker, level="info", **kw)

    clean = [
        rend("10:00:00.000", 'Using prepared track "A"', "vidAAAAAAAA"),
        rend("10:00:00.100", 'Sent "A" to renderer as mp4 (audio/mp4)', "vidAAAAAAAA"),
        rend("10:00:30.000", 'Prepared next track "B" (mp4)', "vidBBBBBBBB", format="mp4", ms=900),
        rend("10:00:30.500", QUEUED, "vidBBBBBBBB", format="mp4"),
        rend("10:03:00.000", CONTINUED, "vidBBBBBBBB", via="TrackURI"),
        rend("10:03:00.200", 'Now playing "B" (continued gaplessly)', "vidBBBBBBBB"),
        ev("10:00:31.000", "filebuild", event="cache-prune", evicted=2, freedBytes=100, totalBytes=15000000,
           limitBytes=16777216, pinned=2),
        ev("10:01:31.000", "filebuild", event="cache-prune", evicted=1, freedBytes=100, totalBytes=16000000,
           limitBytes=16777216, pinned=1),
    ]
    reloaded = clean[:5] + [
        rend("10:03:00.300", 'Sent "B" to renderer as mp4 (audio/mp4)', "vidBBBBBBBB"),
        rend("10:03:00.400", 'Now playing "B" (continued gaplessly)', "vidBBBBBBBB"),
    ]
    none = clean[:4]

    def write(path, evs, extra=None):
        with open(path, "w", encoding="utf-8") as fh:
            for e in evs:
                fh.write(json.dumps(e) + "\n")
            if extra:
                fh.write(extra)

    def report(path, **kw):
        buf = io.StringIO()
        code = run([path], kw.get("since"), kw.get("until"), buf)
        return code, buf.getvalue().splitlines()

    with tempfile.TemporaryDirectory(prefix="gapless-report-") as tmp:
        p = os.path.join(tmp, "clean.jsonl")
        write(p, clean, extra="not json at all\n")
        code, lines = report(p)
        assert code == 0 and lines[-1] == "GAPLESS OK 1 transitions", lines
        assert "skipped 1 unparsable lines" in lines, lines
        assert any(l.startswith("TRANSITION speaker=Lamp video=vidBBBBBBBB via=TrackURI prepared_lead_s=150.0 "
                                "queued_lead_s=149.5 adopted=yes reload=no") for l in lines), lines
        assert "LOADED speaker=Lamp video=vidAAAAAAAA prepared=yes format=mp4" in lines, lines
        assert "CACHE max_total_bytes=16000000 limit_bytes=16777216 evictions=3 max_pinned=2" in lines, lines
        code, lines = report(p, since="10:00:00", until="10:00:40")
        assert code == 1 and lines[-1] == "GAPLESS NONE", lines

        p = os.path.join(tmp, "reload.jsonl")
        write(p, reloaded)
        code, lines = report(p)
        assert code == 1 and lines[-1] == "GAPLESS FAIL 1 of 1 transitions", lines
        assert any("reload=yes" in l for l in lines), lines

        p = os.path.join(tmp, "none.jsonl")
        write(p, none)
        code, lines = report(p)
        assert code == 1 and lines[-1] == "GAPLESS NONE", lines

        # A URL or control characters in a field never reach the output unescaped.
        p = os.path.join(tmp, "hostile.jsonl")
        hostile = [dict(e) for e in clean]
        hostile[4]["via"] = "http://192.168.0.5:8000/stream/tok\x1b[31m"
        write(p, hostile)
        code, lines = report(p)
        assert "/" not in lines[0] and "\x1b" not in lines[0], lines
        assert "\\x1b" in esc("a\x1bb")
    print("SELF-TEST OK")
    return 0


def main(argv):
    ap = argparse.ArgumentParser(description="One line per track change of a gapless session")
    ap.add_argument("files", nargs="*")
    ap.add_argument("--since")
    ap.add_argument("--until")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)
    if args.self_test:
        return self_test()
    for name, value in (("--since", args.since), ("--until", args.until)):
        if value is not None and not _CLOCK.match(value):
            ap.error("%s needs HH:MM:SS" % name)
    if not args.files:
        ap.error("no input files")
    return run(args.files, args.since, args.until, sys.stdout)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
