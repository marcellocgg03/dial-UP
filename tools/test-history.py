#!/usr/bin/env python3
"""Report whether each ported Kotlin test class was committed before the code file it covers (PAR-02, D-07).

Run as:  python3 -I tools/test-history.py                    (the report for this repository)
         python3 -I tools/test-history.py --root DIR         (another git work tree; also --root=DIR)
         python3 -I tools/test-history.py --parity FILE      (another PARITY.md; also --parity=FILE)
         python3 -I tools/test-history.py --self-test        (throwaway git histories; prints SELF-TEST OK)

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
import hashlib
import io
import os
import re
import shutil
import subprocess
import sys
import tempfile

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
        if a == "--self-test" and len(argv) == 1:
            return self_test(out)
        if a in ("--root", "--parity"):
            if not args:
                return usage_error(err, "usage: test-history.py [--root DIR] [--parity FILE] | --self-test (%s needs a value)" % a)
            v = args.pop(0)
        elif a.startswith("--root=") or a.startswith("--parity="):
            a, v = a.split("=", 1)
        else:
            return usage_error(err, "usage: test-history.py [--root DIR] [--parity FILE] | --self-test (unknown argument %s)" % a)
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


SELF_C = (
    "# P\n\n## (c) Node tests\n\n| Node test file | Test title | Status | Kotlin test class |\n|---|---|---|---|\n"
    "%s\n\n## (d) Adaptations\n"
)
SELF_G_HEAD = "\n## (g) Tests-before-code exceptions\n\nStatus: proposed, pending author acceptance.\n\n" \
              "| Kotlin test class | Code file | Reason |\n|---|---|---|\n"


def c_row(module, cls, status="Phase 2"):
    return "| `a.test.js` | t | %s | %s %s |" % (status, module, cls)


class Repo:
    """A throwaway repository; git never reads the user's own configuration or hooks."""

    def __init__(self, path):
        self.path = path
        os.makedirs(path)
        self.git("init", "-q", "-b", "main")

    def git(self, *args):
        base = ["git", "-C", self.path, "-c", "user.name=t", "-c", "user.email=t@example.invalid",
                "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null"]
        p = subprocess.run(base + list(args), stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)
        if p.returncode != 0:
            raise AssertionError("git %s failed: %s" % (" ".join(args), p.stderr))
        return p.stdout.strip()

    def write(self, rel, text=None):
        if text is None:  # distinct content per file, so that --follow never sees one file as a copy of another
            text = "// %s\n" % rel + "".join(
                "val v%d = \"%s\"\n" % (i, hashlib.sha256(("%s%d" % (rel, i)).encode()).hexdigest()) for i in range(12))
        full = os.path.join(self.path, rel)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", encoding="utf-8") as f:
            f.write(text)

    def commit(self, message, *files):
        for rel in files:
            self.git("add", "--", rel)
        self.git("commit", "-q", "-m", message)
        return self.git("rev-parse", "HEAD")


def tpath(module, cls):
    return "%s/src/test/kotlin/p/%s.kt" % (module, cls)


def mpath(module, name):
    return "%s/src/main/kotlin/p/%s.kt" % (module, name)


