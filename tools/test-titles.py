#!/usr/bin/env python3
"""Check Node test titles against PARITY.md and the Kotlin @DisplayNames (PAR-01, PAR-02).

Run as:  python3 -I tools/test-titles.py                       (the whole Node suite, see below)
         python3 -I tools/test-titles.py --reference DIR       (the suite check on another test directory; also --reference=DIR)
         python3 -I tools/test-titles.py --status "Phase 3"    (only the PARITY.md rows with that Status; also --status=VALUE)
         python3 -I tools/test-titles.py --self-test

Suite mode (no arguments, D-05, D-06): reads every file of reference/tube2upnp/test. A *.test.js file contributes the
title of each test( call (single, double or backtick literal; backslash escapes undone in quoted strings, template text
kept verbatim); any other *.js file is a helper. Each title must have a row in the "(c) Node tests" table of PARITY.md
(same file and title). Output lines, one per title or helper, then a summary line:
  FOUND <file> | <title> -> <kt path>         status "Phase <n>" and a Kotlin @DisplayName with that title exists
  MISSING <file> | <title> (<status>)         status "Phase <n>" but no Kotlin @DisplayName with that title
  DEFERRED <file> | <title> (<status>: <why>) status "v2" or "out of scope": never a failure
  NO ROW <file> | <title>                     the Node title has no PARITY.md (c) row
  UNKNOWN STATUS <file> | <title> (<status>)  a status that is none of the above
  STALE ROW <file> | <title>                  a (c) row whose title is no longer in reference/
  HELPER <name> -> <kt path>                  helper with a (b) File map row test/<name> whose .kt file exists
  MISSING HELPER <name>                       no such row, or its .kt file is missing
  SUITE OK found=<n> deferred=<d> helpers=<h>   exit 0
  SUITE FAILED missing=.. no-row=.. stale=.. unknown=.. helpers-missing=..   exit 1
Exit 2 is a usage error or a missing reference directory (the snapshot is gitignored; see reference/SOURCE.txt).

Status mode (--status VALUE, default "Phase 2"): takes every (c) row whose Status is exactly VALUE and looks for
@DisplayName("<title>") in any *.kt file under */src/test/kotlin (build, .gradle, .git and reference directories are
skipped). Prints "FOUND <title> -> <file>" or "MISSING <title>" per row, then "TITLES OK <n>" and exits 0 when nothing is
missing, otherwise exits 1. Exit 2 is a usage error: an unknown argument, or a status with no rows ("no rows with status
<VALUE>" on stderr), so a typo cannot report TITLES OK 0.

Standard library only; the inputs are repository files, read as text and never executed.
"""
import os
import re
import sys
import tempfile

SKIP_DIRS = {"build", ".gradle", ".git", "reference", "node_modules", ".idea"}
SECTION = "## (c) Node tests"
STATUS = "Phase 2"


def parse_rows(parity_text):
    """Rows of the Node tests table as (file, title, status). The header and separator rows are skipped."""
    rows = []
    in_section = False
    for line in parity_text.splitlines():
        if line.startswith("## "):
            in_section = line.strip() == SECTION
            continue
        if not in_section or not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) < 3:
            continue
        if cells[0].lower().startswith("node test file") or set(cells[0]) <= set("-: "):
            continue
        rows.append((cells[0].strip("`"), cells[1], cells[2]))
    return rows


def kotlin_escape(title):
    """The title as it is written inside a Kotlin string literal."""
    return title.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")


def test_sources(root):
    for base, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        norm = base.replace(os.sep, "/")
        if "/src/test/kotlin" not in norm + "/":
            continue
        for name in files:
            if name.endswith(".kt"):
                yield os.path.join(base, name)


