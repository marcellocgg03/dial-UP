#!/usr/bin/env python3
"""List the deaksa/tube2upnp commits that PARITY.md section (f) does not record yet (PAR-03).

Run as:  python3 -I tools/upstream-commits.py
         python3 -I tools/upstream-commits.py --self-test

Reads PARITY.md: the "Upstream base" row of section (a) and the table under "## (f) Upstream commits".
Asks the GitHub API compare view (no clone) for the commits on upstream main after the newest commit
recorded in (f), or after the upstream base while (f) has no rows. Prints, in upstream order,
    PENDING <sha10> <date> <subject>    one per (f) row whose status is still "pending"
    NEW <sha10> <date> <subject>        one per upstream commit that (f) does not record
and then "BEHIND new=<n> pending=<p>" with exit 1. When there is nothing new and nothing pending it
prints "up to date" and nothing else, exit 0. A GitHub problem (HTTP status, network) is one line on
stderr and exit 3; a usage error is one line on stderr and exit 2.

The script never writes PARITY.md. Standard library only; the GitHub answer is untrusted data, parsed
as JSON and never executed, imported, passed to a shell or written to disk.
"""
import json
import os
import re
import sys
import urllib.error
import urllib.request

OWNER = "deaksa"
REPO = "tube2upnp"
BRANCH = "main"
API_ROOT = "https://api.github.com"
SECTION = "## (f) Upstream commits"
BASE_ITEM = "Upstream base"
PER_PAGE = 100
MAX_PAGES = 10
TIMEOUT_S = 20
USER_AGENT = "dialup-upstream-commits/1"

SHA_RE = re.compile(r"^[0-9a-f]{40}$")
CELL_SPLIT = re.compile(r"(?<!\\)\|")


class RecordError(Exception):
    """PARITY.md does not hold what this script needs (exit 2)."""


class ApiError(Exception):
    """GitHub did not answer as needed (exit 3)."""


def split_cells(line):
    line = line.strip()
    if line.startswith("|"):
        line = line[1:]
    if line.endswith("|") and not line.endswith("\\|"):
        line = line[:-1]
    return [c.strip() for c in CELL_SPLIT.split(line)]


def unquote(cell):
    cell = cell.strip()
    m = re.match(r"^`([^`]*)`", cell)
    return m.group(1) if m else cell


def parse_parity(text):
    """Returns (base_sha, rows); rows are (sha, date, subject, status, reason) in table order."""
    base = None
    rows = []
    in_section = False
    for line in text.splitlines():
        if line.startswith("## "):
            in_section = line.strip() == SECTION
            continue
        if base is None and line.startswith("| " + BASE_ITEM + " |"):
            cells = split_cells(line)
            if len(cells) >= 2:
                base = unquote(cells[1])
            continue
        if not in_section or not line.startswith("|"):
            continue
        cells = split_cells(line)
        if cells[0].lower().startswith("upstream commit") or set(cells[0]) <= set("-: "):
            continue
        if len(cells) != 5:
            raise RecordError("(f) row does not have 5 cells: %s" % line[:120])
        rows.append((unquote(cells[0]), cells[1], cells[2], cells[3], cells[4]))
    if base is None:
        raise RecordError("PARITY.md (a) has no '%s' row" % BASE_ITEM)
    return base, rows


def start_point(base, rows):
    return rows[-1][0] if rows else base


def compare_url(start, page):
    return "%s/repos/%s/%s/compare/%s...%s?per_page=%d&page=%d" % (
        API_ROOT, OWNER, REPO, start, BRANCH, PER_PAGE, page)


def fetch_commits(start, fetch):
    """Commits after `start` on upstream main, oldest first, as (sha, date, subject)."""
    headers = {
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": USER_AGENT,
    }
    commits = []
    total = None
    for page in range(1, MAX_PAGES + 1):
        status, _resp_headers, body = fetch(compare_url(start, page), headers)
        if status != 200:
            raise ApiError("GitHub API answered HTTP %d" % status)
        try:
            data = json.loads(body)
            page_commits = data["commits"]
            total = data.get("total_commits", total)
        except (ValueError, KeyError, TypeError):
            raise ApiError("unexpected API answer")
        if not page_commits:
            break
        for c in page_commits:
            message = c["commit"]["message"]
            commits.append((c["sha"], c["commit"]["committer"]["date"][:10], message.splitlines()[0] if message else ""))
        if isinstance(total, int) and len(commits) >= total:
            break
    return commits


