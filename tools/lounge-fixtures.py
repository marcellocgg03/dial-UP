#!/usr/bin/env python3
"""Turn raw lounge captures into committable test fixtures with every credential replaced.

Run as:  python3 -I tools/lounge-fixtures.py from-capture tools/lounge-capture/out/capture.jsonl OUTDIR
         python3 -I tools/lounge-fixtures.py from-evidence EVENTS.jsonl OUTDIR --theme THEME
         python3 -I tools/lounge-fixtures.py check DIR
         python3 -I tools/lounge-fixtures.py --self-test

Input: out/capture.jsonl written by tools/lounge-capture/capture.mjs (record kinds http-req, http-res,
rpc-raw, pairing-code, others ignored), or app evidence lines of kind lounge-raw (plan 02-09).

Redaction: every secret value becomes a stable placeholder R-<kind>-<n> (the same value maps to the same
placeholder in every file of one run). Lounge responses are "<len>\\n<payload>" chunks where len counts
JavaScript string length (UTF-16 code units); each payload is rewritten in place (layout, escapes and
line breaks of untouched text are kept) and re-framed with its recomputed length.

A chunk or payload that cannot be parsed is not copied verbatim: it goes through a text-level scrub
(known secrets, key=value and "key":"value" patterns, long base64url runs) and is reported on stderr.
That is stricter than a verbatim copy and keeps the no-credential-reaches-git promise.

Stdlib only. Input is untrusted text: json.loads only, never eval.
"""
import argparse
import contextlib
import io
import json
import os
import re
import sys
import tempfile
from datetime import datetime, timezone
from urllib.parse import parse_qsl, urlsplit

NODE_COMMIT = "5bd3025cee730d3f5464ae585917af3d90e1dfc6"
SOURCE = "yt-cast-receiver 2.1.0 via tools/lounge-capture"

# Key (URL query, form body or JSON key, case-sensitive) -> placeholder kind.
KIND = {
    "loungeIdToken": "token", "loungeToken": "token",
    "SID": "sid", "gsessionid": "gsession",
    "id": "device", "deviceId": "device", "device_id": "device",
    "screenId": "screen", "screen_id": "screen", "screen_ids": "screen",
    "pairing_code": "pairing", "pairingCode": "pairing",
    "zx": "zx",
    "user": "user", "userAvatarUri": "avatar",
    "obfuscatedGaiaId": "gaia", "ownerObfuscatedGaiaId": "gaia",
    "ctt": "ctt",
}
# Keys whose long base64url-looking values are legitimate (YouTube IDs and opaque params).
EXEMPT = {"listId", "videoIds", "videoId", "params", "node_commit"}
PH_RE = re.compile(r"^R-[a-z]+-\d+$")
B64_RUN = re.compile(r"[A-Za-z0-9_-]{40,}")
REQ_PREFIX = re.compile(r"^req\d+_")
LEN_LINE = re.compile(r"(\d+)\n")
STR_LIT = re.compile(r'"(?:[^"\\]|\\.)*"', re.S)
WS = re.compile(r"\s*")
SCALAR = re.compile(r"[^\s,\]\}]+")
MIN_SECRET_LEN = 6


# ---------------------------------------------------------------- redaction core