def check(root, parity_path, status=STATUS):
    """Returns (found, missing): found is a list of (title, relative file), missing a list of titles."""
    with open(parity_path, encoding="utf-8") as f:
        rows = [r for r in parse_rows(f.read()) if r[2] == status]
    sources = []
    for path in test_sources(root):
        with open(path, encoding="utf-8", errors="replace") as f:
            sources.append((path, f.read()))
    found, missing = [], []
    for _file, title, _status in rows:
        needles = {'@DisplayName("%s")' % title, '@DisplayName("%s")' % kotlin_escape(title)}
        hit = next((p for p, text in sources if any(n in text for n in needles)), None)
        if hit is None:
            missing.append(title)
        else:
            found.append((title, os.path.relpath(hit, root)))
    return found, missing


def run(root, parity_path, out, status=STATUS):
    with open(parity_path, encoding="utf-8") as f:
        order = [r[1] for r in parse_rows(f.read()) if r[2] == status]
    if not order:
        print("no rows with status %s" % status, file=sys.stderr)
        return 2
    found, missing = check(root, parity_path, status)
    by_title = dict(found)
    for title in order:
        if title in by_title:
            print("FOUND %s -> %s" % (title, by_title[title]), file=out)
        else:
            print("MISSING %s" % title, file=out)
    if missing:
        print("TITLES MISSING %d of %d" % (len(missing), len(order)), file=out)
        return 1
    print("TITLES OK %d" % len(order), file=out)
    return 0


def node_titles(text):
    """Titles of the test( calls in one Node test file, in source order. A test( preceded by a word character, $ or . is not a call
    (x.test(y) is the regex method); a first argument that is not a string literal is not a title."""
    titles = []
    for m in re.finditer(r"(?<![\w.$])test\(", text):
        i = m.end()
        while i < len(text) and text[i].isspace():
            i += 1
        if i >= len(text) or text[i] not in "'\"`":
            continue
        quote = text[i]
        i += 1
        out = []
        while i < len(text) and text[i] != quote:
            if text[i] == "\\" and quote != "`" and i + 1 < len(text):
                i += 1
            out.append(text[i])
            i += 1
        if i < len(text):
            titles.append("".join(out))
    return titles


def helper_rows(parity_text):
    """Rows of the (b) File map whose first cell starts with test/: {node helper name: [kt file names]}."""
    rows = {}
    in_section = False
    for line in parity_text.splitlines():
        if line.startswith("## "):
            in_section = line.strip() == "## (b) File map"
            continue
        if not in_section or not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        first = cells[0].strip("`")
        if len(cells) >= 2 and first.startswith("test/"):
            rows[first[len("test/"):]] = re.findall(r"[A-Za-z0-9_]+\.kt", cells[1])
    return rows


def kotlin_dirs(root, pattern):
    """Every *.kt file under a directory matching pattern (build, .gradle, .git, reference ... skipped): (path, name)."""
    for base, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        if re.search(pattern, base.replace(os.sep, "/") + "/"):
            for name in files:
                if name.endswith(".kt"):
                    yield os.path.join(base, name), name