def real_fetch(url, headers):
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_S) as response:
            return response.status, {k.lower(): v for k, v in response.headers.items()}, response.read()
    except urllib.error.HTTPError as e:
        return e.code, {k.lower(): v for k, v in e.headers.items()}, e.read()
    except (urllib.error.URLError, OSError) as e:
        raise ApiError("cannot reach api.github.com: %s" % getattr(e, "reason", e))


def report(rows, commits, out):
    """Prints the listing; returns the exit code."""
    recorded = {r[0] for r in rows}
    pending = [r for r in rows if r[3] == "pending"]
    new = [c for c in commits if c[0] not in recorded]
    if not pending and not new:
        print("up to date", file=out)
        return 0
    for sha, date, subject, _status, _reason in pending:
        print("PENDING %s %s %s" % (sha[:10], date, subject), file=out)
    for sha, date, subject in new:
        print("NEW %s %s %s" % (sha[:10], date, subject), file=out)
    print("BEHIND new=%d pending=%d" % (len(new), len(pending)), file=out)
    return 1


def run(parity_text, fetch, out, err):
    try:
        base, rows = parse_parity(parity_text)
        commits = fetch_commits(start_point(base, rows), fetch)
    except RecordError as e:
        print("PARITY.md: %s" % e, file=err)
        return 2
    except ApiError as e:
        print(str(e), file=err)
        return 3
    return report(rows, commits, out)


# ----------------------------------------------------------------------------------------------------
# self-test


class _Sink:
    def __init__(self):
        self.parts = []

    def write(self, s):
        self.parts.append(s)

    def text(self):
        return "".join(self.parts)


def _sha(n):
    return ("%02x" % n) * 20


def _page(shas, total):
    return json.dumps({
        "status": "ahead",
        "total_commits": total,
        "commits": [{"sha": s, "commit": {"message": "subject %s\n\nbody" % s[:4],
                                           "committer": {"date": "2026-10-1%dT10:00:00Z" % (i % 9)}}}
                    for i, s in enumerate(shas)],
    }).encode()


def _parity(rows):
    head = ("## (a) Ported Node commit\n\n| Item | Value |\n|------|-------|\n"
            "| Branch | `deaksa-fixes` |\n| Upstream base | `%s` (note) |\n\n%s\n\nintro\n\n"
            "| Upstream commit | Date | Subject | Status | Reason |\n|---|---|---|---|---|\n" % (_sha(1), SECTION))
    body = "".join("| `%s` | 2026-10-18 | s | %s | %s |\n" % r for r in rows)
    return head + body + "\n## (g) After\n\n| `%s` | 2026-10-18 | not read | pending | x |\n" % _sha(99)


def _canned(pages, calls):
    def fetch(url, headers):
        calls.append(url)
        i = len(calls) - 1
        body = pages[i] if i < len(pages) else _page([], 0)
        return 200, {}, body
    return fetch