class Redactor:
    def __init__(self):
        self.map = {}      # (kind, value) -> placeholder
        self.counts = {}   # kind -> n
        self.secrets = {}  # value -> placeholder, for value-based scrubbing

    def ph(self, kind, value):
        if not isinstance(value, str) or value == "" or PH_RE.match(value):
            return value
        key = (kind, value)
        if key not in self.map:
            n = self.counts.get(kind, 0) + 1
            self.counts[kind] = n
            self.map[key] = "R-%s-%d" % (kind, n)
        p = self.map[key]
        if len(value) >= MIN_SECRET_LEN:
            self.secrets[value] = p
        return p

    def scrub(self, s, key):
        """Replace known secret values and long base64url runs inside a free-form string."""
        if key in EXEMPT:
            return s
        for v in sorted(self.secrets, key=len, reverse=True):
            if v in s:
                s = s.replace(v, self.secrets[v])
        return B64_RUN.sub(lambda m: self.ph("token", m.group(0)), s)

    def string(self, val, key, force):
        """Redact one decoded JSON/query string value found under `key`."""
        if not isinstance(val, str) or val == "":
            return val
        if force:
            return self.ph(force, val)
        base = REQ_PREFIX.sub("", key) if isinstance(key, str) else key
        if base in KIND:
            if base == "screen_ids":
                return ",".join(self.ph("screen", x) for x in val.split(","))
            return self.ph(KIND[base], val)
        t = val.strip()
        if t[:1] in ("{", "["):
            try:
                inner = json.loads(t)
            except ValueError:
                inner = None
            if isinstance(inner, (dict, list)):
                try:
                    return Rewriter(self).run(val)
                except (ValueError, IndexError):
                    pass
        return self.scrub(val, key)

    def text(self, s):
        """Text-level fallback scrub for content that does not parse as JSON."""
        for name, kind in KIND.items():
            s = re.sub(r'("%s"\s*:\s*")([^"]*)(")' % re.escape(name),
                       lambda m, k=kind: m.group(1) + self.ph(k, m.group(2)) + m.group(3), s)
            s = re.sub(r"((?:^|[?&])%s=)([^&\s]*)" % re.escape(name),
                       lambda m, k=kind: m.group(1) + self.ph(k, m.group(2)), s)
        return self.scrub(s, None)


class Rewriter:
    """Layout-preserving JSON walker: copies text through and replaces only redacted string literals."""

    def __init__(self, red):
        self.red = red

    def run(self, text):
        self.s, self.i, self.out = text, 0, []
        self.ws()
        self.value(None, None)
        self.ws()
        if self.i != len(self.s):
            raise ValueError("trailing data")
        return "".join(self.out)

    def ws(self):
        m = WS.match(self.s, self.i)
        self.out.append(m.group(0))
        self.i = m.end()

    def expect(self, ch):
        if self.s[self.i] != ch:
            raise ValueError("expected %r at %d" % (ch, self.i))
        self.out.append(ch)
        self.i += 1

    def value(self, key, force):
        c = self.s[self.i]
        if c == "{":
            self.obj(force)
            return None
        if c == "[":
            self.arr(key, force)
            return None
        if c == '"':
            return self.string(key, force)
        m = SCALAR.match(self.s, self.i)
        if not m:
            raise ValueError("bad token at %d" % self.i)
        self.out.append(m.group(0))
        self.i = m.end()
        return None

    def string(self, key, force):
        m = STR_LIT.match(self.s, self.i)
        if not m:
            raise ValueError("bad string at %d" % self.i)
        raw = m.group(0)
        val = json.loads(raw)
        new = self.red.string(val, key, force)
        self.out.append(raw if new == val else json.dumps(new, ensure_ascii=False))
        self.i = m.end()
        return val

    def obj(self, force):
        self.expect("{")
        self.ws()
        if self.s[self.i] == "}":
            self.expect("}")
            return
        while True:
            self.ws()
            m = STR_LIT.match(self.s, self.i)
            if not m:
                raise ValueError("object key expected at %d" % self.i)
            self.out.append(m.group(0))
            self.i = m.end()
            k = json.loads(m.group(0))
            self.ws()
            self.expect(":")
            self.ws()
            sub = force or KIND.get(REQ_PREFIX.sub("", k))
            self.value(k, sub)
            self.ws()
            if self.s[self.i] == ",":
                self.expect(",")
                continue
            self.expect("}")
            return

    def arr(self, key, force):
        self.expect("[")
        self.ws()
        if self.s[self.i] == "]":
            self.expect("]")
            return
        idx, head = 0, None
        while True:
            self.ws()
            sub = force
            if idx == 1 and sub is None and head == "c":
                sub = "sid"            # ["c", SID, "", 8]
            elif idx == 1 and sub is None and head == "S":
                sub = "gsession"       # ["S", gsessionid]
            got = self.value(key, sub)
            if idx == 0:
                head = got
            idx += 1
            self.ws()
            if self.s[self.i] == ",":
                self.expect(",")
                continue
            self.expect("]")
            return


def rewrite_json(red, text):
    return Rewriter(red).run(text)


# ---------------------------------------------------------------- chunk framing

def u16(s):
    """JavaScript string length: UTF-16 code units."""
    return len(s.encode("utf-16-le", "surrogatepass")) // 2