def suite(root, parity_path, ref_dir, out):
    """The whole Node suite against PARITY.md and the Kotlin tests. Returns the exit code (0 ok, 1 failure)."""
    with open(parity_path, encoding="utf-8") as f:
        parity_text = f.read()
    rows = {}
    for file, title, status in parse_rows(parity_text):
        rows.setdefault((file, title), status)
    # the Kotlin cell of a row, for the deferred reason
    cells = {}
    in_section = False
    for line in parity_text.splitlines():
        if line.startswith("## "):
            in_section = line.strip() == SECTION
            continue
        if in_section and line.startswith("|"):
            c = [x.strip() for x in line.strip().strip("|").split("|")]
            if len(c) >= 4:
                cells.setdefault((c[0].strip("`"), c[1]), c[3])
    helpers = helper_rows(parity_text)
    sources = []
    for path in test_sources(root):
        with open(path, encoding="utf-8", errors="replace") as f:
            sources.append((path, f.read()))
    fixtures = list(kotlin_dirs(root, r"/src/test[^/]*/kotlin/"))
    seen = set()
    found = deferred = nhelpers = missing = no_row = stale = unknown = helpers_missing = 0
    for name in sorted(os.listdir(ref_dir)):
        full = os.path.join(ref_dir, name)
        if not name.endswith(".js") or not os.path.isfile(full):
            continue
        if name.endswith(".test.js"):
            with open(full, encoding="utf-8", errors="replace") as f:
                titles = node_titles(f.read())
            for title in titles:
                seen.add((name, title))
                row = rows.get((name, title))
                if row is None:
                    print("NO ROW %s | %s" % (name, title), file=out)
                    no_row += 1
                    continue
                status = row
                if re.fullmatch(r"Phase \d+", status):
                    needles = {'@DisplayName("%s")' % title, '@DisplayName("%s")' % kotlin_escape(title)}
                    hit = next((p for p, text in sources if any(n in text for n in needles)), None)
                    if hit is None:
                        print("MISSING %s | %s (%s)" % (name, title, status), file=out)
                        missing += 1
                    else:
                        print("FOUND %s | %s -> %s" % (name, title, os.path.relpath(hit, root).replace(os.sep, "/")), file=out)
                        found += 1
                elif status in ("v2", "out of scope"):
                    print("DEFERRED %s | %s (%s: %s)" % (name, title, status, cells.get((name, title), "").strip("()")), file=out)
                    deferred += 1
                else:
                    print("UNKNOWN STATUS %s | %s (%s)" % (name, title, status), file=out)
                    unknown += 1
        else:
            names = helpers.get(name, [])
            hit = next((p for p, n in fixtures if n in names), None)
            if hit is None:
                print("MISSING HELPER %s" % name, file=out)
                helpers_missing += 1
            else:
                print("HELPER %s -> %s" % (name, os.path.relpath(hit, root).replace(os.sep, "/")), file=out)
                nhelpers += 1
    for (file, title) in rows:
        if (file, title) not in seen:
            print("STALE ROW %s | %s" % (file, title), file=out)
            stale += 1
    if missing or no_row or stale or unknown or helpers_missing:
        print("SUITE FAILED missing=%d no-row=%d stale=%d unknown=%d helpers-missing=%d"
              % (missing, no_row, stale, unknown, helpers_missing), file=out)
        return 1
    print("SUITE OK found=%d deferred=%d helpers=%d" % (found, deferred, nhelpers), file=out)
    return 0


SUITE_NODE = (
    "test('plain title', async () => {});\n"
    "test('video\\'s escaped title', () => {});\n"
    "for (const bits of [16]) {\n"
    "  test(`flac (${bits}-bit): lossless`, () => {});\n"
    "}\n"
    "assert.ok(/abc/.test('abc'));\n"
)
SUITE_PARITY = (
    "# Parity\n\n## (b) File map\n\n| Node file | Kotlin file(s) | Status |\n|---|---|---|\n"
    "| `src/x.js` | `x/X.kt` | ported |\n"
    "| `test/fake.js` | `mod/src/testFixtures/.../Fake.kt` | ported |\n\n"
    "## (c) Node tests\n\n"
    "| Node test file | Test title | Status | Kotlin test class |\n|---|---|---|---|\n"
    "| `a.test.js` | plain title | Phase 2 | kt A |\n"
    "| `a.test.js` | video's escaped title | Phase 3 | kt A |\n"
    "| `a.test.js` | flac (${bits}-bit): lossless | Phase 3 | kt A |\n"
    "| `b.test.js` | deferred one | v2 | (UI-02) |\n"
    "| `b.test.js` | deferred two | out of scope | (no status page) |\n"
)