def self_test():
    # (a) empty (f) starts at the upstream base, every canned commit is NEW
    calls, out, err = [], _Sink(), _Sink()
    rc = run(_parity([]), _canned([_page([_sha(2), _sha(3)], 2)], calls), out, err)
    assert rc == 1, (rc, out.text(), err.text())
    assert _sha(1) + "..." + BRANCH in calls[0], calls
    lines = out.text().splitlines()
    assert lines[0].startswith("NEW 0202020202 2026-10-1"), lines
    assert lines[1].startswith("NEW 0303030303 "), lines
    assert lines[-1] == "BEHIND new=2 pending=0", lines
    # (b) all recorded ported/skipped, empty compare: exactly "up to date"
    calls, out = [], _Sink()
    rc = run(_parity([(_sha(2), "ported", "r"), (_sha(3), "skipped", "r")]), _canned([_page([], 0)], calls), out, err)
    assert rc == 0 and out.text() == "up to date\n", (rc, out.text())
    assert _sha(3) + "..." in calls[0], calls
    # (c) a recorded pending row is listed as PENDING even with nothing new
    calls, out = [], _Sink()
    rc = run(_parity([(_sha(2), "pending", "not triaged yet")]), _canned([_page([], 0)], calls), out, err)
    assert rc == 1 and out.text().splitlines() == ["PENDING 0202020202 2026-10-18 s", "BEHIND new=0 pending=1"], out.text()
    # (d) two pages are joined in order
    calls, out = [], _Sink()
    rc = run(_parity([]), _canned([_page([_sha(2), _sha(3)], 3), _page([_sha(4)], 3)], calls), out, err)
    assert rc == 1 and len(calls) == 2 and "page=2" in calls[1], calls
    assert [l[:14] for l in out.text().splitlines()[:3]] == ["NEW 0202020202", "NEW 0303030303", "NEW 0404040404"], out.text()
    # (e) a recorded commit that the compare still returns is not NEW
    calls, out = [], _Sink()
    rc = run(_parity([(_sha(2), "ported", "r"), (_sha(3), "ported", "r")]), _canned([_page([_sha(2), _sha(4)], 2)], calls), out, err)
    assert rc == 1 and out.text().splitlines() == ["NEW 0404040404 2026-10-11 subject 0404", "BEHIND new=1 pending=0"], out.text()
    # a non-200 answer is exit 3 with one line
    err = _Sink()
    rc = run(_parity([]), lambda u, h: (500, {}, b""), _Sink(), err)
    assert rc == 3 and err.text() == "GitHub API answered HTTP 500\n", (rc, err.text())
    assert main(["--bogus"]) == 2
    _self_test_errors()
    _self_test_token_and_redirect()
    _self_test_hostile()
    _self_test_record()
    _self_test_modes()
    print("SELF-TEST OK")
    return 0


def _answer(status, headers=None, body=b""):
    return lambda url, h: (status, headers or {}, body)


def _run_err(fetch, parity=None):
    out, err = _Sink(), _Sink()
    rc = run(parity if parity is not None else _parity([]), fetch, out, err)
    return rc, out.text(), err.text()


def _self_test_errors():
    import time
    reset = int(time.time()) + 600
    hhmm = time.strftime("%H:%M", time.gmtime(reset))
    for code in (403, 429):
        rc, out, err = _run_err(_answer(code, {"x-ratelimit-remaining": "0", "x-ratelimit-reset": str(reset)}))
        assert rc == 3 and out == "" and err.count("\n") == 1, (code, rc, out, err)
        assert "rate limit" in err and hhmm in err and "GITHUB_TOKEN" in err, err
    rc, out, err = _run_err(_answer(403, {"x-ratelimit-remaining": "12", "retry-after": "60"}))
    assert rc == 3 and "60 s" in err and err.count("\n") == 1, (rc, err)
    rc, out, err = _run_err(_answer(404))
    assert rc == 3 and _sha(1)[:10] in err and "not found" in err, err
    rc, out, err = _run_err(_answer(401))
    assert rc == 3 and "GITHUB_TOKEN" in err, err
    rc, out, err = _run_err(_answer(500))
    assert rc == 3 and "HTTP 500" in err, err
    rc, out, err = _run_err(_answer(302))
    assert rc == 3 and "redirect" in err and "HTTP 302" in err, err
    for status in ("diverged", "behind"):
        body = json.dumps({"status": status, "total_commits": 0, "commits": []}).encode()
        rc, out, err = _run_err(_answer(200, {}, body))
        assert rc == 3 and "no longer contains" in err and status in err and _sha(1)[:10] in err, err

    def unreachable(url, headers):
        raise ApiError("cannot reach api.github.com: timed out")
    rc, out, err = _run_err(unreachable)
    assert rc == 3 and "timed out" in err, err