def utf16_end(text, start, n):
    """Index after n UTF-16 code units of text starting at start, or None if it does not fit exactly."""
    end = start + n
    if end > len(text):
        end = len(text)
    units = u16(text[start:end])
    while units > n and end > start:
        end -= 1
        units -= 2 if ord(text[end]) > 0xFFFF else 1
    return end if units == n else None


def split_chunks(text):
    """Return (payloads, rest): rest is the unparsed tail ('' when the whole text is chunks)."""
    i, res = 0, []
    while i < len(text):
        m = LEN_LINE.match(text, i)
        if not m:
            if text[i] in " \r\n\t":
                i += 1
                continue
            return res, text[i:]
        end = utf16_end(text, m.end(), int(m.group(1)))
        if end is None:
            return res, text[i:]
        res.append(text[m.end():end])
        i = end
    return res, ""


def reframe(red, text, where):
    """Redact every chunk of a chunk-framed text and re-emit with recomputed lengths."""
    payloads, rest = split_chunks(text)
    out = []
    for p in payloads:
        try:
            new = rewrite_json(red, p)
        except (ValueError, IndexError):
            print("lounge-fixtures: %s: payload is not JSON, text-level scrub used" % where, file=sys.stderr)
            new = red.text(p)
        out.append("%d\n%s" % (u16(new), new))
    if rest:
        print("lounge-fixtures: %s: unparseable tail of %d chars, text-level scrub used" % (where, len(rest)),
              file=sys.stderr)
        out.append(red.text(rest))
    return "".join(out)


def message_names(framed):
    names = set()
    for p in split_chunks(framed)[0]:
        try:
            msgs = json.loads(p)
        except ValueError:
            continue
        if isinstance(msgs, list):
            for m in msgs:
                if isinstance(m, list) and len(m) > 1 and isinstance(m[1], list) and m[1] and isinstance(m[1][0], str):
                    names.add(m[1][0])
    return names


# ---------------------------------------------------------------- from-capture