def suite_tree(tmp, parity=SUITE_PARITY, node_a=SUITE_NODE, kotlin=True, fixture=True):
    """Writes a temporary repo (reference tests, PARITY.md, Kotlin sources); returns (root, reference dir, parity path)."""
    ref = os.path.join(tmp, "reference", "tube2upnp", "test")
    os.makedirs(ref)
    with open(os.path.join(ref, "a.test.js"), "w", encoding="utf-8") as f:
        f.write(node_a)
    with open(os.path.join(ref, "b.test.js"), "w", encoding="utf-8") as f:
        f.write("test('deferred one', () => {});\ntest(\"deferred two\", () => {});\n")
    with open(os.path.join(ref, "fake.js"), "w", encoding="utf-8") as f:
        f.write("module.exports = {};\n")
    tests = os.path.join(tmp, "mod", "src", "test", "kotlin")
    os.makedirs(tests)
    if kotlin:
        with open(os.path.join(tests, "ATest.kt"), "w", encoding="utf-8") as f:
            f.write('@DisplayName("plain title")\n@DisplayName("video\'s escaped title")\n'
                    '@DisplayName("flac (\\${bits}-bit): lossless")\n')
    if fixture:
        fx = os.path.join(tmp, "mod", "src", "testFixtures", "kotlin", "p")
        os.makedirs(fx)
        with open(os.path.join(fx, "Fake.kt"), "w", encoding="utf-8") as f:
            f.write("class Fake\n")
    path = os.path.join(tmp, "PARITY.md")
    with open(path, "w", encoding="utf-8") as f:
        f.write(parity)
    return tmp, ref, path


def run_suite(tmp, **kw):
    import io
    root, ref, path = suite_tree(tmp, **kw)
    buf = io.StringIO()
    code = suite(root, path, ref, buf)
    return code, buf.getvalue().splitlines()


def suite_self_test():
    assert node_titles(SUITE_NODE) == ["plain title", "video's escaped title", "flac (${bits}-bit): lossless"], node_titles(SUITE_NODE)
    assert node_titles("test(name, fn); x.test(y); mytest('no'); test(`a`, 1)") == ["a"]
    with tempfile.TemporaryDirectory() as tmp:
        code, lines = run_suite(tmp)
        assert code == 0, lines
        assert "HELPER fake.js -> mod/src/testFixtures/kotlin/p/Fake.kt" in lines, lines
        assert "FOUND a.test.js | plain title -> mod/src/test/kotlin/ATest.kt" in lines, lines
        assert "FOUND a.test.js | video's escaped title -> mod/src/test/kotlin/ATest.kt" in lines, lines
        assert "FOUND a.test.js | flac (${bits}-bit): lossless -> mod/src/test/kotlin/ATest.kt" in lines, lines
        assert "DEFERRED b.test.js | deferred one (v2: UI-02)" in lines, lines
        assert "DEFERRED b.test.js | deferred two (out of scope: no status page)" in lines, lines
        assert lines[-1] == "SUITE OK found=3 deferred=2 helpers=1", lines
        assert not any("abc" in l for l in lines), lines


