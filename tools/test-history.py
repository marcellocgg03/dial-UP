#!/usr/bin/env python3
"""Report whether each ported Kotlin test class was committed before the code file it covers (PAR-02, D-07).

Run as:  python3 -I tools/test-history.py                    (the report for this repository)
         python3 -I tools/test-history.py --root DIR         (another git work tree; also --root=DIR)
         python3 -I tools/test-history.py --parity FILE      (another PARITY.md; also --parity=FILE)

For each ported test class it compares the first commit of the test file with the first commit of the code file it covers,
on the branch that is checked out (the full history lives on gsd-local). The ported classes are the Kotlin classes that
the (c) table of PARITY.md names on a "Phase <n>" row, plus every test class under lounge/ and dial/ (library ports).
The test file is <module>/src/test/kotlin/**/<Class>.kt. The code file is, in order: the override map in this script
(classes whose name does not give their subject), the main file <module>/src/main/kotlin/**/<Class minus Test>.kt, the
main file of the same module with the longest name that the class name (minus Test) starts with, else unmapped.
The first commit of a path is the last line of "git log --follow --diff-filter=A --format=%H -- <path>".

The order of the test commit T and the code commit C: T equal to C is SAME, T an ancestor of C is BEFORE, C an ancestor
of T is AFTER, otherwise PARALLEL. Everything but BEFORE is a violation and carries the first commit after T that touches
the code file as evidence. A violation listed in the (g) table of PARITY.md ("## (g) Tests-before-code exceptions", rows
"| <module> <Class> | <module>/<File>.kt | <reason> |") is an exception and prints its reason.

Output lines (ported classes first, library classes second, each sorted by module and class):
  history of <branch> at <short head>
  BEFORE <module> <Class> <T> -> <code file> <C>
  VIOLATION <SAME|AFTER|PARALLEL> <module> <Class> <T> <date> -> <code file> <C> <date>; next code change after the test: <short> <date> <subject>
  EXCEPTION <verdict> <module> <Class> -> <code file>: <reason>
  UNMAPPED <ported|library> <module> <Class>        no code file found
  NO TEST FILE <module> <Class>                      a (c) class with no test file
  NO HISTORY <module> <Class>                        a file without a first commit (not tracked)
  UNUSED EXCEPTION <module> <Class> -> <code file>   a (g) row that matches no violation
  PAIRS <n> BEFORE <b> EXCEPTIONS <e> VIOLATIONS <v> UNMAPPED <u>
  UNEXPLAINED <n>                                    violations + unmapped ported classes + missing test files + no history

It reports and never gates (D-07): exit 0 after any report. Exit 2, with one line on stderr, for an unknown argument, a
directory that is not a git work tree, a missing PARITY.md or a malformed (g) row.

Standard library only; git is run with argument lists, never through a shell.
"""
import os
import re
import subprocess
import sys

MODULES = ("upnp", "media", "renderer", "lounge", "dial")
LIBRARY_MODULES = ("lounge", "dial")
SECTION_C = "## (c) Node tests"
SECTION_G = "## (g) Tests-before-code exceptions"
SKIP_DIRS = {"build", ".gradle", ".git", "reference", "node_modules", ".idea"}

# Test classes whose name gives no code file (or a misleading one) -> the main file their subject lives in
# (checked against each test's imports and calls). Key: (module, Class); value: the file name under <module>/src/main/kotlin.
OVERRIDES = {
    ("renderer", "GaplessTest"): "Renderer.kt",
    ("renderer", "SeekFallbackTest"): "Renderer.kt",
    ("renderer", "TimeHelpersTest"): "Renderer.kt",  # parseHms is in Renderer.kt
    ("renderer", "DirectProxyTest"): "OkHttpUpstreamFetcher.kt",
    ("media", "CachingResolverTest"): "Resolver.kt",  # class CachingResolver is in Resolver.kt
    ("media", "MediaServerCacheTest"): "FileCache.kt",
}


class ReportError(Exception):
    """A problem with the inputs (exit 2)."""


def git(root, *args):
    """Runs git in root and returns stdout, or None when git fails."""
    p = subprocess.run(["git", "-C", root] + list(args), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                       universal_newlines=True)
    if p.returncode != 0:
        return None
    return p.stdout