def self_test(out):
    saved = dict(os.environ)
    os.environ.update({"GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1"})
    tmp = tempfile.mkdtemp(prefix="test-history-selftest-")
    os.environ["GIT_CEILING_DIRECTORIES"] = tmp
    failures = []

    def check(name, cond, detail=""):
        if not cond:
            failures.append("%s %s" % (name, detail))

    def lines_of(repo, parity, overrides=None):
        lines, unexplained = build(repo.path, parity, overrides)
        return lines, unexplained

    def line_with(lines, start):
        return next((x for x in lines if x.startswith(start)), None)

    try:
        # BEFORE, SAME, AFTER with evidence
        r = Repo(os.path.join(tmp, "basic"))
        r.write(tpath("media", "FooTest")); t1 = r.commit("test foo", tpath("media", "FooTest"))
        r.write(mpath("media", "Foo")); c1 = r.commit("code foo", mpath("media", "Foo"))
        r.write(tpath("media", "BarTest")); r.write(mpath("media", "Bar"))
        r.commit("test and code bar", tpath("media", "BarTest"), mpath("media", "Bar"))
        r.write(mpath("media", "Baz")); r.commit("code baz", mpath("media", "Baz"))
        r.write(tpath("media", "BazTest")); r.commit("test baz", tpath("media", "BazTest"))
        r.write(mpath("media", "Baz"), "changed\n"); r.commit("code baz changed", mpath("media", "Baz"))
        parity = SELF_C % "\n".join(c_row("media", c) for c in ("FooTest", "BarTest", "BazTest"))
        lines, unexplained = lines_of(r, parity)
        check("before", line_with(lines, "BEFORE media FooTest %s -> media/Foo.kt %s" % (t1[:7], c1[:7])) is not None, str(lines))
        check("same", (line_with(lines, "VIOLATION SAME media BarTest") or "").count("next code change after the test: ") == 1, str(lines))
        after = line_with(lines, "VIOLATION AFTER media BazTest") or ""
        check("after evidence", "next code change after the test: " in after and "code baz changed" in after, after)
        check("summary", "PAIRS 3 BEFORE 1 EXCEPTIONS 0 VIOLATIONS 2 UNMAPPED 0" in lines and lines[-1] == "UNEXPLAINED 2", str(lines[-2:]))
        check("header", lines[0].startswith("history of main at "), lines[0])

        # PARALLEL: the test on a side branch, the code on main, merged
        r = Repo(os.path.join(tmp, "parallel"))
        r.write("README", "r\n"); r.commit("root", "README")
        r.git("checkout", "-q", "-b", "side")
        r.write(tpath("media", "ParTest")); r.commit("test par", tpath("media", "ParTest"))
        r.git("checkout", "-q", "main")
        r.write(mpath("media", "Par")); r.commit("code par", mpath("media", "Par"))
        r.git("merge", "-q", "--no-ff", "-m", "merge side", "side")
        lines, unexplained = lines_of(r, SELF_C % c_row("media", "ParTest"))
        check("parallel", line_with(lines, "VIOLATION PARALLEL media ParTest") is not None, str(lines))

        # a renamed test file keeps the first commit of its original name
        r = Repo(os.path.join(tmp, "rename"))
        body = "class RenTest {\n    // " + "line\n    // ".join(str(i) for i in range(30)) + "\n}\n"
        r.write(tpath("media", "RenTest"), body); t1 = r.commit("test ren", tpath("media", "RenTest"))
        r.write(mpath("media", "Ren")); r.commit("code ren", mpath("media", "Ren"))
        new = "media/src/test/kotlin/q/RenTest.kt"
        os.makedirs(os.path.join(r.path, "media/src/test/kotlin/q"))
        r.git("mv", tpath("media", "RenTest"), new)
        r.commit("move test")
        lines, unexplained = lines_of(r, SELF_C % c_row("media", "RenTest"))
        check("rename", line_with(lines, "BEFORE media RenTest %s" % t1[:7]) is not None, str(lines))

        # mapping rules
        r = Repo(os.path.join(tmp, "mapping"))
        tests = [("media", "MediaServerRemuxTest"), ("media", "WidgetTest"), ("media", "OtherTest"), ("media", "NoneTest"),
                 ("lounge", "ZzzTest"), ("lounge", "SessionTest")]
        for module, cls in tests:
            r.write(tpath(module, cls))
        r.commit("tests", *[tpath(m, c) for m, c in tests])
        mains = [("media", "MediaServer"), ("media", "Media"), ("media", "Widget"), ("media", "Wid"), ("media", "Other"),
                 ("media", "Target"), ("lounge", "Session")]
        for module, name in mains:
            r.write(mpath(module, name))
        r.commit("code", *[mpath(m, n) for m, n in mains])
        parity = SELF_C % "\n".join(c_row(m, c) for m, c in (tests[0], tests[1], tests[2], tests[3]))
        lines, unexplained = lines_of(r, parity, {("media", "OtherTest"): "Target.kt"})
        check("longest prefix", line_with(lines, "BEFORE media MediaServerRemuxTest") is not None and
              "-> media/MediaServer.kt" in line_with(lines, "BEFORE media MediaServerRemuxTest"), str(lines))
        check("exact name", "-> media/Widget.kt" in (line_with(lines, "BEFORE media WidgetTest") or ""), str(lines))
        check("override", "-> media/Target.kt" in (line_with(lines, "BEFORE media OtherTest") or ""), str(lines))
        check("ported unmapped", "UNMAPPED ported media NoneTest" in lines, str(lines))
        check("library unmapped", "UNMAPPED library lounge ZzzTest" in lines, str(lines))
        check("library mapped", "-> lounge/Session.kt" in (line_with(lines, "BEFORE lounge SessionTest") or ""), str(lines))
        check("unmapped count", unexplained == 1 and lines[-2].endswith("UNMAPPED 2"), str(lines[-2:]))
        lines, unexplained = lines_of(r, SELF_C % c_row("media", "GoneTest"))
        check("no test file", "NO TEST FILE media GoneTest" in lines and unexplained == 1, str(lines))

        # (c) cells
        cells = ported_classes(SELF_C % "\n".join([
            c_row("renderer", "RendererTest (wire-level cases also in upnp SoapTest)"),
            "| `a.test.js` | t | v2 | (UI-02) |", c_row("media", "SkippedTest", "v2"), c_row("dial", "OtherTest", "Phase 3")]))
        check("c cells", cells == {("renderer", "RendererTest"), ("upnp", "SoapTest"), ("dial", "OtherTest")}, str(sorted(cells)))

        # exceptions
        r = Repo(os.path.join(tmp, "exc"))
        r.write(mpath("media", "Late")); r.commit("code late", mpath("media", "Late"))
        r.write(tpath("media", "LateTest")); r.commit("test late", tpath("media", "LateTest"))
        base = SELF_C % c_row("media", "LateTest")
        lines, unexplained = lines_of(r, base)
        check("violation counted", unexplained == 1 and lines[-2].endswith("VIOLATIONS 1 UNMAPPED 0"), str(lines[-2:]))
        g = SELF_G_HEAD + "| media LateTest | media/Late.kt | a spike came first (abc1234) |\n"
        lines, unexplained = lines_of(r, base + g)
        check("exception", "EXCEPTION AFTER media LateTest -> media/Late.kt: a spike came first (abc1234)" in lines, str(lines))
        check("exception lowers unexplained", unexplained == 0 and lines[-1] == "UNEXPLAINED 0" and
              lines[-2] == "PAIRS 1 BEFORE 0 EXCEPTIONS 1 VIOLATIONS 0 UNMAPPED 0", str(lines[-2:]))
        g2 = SELF_G_HEAD + "| media LateTest | media/Late.kt | r |\n| media GhostTest | media/Ghost.kt | stale |\n"
        lines, unexplained = lines_of(r, base + g2)
        check("unused exception", "UNUSED EXCEPTION media GhostTest -> media/Ghost.kt" in lines, str(lines))
        for bad in ("| media LateTest | Late.kt | r |", "| Late | media/Late.kt | r |", "| media LateTest | media/Late.txt | r |"):
            try:
                lines_of(r, base + SELF_G_HEAD + bad + "\n")
                check("malformed row", False, bad)
            except ReportError:
                pass

        # command line
        with open(os.path.join(r.path, "PARITY.md"), "w", encoding="utf-8") as f:
            f.write(base)
        o, e = io.StringIO(), io.StringIO()
        check("exit 0 with violations", main(["--root", r.path], o, e) == 0 and "VIOLATION AFTER media LateTest" in o.getvalue(), o.getvalue() + e.getvalue())
        o, e = io.StringIO(), io.StringIO()
        check("unknown argument", main(["--nope"], o, e) == 2 and e.getvalue().count("\n") == 1, e.getvalue())
        plain = os.path.join(tmp, "plain")
        os.makedirs(plain)
        o, e = io.StringIO(), io.StringIO()
        check("not a work tree", main(["--root", plain], o, e) == 2 and e.getvalue().count("\n") == 1, e.getvalue())
        o, e = io.StringIO(), io.StringIO()
        check("missing PARITY.md", main(["--root", r.path, "--parity", os.path.join(plain, "none.md")], o, e) == 2, e.getvalue())
        bad_parity = os.path.join(plain, "bad.md")
        with open(bad_parity, "w", encoding="utf-8") as f:
            f.write(base + SELF_G_HEAD + "| nonsense | row | r |\n")
        o, e = io.StringIO(), io.StringIO()
        check("malformed (g) row exits 2", main(["--root", r.path, "--parity", bad_parity], o, e) == 2, e.getvalue())
    except Exception as e:  # a failed git command or a crash is a failure, not a pass
        failures.append("exception: %r" % (e,))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
        os.environ.clear()
        os.environ.update(saved)
    if failures:
        for f in failures:
            print("FAIL %s" % f, file=out)
        print("SELF-TEST FAILED %d" % len(failures), file=out)
        return 1
    print("SELF-TEST OK", file=out)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