def self_test():
    parity = (
        "# Parity\n\n## (b) Other\n\n| a | b | c |\n|---|---|---|\n| `x.js` | not read | Phase 2 | nothing |\n\n"
        "## (c) Node tests\n\nintro text\n\n"
        "| Node test file | Test title | Status | Kotlin test class |\n"
        "|----------------|------------|--------|-------------------|\n"
        "| `a.test.js` | plain title | Phase 2 | kt A |\n"
        "| `a.test.js` | title with \"quotes\" and $dollar | Phase 2 | kt A |\n"
        "| `a.test.js` | later phase title | Phase 3 | (planned) |\n"
        "| `a.test.js` | phase three present title | Phase 3 | kt C |\n"
        "| `b.test.js` | absent title | Phase 2 | kt B |\n\n"
        "## (d) After\n\n| `z.js` | after the table | Phase 2 | z |\n"
    )
    rows = [r for r in parse_rows(parity) if r[2] == STATUS]
    assert [r[1] for r in rows] == ["plain title", 'title with "quotes" and $dollar', "absent title"], rows
    rows3 = [r for r in parse_rows(parity) if r[2] == "Phase 3"]
    assert [r[1] for r in rows3] == ["later phase title", "phase three present title"], rows3
    with tempfile.TemporaryDirectory() as tmp:
        os.makedirs(os.path.join(tmp, "mod", "src", "test", "kotlin", "p"))
        os.makedirs(os.path.join(tmp, "mod", "build", "src", "test", "kotlin"))
        os.makedirs(os.path.join(tmp, "mod", "src", "main", "kotlin"))
        with open(os.path.join(tmp, "PARITY.md"), "w", encoding="utf-8") as f:
            f.write(parity)
        with open(os.path.join(tmp, "mod", "src", "test", "kotlin", "p", "ATest.kt"), "w", encoding="utf-8") as f:
            f.write('@DisplayName("plain title")\nfun a() {}\n'
                    '@DisplayName("title with \\"quotes\\" and \\$dollar")\nfun b() {}\n')
        # a title that only appears under build/ or in main code must not count
        with open(os.path.join(tmp, "mod", "build", "src", "test", "kotlin", "B.kt"), "w", encoding="utf-8") as f:
            f.write('@DisplayName("absent title")\n')
        with open(os.path.join(tmp, "mod", "src", "main", "kotlin", "C.kt"), "w", encoding="utf-8") as f:
            f.write('@DisplayName("absent title")\n')
        found, missing = check(tmp, os.path.join(tmp, "PARITY.md"))
        assert len(found) == 2 and missing == ["absent title"], (found, missing)
        sink = open(os.devnull, "w")
        assert run(tmp, os.path.join(tmp, "PARITY.md"), sink) == 1
        with open(os.path.join(tmp, "mod", "src", "test", "kotlin", "p", "BTest.kt"), "w", encoding="utf-8") as f:
            f.write('@DisplayName("absent title")\n')
        assert run(tmp, os.path.join(tmp, "PARITY.md"), sink) == 0
        # a second status: Phase 3 has one missing and one present title
        with open(os.path.join(tmp, "mod", "src", "test", "kotlin", "p", "CTest.kt"), "w", encoding="utf-8") as f:
            f.write('@DisplayName("phase three present title")\n')
        assert run(tmp, os.path.join(tmp, "PARITY.md"), sink, status="Phase 3") == 1
        found3, missing3 = check(tmp, os.path.join(tmp, "PARITY.md"), status="Phase 3")
        assert [t for t, _ in found3] == ["phase three present title"] and missing3 == ["later phase title"], (found3, missing3)
        with open(os.path.join(tmp, "mod", "src", "test", "kotlin", "p", "DTest.kt"), "w", encoding="utf-8") as f:
            f.write('@DisplayName("later phase title")\n')
        assert run(tmp, os.path.join(tmp, "PARITY.md"), sink, status="Phase 3") == 0
        # the default status is unchanged
        assert run(tmp, os.path.join(tmp, "PARITY.md"), sink) == 0
        # a status with no rows is a usage error, never TITLES OK 0
        assert run(tmp, os.path.join(tmp, "PARITY.md"), sink, status="Phase 9") == 2
        assert main(["--bogus"]) == 2
        assert main(["--status"]) == 2
    suite_self_test()
    print("SELF-TEST OK")
    return 0


def main(argv):
    status = None
    reference = None
    self_testing = False
    i = 0
    while i < len(argv):
        arg = argv[i]
        if arg == "--self-test":
            self_testing = True
        elif arg == "--status" and i + 1 < len(argv):
            i += 1
            status = argv[i]
        elif arg.startswith("--status="):
            status = arg[len("--status="):]
        elif arg == "--reference" and i + 1 < len(argv):
            i += 1
            reference = argv[i]
        elif arg.startswith("--reference="):
            reference = arg[len("--reference="):]
        else:
            print("usage: test-titles.py [--status VALUE | --reference DIR] [--self-test]", file=sys.stderr)
            return 2
        i += 1
    if self_testing:
        return self_test()
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    parity = os.path.join(root, "PARITY.md")
    if status is not None:
        return run(root, parity, sys.stdout, status)
    ref_dir = reference if reference is not None else os.path.join(root, "reference", "tube2upnp", "test")
    if not os.path.isdir(ref_dir):
        print("no reference test directory at %s: the reference/ snapshot is gitignored and exists only in the main checkout "
              "(see reference/SOURCE.txt); pass --reference DIR to use another test directory" % ref_dir, file=sys.stderr)
        return 2
    return suite(root, parity, ref_dir, sys.stdout)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