def _self_test_token_and_redirect():
    token = "ghp_secretTOKEN123"
    plain = build_request("https://api.github.com/x", {"Accept": "a"}, None)
    assert "Authorization" not in plain.headers and "Authorization" not in plain.unredirected_hdrs
    with_token = build_request("https://api.github.com/x", {"Accept": "a"}, token)
    assert with_token.unredirected_hdrs.get("Authorization") == "Bearer " + token, with_token.unredirected_hdrs
    assert "Authorization" not in with_token.headers
    assert allowed_target("https://api.github.com/repos/x")
    for bad in ("http://api.github.com/x", "https://evil.example/x", "https://api.github.com.evil.example/x",
                "https://api.github.com@evil.example/x", "ftp://api.github.com/x"):
        assert not allowed_target(bad), bad
    handler = SafeRedirect()
    assert handler.redirect_request(with_token, None, 302, "Found", {}, "https://evil.example/x") is None
    assert handler.redirect_request(with_token, None, 302, "Found", {}, "http://api.github.com/x") is None
    moved = handler.redirect_request(with_token, None, 302, "Found", {}, "https://api.github.com/y")
    assert moved is not None and "Authorization" not in moved.headers and "Authorization" not in moved.unredirected_hdrs
    assert valid_token(token) and not valid_token("a b") and not valid_token("a\nb") and not valid_token("")
    rc, out, err = _run_err(_answer(401))
    assert token not in out + err


def _page_entries(entries, status="ahead", total=None):
    return json.dumps({
        "status": status,
        "total_commits": len(entries) if total is None else total,
        "commits": [{"sha": sha, "commit": {"message": message, "committer": {"date": date}}}
                    for sha, message, date in entries],
    }).encode()


def _self_test_hostile():
    ok_date = "2026-10-18T10:00:00Z"
    for entries in ([("A" * 40, "m", ok_date)], [("a" * 39, "m", ok_date)], [("g" * 40, "m", ok_date)],
                    [(_sha(2), 5, ok_date)], [(_sha(2), "m", "yesterday")], [(_sha(2), "m", None)]):
        rc, out, err = _run_err(_answer(200, {}, _page_entries(entries)))
        assert rc == 3 and "unexpected API answer" in err and out == "", (entries, rc, out, err)
    for body in (b"not json", b"[]", b'{"commits": 5}', b'{"status": "ahead"}', b'{"commits": [5]}',
                 b'{"commits": [], "total_commits": "many", "status": "ahead"}', b""):
        rc, out, err = _run_err(_answer(200, {}, body))
        assert rc == 3 and "unexpected API answer" in err, (body, rc, err)
    rc, out, err = _run_err(_answer(200, {}, b"x" * (MAX_BODY + 1)))
    assert rc == 3 and "larger than" in err, err
    nasty = "A\x1b[31mred | pipe\nsecond line " + "z" * 300
    cleaned = clean_subject(nasty)
    assert "\x1b" not in cleaned and "\n" not in cleaned and "\\|" in cleaned and len(cleaned) <= 100, cleaned
    assert cleaned.startswith("A[31mred \\| pipe"), cleaned
    assert clean_subject("tab\there\x85x\x7fy") == "tab here xy"
    assert clean_subject("") == ""
    rc, out, err = _run_err(_answer(200, {}, _page_entries([(_sha(2), nasty, "2026-10-18T10:00:00Z")])))
    assert rc == 1 and "\x1b" not in out and out.splitlines()[0].count("\n") == 0, out


def _parity_raw(base, rows):
    return ("## (a) x\n\n| Item | Value |\n|---|---|\n| Upstream base | `%s` |\n\n%s\n\n"
            "| Upstream commit | Date | Subject | Status | Reason |\n|---|---|---|---|---|\n%s"
            % (base, SECTION, "".join(rows)))


