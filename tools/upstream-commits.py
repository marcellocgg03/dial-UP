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
    print("SELF-TEST OK")
    return 0


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