def read_jsonl(path):
    recs, bad = [], 0
    with open(path, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                o = json.loads(line)
            except ValueError:
                bad += 1
                continue
            if isinstance(o, dict):
                recs.append(o)
            else:
                bad += 1
    if bad:
        print("lounge-fixtures: %s: %d unparsable lines skipped" % (path, bad), file=sys.stderr)
    return recs


def theme_of(url):
    for k, v in parse_qsl(urlsplit(url).query, keep_blank_values=True):
        if k == "theme":
            return v
    return ""


def pair_requests(recs):
    """Pair every http-res with its http-req (same URL without query, same theme, in order)."""
    pending, pairs = [], []
    for r in recs:
        if r.get("kind") == "http-req":
            if "RID=rpc" not in str(r.get("url", "")):  # the stream request never gets an http-res
                pending.append(r)
        elif r.get("kind") == "http-res":
            base = r.get("url", "")
            want = r.get("theme")
            hit = None
            for q in pending:
                if str(q.get("url", "")).split("?")[0] != base:
                    continue
                if want is not None and theme_of(q.get("url", "")) != want:
                    continue
                hit = q
                break
            if hit is not None:
                pending.remove(hit)
            pairs.append((hit, r))
    return pairs


def safe_name(s):
    return re.sub(r"[^A-Za-z0-9_-]", "_", s) or "unknown"


def pairs_to_dict(red, pairs):
    out = {}
    for k, v in pairs:
        val = red.string(v, k, None)
        base = REQ_PREFIX.sub("", k)
        if base in KIND:
            val = red.string(v, base, None)
        if k in out:
            if not isinstance(out[k], list):
                out[k] = [out[k]]
            out[k].append(val)
        else:
            out[k] = val
    return out


def write(path, text):
    with open(path, "w", encoding="utf-8", newline="") as fh:
        fh.write(text)


def sanitize_plain_json(red, text, where):
    try:
        return rewrite_json(red, text)
    except (ValueError, IndexError):
        print("lounge-fixtures: %s: not JSON, text-level scrub used" % where, file=sys.stderr)
        return red.text(text)


def build_fixtures(recs, outdir, red):
    files, incoming, outgoing = {}, set(), set()
    pairs = pair_requests(recs)

    def put(name, text):
        files[name] = text

    seen = {"screen": 0, "token": 0}
    handshake_first = True
    for req, res in pairs:
        url = res.get("url", "")
        text = res.get("text") or ""
        theme = res.get("theme") if res.get("theme") is not None else (theme_of(req["url"]) if req else "")
        body = (req or {}).get("body") or ""
        if "generate_screen_id" in url:
            n = seen["screen"]
            seen["screen"] += 1
            put("generate-screen-id.txt" if n == 0 else "generate-screen-id-%d.txt" % (n + 1),
                red.ph("screen", text.strip()) + "\n")
        elif "get_lounge_token_batch" in url:
            n = seen["token"]
            seen["token"] += 1
            put("token-batch.json" if n == 0 else "token-batch-%d.json" % (n + 1),
                sanitize_plain_json(red, text, "token batch"))
        elif url.endswith("/bind") and body.strip() == "count=0":
            framed = reframe(red, text, "handshake %s" % theme)
            incoming |= message_names(framed)
            if handshake_first:
                put("handshake.txt", framed)
                handshake_first = False
            put("handshake-%s.txt" % safe_name(theme), framed)

    # rpc streams, per theme, in arrival order
    streams = {}
    for r in recs:
        if r.get("kind") == "rpc-raw" and isinstance(r.get("data"), str):
            streams.setdefault(r.get("theme") or "", []).append(r["data"])
    for theme, parts in streams.items():
        framed = reframe(red, "".join(parts), "rpc %s" % theme)
        incoming |= message_names(framed)
        put("rpc-%s.txt" % safe_name(theme), framed)

    # outgoing bind POSTs with count >= 1
    out_lines, req_lines = [], []
    for req, res in pairs:
        if not req or not str(res.get("url", "")).endswith("/bind"):
            continue
        form = parse_qsl(req.get("body") or "", keep_blank_values=True)
        fd = dict(form)
        try:
            count = int(fd.get("count", "0"))
        except ValueError:
            count = 0
        if count >= 1:
            fields = pairs_to_dict(red, form)
            for k, v in form:
                if k.endswith("__sc"):
                    outgoing.add(v)
            out_lines.append(json.dumps({
                "theme": theme_of(req["url"]),
                "fields": fields,
                "status": res.get("status"),
                "response": red.string(res.get("text") or "", "response", None),
            }, ensure_ascii=False))
    if out_lines:
        put("outgoing.jsonl", "\n".join(out_lines) + "\n")

    for r in recs:
        if r.get("kind") != "http-req":
            continue
        sp = urlsplit(str(r.get("url", "")))
        entry = {"method": r.get("method", "GET"), "host": sp.netloc, "path": sp.path,
                 "query": pairs_to_dict(red, parse_qsl(sp.query, keep_blank_values=True))}
        body = r.get("body")
        if isinstance(body, str) and body:
            if "=" in body and not body.lstrip().startswith(("{", "[")):
                entry["body"] = pairs_to_dict(red, parse_qsl(body, keep_blank_values=True))
            else:
                entry["body"] = red.string(body, "body", None)
        req_lines.append(json.dumps(entry, ensure_ascii=False))
    put("requests.jsonl", "\n".join(req_lines) + ("\n" if req_lines else ""))

    t0 = min((r["t"] for r in recs if isinstance(r.get("t"), (int, float))), default=0)
    return files, incoming, outgoing, t0


def cmd_from_capture(args):
    recs = read_jsonl(args.capture)
    red = Redactor()
    # Pass 1 learns every secret value; pass 2 (the real one) scrubs them everywhere they appear.
    with contextlib.redirect_stderr(io.StringIO()):
        build_fixtures(recs, None, red)
    files, incoming, outgoing, t0 = build_fixtures(recs, args.outdir, red)
    os.makedirs(args.outdir, exist_ok=True)
    for name, text in files.items():
        write(os.path.join(args.outdir, name), text)
    manifest = {
        "source": SOURCE,
        "node_commit": NODE_COMMIT,
        "captured_at": datetime.fromtimestamp(t0 / 1000, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "files": sorted(list(files) + ["manifest.json"]),
        "incoming_names": sorted(incoming),
        "outgoing_names": sorted(outgoing),
    }
    write(os.path.join(args.outdir, "manifest.json"), json.dumps(manifest, indent=2) + "\n")
    print("wrote %d fixture files to %s" % (len(manifest["files"]), args.outdir))
    return 0


# ---------------------------------------------------------------- from-evidence

def cmd_from_evidence(args):
    recs = [r for r in read_jsonl(args.events)
            if r.get("kind") == "lounge-raw" and r.get("theme", args.theme) == args.theme]
    groups = {}
    for r in recs:
        groups.setdefault(r.get("seq"), []).append(r)
    red = Redactor()
    framed_all, incoming = [], set()
    with contextlib.redirect_stderr(io.StringIO()):
        for pass_no in (1, 2):
            framed_all, incoming = [], set()
            for seq in sorted(groups, key=lambda x: (x is None, x)):
                parts = sorted(groups[seq], key=lambda r: r.get("part", 0))
                total = parts[0].get("parts", len(parts))
                if len(parts) != total or len({p.get("part") for p in parts}) != total:
                    if pass_no == 2:
                        print("seq %s incomplete (%d of %s parts), skipped" % (seq, len(parts), total), file=sys.stderr)
                    continue
                payload = "".join(str(p.get("data", "")) for p in parts)
                try:
                    new = rewrite_json(red, payload)
                except (ValueError, IndexError):
                    new = red.text(payload)
                framed = "%d\n%s" % (u16(new), new)
                incoming |= message_names(framed)
                framed_all.append(framed)
    # Report skips once (the second pass above swallowed stderr to keep both passes quiet).
    for seq in sorted(groups, key=lambda x: (x is None, x)):
        parts = groups[seq]
        total = parts[0].get("parts", len(parts))
        if len(parts) != total or len({p.get("part") for p in parts}) != total:
            print("lounge-fixtures: seq %s incomplete (%d of %s parts), skipped" % (seq, len(parts), total),
                  file=sys.stderr)
    os.makedirs(args.outdir, exist_ok=True)
    name = "rpc-%s-app.txt" % safe_name(args.theme)
    write(os.path.join(args.outdir, name), "".join(framed_all))
    mpath = os.path.join(args.outdir, "manifest.json")
    if os.path.exists(mpath):
        with open(mpath, encoding="utf-8") as fh:
            manifest = json.load(fh)
    else:
        manifest = {"source": "lounge-raw evidence from the Kotlin app", "files": [], "incoming_names": [],
                    "outgoing_names": []}
    manifest["files"] = sorted(set(manifest.get("files", [])) | {name, "manifest.json"})
    manifest["incoming_names"] = sorted(set(manifest.get("incoming_names", [])) | incoming)
    write(mpath, json.dumps(manifest, indent=2) + "\n")
    print("wrote %s (%d chunks)" % (name, len(framed_all)))
    return 0


# ---------------------------------------------------------------- check

def _bad(problems, where, key, val):
    problems.append("%s: key %s holds %r" % (where, key, val if len(str(val)) < 60 else str(val)[:57] + "..."))


def walk_check(obj, key, where, problems, msgs=False):
    base = REQ_PREFIX.sub("", key) if isinstance(key, str) else key
    if isinstance(obj, dict):
        for k, v in obj.items():
            walk_check(v, k, where, problems, msgs)
        return
    if isinstance(obj, list):
        if msgs and len(obj) > 1 and obj[0] in ("c", "S") and isinstance(obj[1], str) and obj[1] and not PH_RE.match(obj[1]):
            _bad(problems, where, "%s message argument" % obj[0], obj[1])
        for v in obj:
            walk_check(v, key, where, problems, msgs)
        return
    if not isinstance(obj, str) or obj == "":
        return
    if base in KIND:
        vals = obj.split(",") if base == "screen_ids" else [obj]
        for v in vals:
            if not PH_RE.match(v):
                _bad(problems, where, key, v)
        return
    t = obj.strip()
    if t[:1] in ("{", "["):
        try:
            inner = json.loads(t)
        except ValueError:
            inner = None
        if isinstance(inner, (dict, list)):
            walk_check(inner, key, where, problems, msgs)
            return
    if key not in EXEMPT and B64_RUN.search(obj):
        _bad(problems, where, key, B64_RUN.search(obj).group(0))


def check_file(path, name, problems):
    with open(path, encoding="utf-8", errors="replace") as fh:
        text = fh.read()
    if name.endswith(".jsonl"):
        for n, line in enumerate(text.splitlines(), 1):
            if line.strip():
                try:
                    walk_check(json.loads(line), None, "%s:%d" % (name, n), problems)
                except ValueError:
                    problems.append("%s:%d: not JSON" % (name, n))
    elif name.endswith(".json"):
        try:
            walk_check(json.loads(text), None, name, problems)
        except ValueError:
            problems.append("%s: not JSON" % name)
    elif name.startswith("generate-screen-id"):
        if not PH_RE.match(text.strip()):
            _bad(problems, name, "screen id", text.strip())
    else:
        payloads, rest = split_chunks(text)
        for n, p in enumerate(payloads, 1):
            try:
                walk_check(json.loads(p), None, "%s chunk %d" % (name, n), problems, msgs=True)
            except ValueError:
                problems.append("%s chunk %d: payload is not JSON" % (name, n))
        if rest:
            problems.append("%s: %d chars are not valid length-prefixed chunks" % (name, len(rest)))


def cmd_check(args):
    problems = []
    names = sorted(os.listdir(args.dir))
    for name in names:
        path = os.path.join(args.dir, name)
        if os.path.isfile(path) and name.endswith((".json", ".jsonl", ".txt")):
            check_file(path, name, problems)
    if problems:
        for p in problems:
            print(p, file=sys.stderr)
        return 1
    print("FIXTURES CLEAN")
    return 0


# ---------------------------------------------------------------- self-test

def frame(payload):
    return "%d\n%s" % (u16(payload), payload)


def self_test():
    S = {
        "token": "TKNsynthetic0123456789abcdefghijklmnopqrstuvwxyz_-AB",
        "sid": "SYNTHSID0123456789ABCDEF",
        "gsession": "SYNTHGSESSIONabcdefghijklmnopqrstuvwxyz0123",
        "screen": "SYNTHSCREENabcdefghijklmnop",
        "device": "synth-device-aaaa-bbbb-cccc",
        "pairing": "123456789012",
        "user": "Synthetic Person Name",
        "avatar": "https://yt3.example.invalid/avatar/SYNTHAVATAR7777",
        "gaia": "1122334455667788990011",
        "ctt": "SYNTHCTTabcdefghijklmnop1234567890",
        "zx": "abcdef012345",
    }
    LIST_ID = "RDCLAK5uy_nSyntheticPlaylistIdThatIsLongEnough1234567"
    title = "Café ♪ \U0001F600 [live]"
    with tempfile.TemporaryDirectory() as tmp:
        cap = os.path.join(tmp, "capture.jsonl")
        bind = "https://www.youtube.com/api/lounge/bc/bind"
        qs = ("CVER=1&RID=45000&VER=8&app=ytcr&device=LOUNGE_SCREEN&id=%(device)s&loungeIdToken=%(token)s"
              "&theme=cl&zx=%(zx)s&deviceInfo=%%7B%%22clientName%%22%%3A%%22TVHTML5%%22%%2C%%22brand%%22%%3A%%22T2U%%22%%7D"
              ) % S
        devices = json.dumps([{"app": "ytcr", "name": "T2U capture", "id": S["device"], "user": S["user"],
                               "userAvatarUri": S["avatar"], "obfuscatedGaiaId": S["gaia"], "type": "REMOTE_CONTROL"}],
                             separators=(",", ":"))
        details = json.dumps({"deviceId": S["device"], "ui": "false"}, separators=(",", ":"))
        init_payload = ('[[0,["c","%s","",8]]\n,[1,["S","%s"]]\n,[2,["loungeStatus",{"devices":%s,"connectionEventDetails":%s}]]\n'
                        ',[3,["getNowPlaying"]]\n]\n') % (S["sid"], S["gsession"], json.dumps(devices), json.dumps(details))
        rpc_payload = ('[[4,["setPlaylist",{"videoId":"dQw4w9WgXcQ","listId":"%s","ctt":"%s","currentIndex":"0",'
                       '"videoIds":"dQw4w9WgXcQ,9bZkp7q19f0","title":%s}]]\n,[5,["pause"]]\n]\n') % (
            LIST_ID, S["ctt"], json.dumps(title, ensure_ascii=False))
        rpc_text = frame(rpc_payload) + frame('[[6,["noteStray","seen on %s today"]]\n]\n' % S["device"])
        half = len(rpc_text) // 2
        recs = [
            {"t": 1760000000000, "kind": "http-req", "method": "GET",
             "url": "https://www.youtube.com/api/lounge/pairing/generate_screen_id"},
            {"t": 1760000000100, "kind": "http-res", "url": "https://www.youtube.com/api/lounge/pairing/generate_screen_id",
             "status": 200, "theme": "", "text": S["screen"]},
            {"t": 1760000000200, "kind": "http-req", "method": "POST",
             "url": "https://www.youtube.com/api/lounge/pairing/get_lounge_token_batch",
             "body": "screen_ids=" + S["screen"]},
            {"t": 1760000000300, "kind": "http-res", "url": "https://www.youtube.com/api/lounge/pairing/get_lounge_token_batch",
             "status": 200, "theme": "",
             "text": json.dumps({"screens": [{"screenId": S["screen"], "refreshIntervalInMillis": 1123200000,
                                             "loungeToken": S["token"], "expiration": 1760999999999}]})},
            {"t": 1760000000400, "kind": "http-req", "method": "POST", "url": bind + "?" + qs, "body": "count=0"},
            {"t": 1760000000500, "kind": "http-res", "url": bind, "status": 200, "theme": "cl", "text": frame(init_payload)},
            {"t": 1760000000600, "kind": "http-req", "method": "GET",
             "url": bind + "?AID=3&RID=rpc&SID=%s&gsessionid=%s&loungeIdToken=%s&theme=cl&TYPE=xmlhttp" % (
                 S["sid"], S["gsession"], S["token"])},
            {"t": 1760000000700, "kind": "rpc-raw", "theme": "cl", "data": rpc_text[:half]},
            {"t": 1760000000800, "kind": "rpc-raw", "theme": "cl", "data": rpc_text[half:]},
            {"t": 1760000000900, "kind": "http-req", "method": "POST",
             "url": bind + "?SID=%s&gsessionid=%s&loungeIdToken=%s&theme=cl&id=%s" % (
                 S["sid"], S["gsession"], S["token"], S["device"]),
             "body": "count=1&ofs=0&req0__sc=onStateChange&req0_state=1&req0_cpn=abc&req0_ctt=%s" % S["ctt"]},
            {"t": 1760000001000, "kind": "http-res", "url": bind, "status": 200, "theme": "cl", "text": ""},
            {"t": 1760000001100, "kind": "http-req", "method": "POST",
             "url": "https://www.youtube.com/api/lounge/pairing/register_pairing_code",
             "body": "access_type=permanent&app=ytcr&pairing_code=%s&screen_id=%s&screen_name=T2U&device_id=%s" % (
                 S["pairing"], S["screen"], S["device"])},
            {"t": 1760000001200, "kind": "pairing-code", "code": S["pairing"]},
        ]
        with open(cap, "w", encoding="utf-8") as fh:
            for r in recs:
                fh.write(json.dumps(r) + "\n")
        out = os.path.join(tmp, "fixtures")
        with contextlib.redirect_stdout(io.StringIO()):
            assert cmd_from_capture(argparse.Namespace(capture=cap, outdir=out)) == 0, "from-capture failed"

        blob = ""
        for name in sorted(os.listdir(out)):
            with open(os.path.join(out, name), encoding="utf-8") as fh:
                blob += fh.read()
        for what, secret in S.items():
            assert secret not in blob, "secret %s survived in the fixtures" % what
        assert LIST_ID in blob, "listId must be left alone"
        assert title in blob, "non-ASCII title lost"
        for name in sorted(os.listdir(out)):
            if name.startswith(("handshake", "rpc-")):
                with open(os.path.join(out, name), encoding="utf-8") as fh:
                    payloads, rest = split_chunks(fh.read())
                assert rest == "" and payloads, "%s: chunk framing broken" % name
        with open(os.path.join(out, "rpc-cl.txt"), encoding="utf-8") as fh:
            rpc_out = fh.read()
        assert "\n,[5,[\"pause\"]]\n]\n" in rpc_out, "multi-line layout not preserved"
        with open(os.path.join(out, "handshake.txt"), encoding="utf-8") as fh:
            hs = fh.read()
        assert '"c","R-sid-' in hs.replace("\\", ""), "SID not replaced in c message"
        with open(os.path.join(out, "requests.jsonl"), encoding="utf-8") as fh:
            req_text = fh.read()
        sid_ph = re.search(r'"R-sid-\d+"', hs).group(0).strip('"')
        assert sid_ph in req_text, "SID placeholder is not stable across files"
        with open(os.path.join(out, "manifest.json"), encoding="utf-8") as fh:
            man = json.load(fh)
        assert {"getNowPlaying", "setPlaylist", "pause", "c", "S", "loungeStatus"} <= set(man["incoming_names"])
        assert "onStateChange" in man["outgoing_names"] and man["source"] == SOURCE

        with contextlib.redirect_stdout(io.StringIO()) as cap_out:
            assert cmd_check(argparse.Namespace(dir=out)) == 0, "check rejected clean fixtures"
        assert "FIXTURES CLEAN" in cap_out.getvalue()

        # from-evidence: parts out of order, one incomplete seq, redaction applied again
        ev = os.path.join(tmp, "events.jsonl")
        pay = '[[7,["setVolume",{"volume":"20","muted":"false","ctt":"%s"}]]\n]\n' % S["ctt"]
        with open(ev, "w", encoding="utf-8") as fh:
            fh.write(json.dumps({"kind": "lounge-raw", "theme": "m", "seq": 1, "part": 1, "parts": 2, "data": pay[10:]}) + "\n")
            fh.write(json.dumps({"kind": "lounge-raw", "theme": "m", "seq": 1, "part": 0, "parts": 2, "data": pay[:10]}) + "\n")
            fh.write(json.dumps({"kind": "lounge-raw", "theme": "m", "seq": 2, "part": 0, "parts": 3, "data": "x"}) + "\n")
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()) as err:
            assert cmd_from_evidence(argparse.Namespace(events=ev, outdir=out, theme="m")) == 0
        assert "incomplete" in err.getvalue()
        with open(os.path.join(out, "rpc-m-app.txt"), encoding="utf-8") as fh:
            app = fh.read()
        assert S["ctt"] not in app and "setVolume" in app and split_chunks(app)[1] == ""
        with open(os.path.join(out, "manifest.json"), encoding="utf-8") as fh:
            man2 = json.load(fh)
        assert "rpc-m-app.txt" in man2["files"] and "setVolume" in man2["incoming_names"]
        with contextlib.redirect_stdout(io.StringIO()):
            assert cmd_check(argparse.Namespace(dir=out)) == 0, "check rejected after from-evidence"

        # check must fail on a raw secret
        bad = os.path.join(tmp, "bad")
        os.makedirs(bad)
        write(os.path.join(bad, "requests.jsonl"),
              json.dumps({"method": "GET", "path": "/x", "query": {"loungeIdToken": S["token"]}}) + "\n")
        with contextlib.redirect_stderr(io.StringIO()):
            assert cmd_check(argparse.Namespace(dir=bad)) == 1, "check accepted a raw loungeIdToken"
        write(os.path.join(bad, "requests.jsonl"), json.dumps({"query": {"note": S["token"]}}) + "\n")
        with contextlib.redirect_stderr(io.StringIO()):
            assert cmd_check(argparse.Namespace(dir=bad)) == 1, "check accepted a stray long token"
        write(os.path.join(bad, "requests.jsonl"), "")
        write(os.path.join(bad, "rpc-cl.txt"), frame('[[0,["c","%s","",8]]\n]\n' % S["sid"]))
        with contextlib.redirect_stderr(io.StringIO()):
            assert cmd_check(argparse.Namespace(dir=bad)) == 1, "check accepted a raw SID in a c message"
    print("SELF-TEST OK")
    return 0


# ---------------------------------------------------------------- CLI

def main(argv):
    if "--self-test" in argv:
        return self_test()
    ap = argparse.ArgumentParser(prog="lounge-fixtures.py", description=__doc__.split("\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("from-capture", help="sanitize out/capture.jsonl into fixtures")
    p.add_argument("capture")
    p.add_argument("outdir")
    p.set_defaults(fn=cmd_from_capture)
    p = sub.add_parser("from-evidence", help="sanitize lounge-raw app evidence into an rpc-<theme>-app.txt fixture")
    p.add_argument("events")
    p.add_argument("outdir")
    p.add_argument("--theme", required=True)
    p.set_defaults(fn=cmd_from_evidence)
    p = sub.add_parser("check", help="exit 1 if any sensitive value in DIR is not a placeholder")
    p.add_argument("dir")
    p.set_defaults(fn=cmd_check)
    args = ap.parse_args(argv)
    return args.fn(args)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