def _self_test_record():
    good = "| `%s` | 2026-10-18 | s | ported | why |\n" % _sha(2)
    cases = {
        "base not hex": _parity_raw("zz" * 20, []),
        "base short": _parity_raw(_sha(1)[:39], []),
        "row bad sha": _parity_raw(_sha(1), ["| `%s` | 2026-10-18 | s | ported | why |\n" % _sha(2)[:39]]),
        "row unknown status": _parity_raw(_sha(1), ["| `%s` | 2026-10-18 | s | done | why |\n" % _sha(2)]),
        "row duplicate": _parity_raw(_sha(1), [good, good]),
        "ported without reason": _parity_raw(_sha(1), ["| `%s` | 2026-10-18 | s | ported |  |\n" % _sha(2)]),
        "skipped without reason": _parity_raw(_sha(1), ["| `%s` | 2026-10-18 | s | skipped |  |\n" % _sha(2)]),
        "row with four cells": _parity_raw(_sha(1), ["| `%s` | 2026-10-18 | s | ported |\n" % _sha(2)]),
    }
    for name, text in cases.items():
        calls = []
        rc, out, err = _run_err(_canned([_page([], 0)], calls), parity=text)
        assert rc == 2 and out == "" and not calls and err.count("\n") == 1, (name, rc, out, err)
        assert err.startswith("PARITY.md: "), (name, err)
    rc, out, err = _run_err(_canned([_page([], 0)], []), parity="# nothing here\n")
    assert rc == 2 and "Upstream base" in err, err
    pending_ok = _parity_raw(_sha(1), ["| `%s` | 2026-10-18 | s | pending | not triaged yet |\n" % _sha(2)])
    base, rows = parse_parity(pending_ok)
    assert base == _sha(1) and rows[0][3] == "pending", (base, rows)
    named = _parity_raw(_sha(1), ["| `%s` | 2026-10-18 | s | done | why |\n" % _sha(2)])
    rc, out, err = _run_err(_canned([], []), parity=named)
    assert _sha(2)[:10] in err, err


def _self_test_modes():
    pipe_subject = "fix a | b in c"
    page = _page_entries([(_sha(2), pipe_subject + "\n\nbody", "2026-10-18T10:00:00Z"),
                          (_sha(3), "plain", "2026-10-19T10:00:00Z")])
    out, err = _Sink(), _Sink()
    rc = run(_parity([]), _answer(200, {}, page), out, err, mode=MODE_MARKDOWN)
    assert rc == 0 and err.text() == "", (rc, err.text())
    lines = out.text().splitlines()
    assert lines == ["| `%s` | 2026-10-18 | fix a \\| b in c | pending | not triaged yet |" % _sha(2),
                     "| `%s` | 2026-10-19 | plain | pending | not triaged yet |" % _sha(3)], lines
    base, rows = parse_parity(_parity_raw(_sha(1), [l + "\n" for l in lines]))
    assert [(r[0], r[1], r[2], r[3]) for r in rows] == [
        (_sha(2), "2026-10-18", "fix a \\| b in c", "pending"), (_sha(3), "2026-10-19", "plain", "pending")], rows
    out = _Sink()
    rc = run(_parity([(_sha(2), "ported", "r")]), _answer(200, {}, _page_entries([])), out, err, mode=MODE_MARKDOWN)
    assert rc == 0 and out.text() == "", out.text()
    # --all: every (f) row, upper-case status, then the NEW lines
    all_page = _page_entries([(_sha(4), "newest", "2026-10-20T10:00:00Z")])
    out = _Sink()
    rc = run(_parity([(_sha(2), "ported", "r"), (_sha(3), "skipped", "r")]), _answer(200, {}, all_page), out, err, mode=MODE_ALL)
    assert rc == 1 and out.text().splitlines() == [
        "PORTED 0202020202 2026-10-18 s", "SKIPPED 0303030303 2026-10-18 s",
        "NEW 0404040404 2026-10-20 newest", "BEHIND new=1 pending=0"], out.text()
    out = _Sink()
    rc = run(_parity([(_sha(2), "ported", "r"), (_sha(3), "pending", "not triaged yet")]), _answer(200, {}, _page_entries([])), out, err, mode=MODE_ALL)
    assert rc == 1 and out.text().splitlines()[:2] == ["PORTED 0202020202 2026-10-18 s", "PENDING 0303030303 2026-10-18 s"], out.text()
    out = _Sink()
    rc = run(_parity([(_sha(2), "ported", "r"), (_sha(3), "skipped", "r")]), _answer(200, {}, _page_entries([])), out, err, mode=MODE_ALL)
    assert rc == 0 and out.text().splitlines() == ["PORTED 0202020202 2026-10-18 s", "SKIPPED 0303030303 2026-10-18 s"], out.text()
    assert main(["--all", "--markdown"]) == 2
    assert main(["--self-test", "--all"]) == 2


def main(argv):
    if argv == ["--self-test"]:
        return self_test()
    if argv:
        print("usage: upstream-commits.py [--self-test]", file=sys.stderr)
        return 2
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    with open(os.path.join(root, "PARITY.md"), encoding="utf-8") as f:
        text = f.read()
    return run(text, real_fetch, sys.stdout, sys.stderr)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