def git_ok(root, *args):
    p = subprocess.run(["git", "-C", root] + list(args), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return p.returncode == 0


def table_rows(parity_text, section):
    """The data rows (list of cell lists) of the first table in the section; header and separator rows are skipped."""
    rows = []
    in_section = False
    for line in parity_text.splitlines():
        if line.startswith("## "):
            in_section = line.strip() == section
            continue
        if not in_section or not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if set(cells[0]) <= set("-: "):
            continue
        rows.append(cells)
    return rows


def ported_classes(parity_text):
    """(module, Class) pairs named in the Kotlin test class cell of the (c) rows whose status is 'Phase <n>'."""
    found = set()
    for cells in table_rows(parity_text, SECTION_C):
        if len(cells) < 4 or cells[0].lower().startswith("node test file"):
            continue
        if not re.fullmatch(r"Phase \d+", cells[2]):
            continue
        for m in re.finditer(r"\b(%s) ([A-Za-z0-9_]+Test)\b" % "|".join(MODULES), cells[3]):
            found.add((m.group(1), m.group(2)))
    return found


def exceptions(parity_text):
    """The (g) rows as {(module, Class, code file): reason}. A malformed row raises ReportError."""
    rows = {}
    for cells in table_rows(parity_text, SECTION_G):
        if cells[0].lower().startswith("kotlin test class"):
            continue
        cls = cells[0].strip("`")
        m = re.fullmatch(r"(%s) ([A-Za-z0-9_]+Test)" % "|".join(MODULES), cls)
        code = cells[1].strip("`") if len(cells) > 1 else ""
        if not m or not re.fullmatch(r"(%s)/[A-Za-z0-9_.-]+\.kt" % "|".join(MODULES), code):
            raise ReportError("malformed (g) row: %s" % "|".join(cells))
        rows[(m.group(1), m.group(2), code)] = cells[2] if len(cells) > 2 else ""
    return rows


def walk_kt(root, module, kind):
    """Sorted (relative path, file name) of the .kt files under <module>/src/<kind>/kotlin."""
    base = os.path.join(root, module, "src", kind, "kotlin")
    found = []
    for d, dirs, files in os.walk(base):
        dirs[:] = sorted(x for x in dirs if x not in SKIP_DIRS)
        for name in sorted(files):
            if name.endswith(".kt"):
                found.append((os.path.relpath(os.path.join(d, name), root).replace(os.sep, "/"), name))
    return found


def library_classes(root):
    found = set()
    for module in LIBRARY_MODULES:
        for _path, name in walk_kt(root, module, "test"):
            if name.endswith("Test.kt"):
                found.add((module, name[:-3]))
    return found


def code_file(root, module, cls, overrides):
    """The main file (relative path) a test class covers, or None."""
    main = walk_kt(root, module, "main")
    if (module, cls) in overrides:
        wanted = overrides[(module, cls)]
        return next((p for p, n in main if n == wanted), None)
    stem = cls[:-len("Test")]
    best = None
    for path, name in main:
        base = name[:-3]
        if base and stem.startswith(base) and (best is None or len(base) > len(best[0])):
            best = (base, path)
    return best[1] if best else None


def first_commit(root, path):
    out = git(root, "log", "--follow", "--diff-filter=A", "--format=%H", "--", path)
    lines = out.split() if out else []
    return lines[-1] if lines else None


def describe(root, sha):
    """(short hash, date, subject) of a commit."""
    out = git(root, "log", "-1", "--format=%h%x09%as%x09%s", sha)
    parts = (out or "").rstrip("\n").split("\t", 2)
    while len(parts) < 3:
        parts.append("")
    return parts[0], parts[1], parts[2]


def verdict(root, t, c):
    if t == c:
        return "SAME"
    if git_ok(root, "merge-base", "--is-ancestor", t, c):
        return "BEFORE"
    if git_ok(root, "merge-base", "--is-ancestor", c, t):
        return "AFTER"
    return "PARALLEL"


def build(root, parity_text, overrides=None):
    """Returns (lines, unexplained). Raises ReportError on a malformed (g) row."""
    if overrides is None:
        overrides = OVERRIDES
    excs = exceptions(parity_text)
    ported = ported_classes(parity_text)
    library = library_classes(root) - ported
    branch = (git(root, "rev-parse", "--abbrev-ref", "HEAD") or "?").strip()
    head = (git(root, "rev-parse", "--short", "HEAD") or "?").strip()
    lines = ["history of %s at %s" % (branch, head)]
    before = violations = unmapped = unexplained = 0
    exc_count = 0
    used = set()
    for kind, group in (("ported", ported), ("library", library)):
        for module, cls in sorted(group):
            tests = [p for p, n in walk_kt(root, module, "test") if n == cls + ".kt"]
            if not tests:
                lines.append("NO TEST FILE %s %s" % (module, cls))
                unexplained += 1
                continue
            code = code_file(root, module, cls, overrides)
            if code is None:
                lines.append("UNMAPPED %s %s %s" % (kind, module, cls))
                unmapped += 1
                if kind == "ported":
                    unexplained += 1
                continue
            code_name = "%s/%s" % (module, os.path.basename(code))
            t = first_commit(root, tests[0])
            c = first_commit(root, code)
            if not t or not c:
                lines.append("NO HISTORY %s %s" % (module, cls))
                unexplained += 1
                continue
            v = verdict(root, t, c)
            ts, td, _ = describe(root, t)
            cs, cd, _ = describe(root, c)
            if v == "BEFORE":
                lines.append("BEFORE %s %s %s -> %s %s" % (module, cls, ts, code_name, cs))
                before += 1
                continue
            key = (module, cls, code_name)
            if key in excs:
                used.add(key)
                lines.append("EXCEPTION %s %s %s -> %s: %s" % (v, module, cls, code_name, excs[key]))
                exc_count += 1
                continue
            nxt = (git(root, "rev-list", "--reverse", "--topo-order", "%s..HEAD" % t, "--", code) or "").split()
            if nxt:
                ns, nd, nsub = describe(root, nxt[0])
                evidence = "%s %s %s" % (ns, nd, nsub)
            else:
                evidence = "none"
            lines.append("VIOLATION %s %s %s %s %s -> %s %s %s; next code change after the test: %s"
                         % (v, module, cls, ts, td, code_name, cs, cd, evidence))
            violations += 1
            unexplained += 1
    for key in sorted(set(excs) - used):
        lines.append("UNUSED EXCEPTION %s %s -> %s" % key)
    lines.append("PAIRS %d BEFORE %d EXCEPTIONS %d VIOLATIONS %d UNMAPPED %d"
                 % (before + exc_count + violations, before, exc_count, violations, unmapped))
    lines.append("UNEXPLAINED %d" % unexplained)
    return lines, unexplained


def usage_error(err, message):
    print(message, file=err)
    return 2


def main(argv, out=None, err=None):
    out = out or sys.stdout
    err = err or sys.stderr
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.dirname(here)
    parity = None
    args = list(argv)
    while args:
        a = args.pop(0)
        if a in ("--root", "--parity"):
            if not args:
                return usage_error(err, "usage: test-history.py [--root DIR] [--parity FILE]  (%s needs a value)" % a)
            v = args.pop(0)
        elif a.startswith("--root=") or a.startswith("--parity="):
            a, v = a.split("=", 1)
        else:
            return usage_error(err, "usage: test-history.py [--root DIR] [--parity FILE]  (unknown argument %s)" % a)
        if a == "--root":
            root = v
        else:
            parity = v
    root = os.path.abspath(root)
    top = git(root, "rev-parse", "--show-toplevel") if os.path.isdir(root) else None
    if top is None or os.path.realpath(top.strip()) != os.path.realpath(root):
        return usage_error(err, "%s is not the top of a git work tree" % root)
    parity = parity or os.path.join(root, "PARITY.md")
    if not os.path.isfile(parity):
        return usage_error(err, "PARITY.md not found: %s" % parity)
    with open(parity, encoding="utf-8") as f:
        text = f.read()
    try:
        lines, _unexplained = build(root, text)
    except ReportError as e:
        return usage_error(err, str(e))
    for line in lines:
        print(line, file=out)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
