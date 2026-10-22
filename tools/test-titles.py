#!/usr/bin/env python3
"""Check that every Node test title PARITY.md assigns to a status exists as a Kotlin @DisplayName (PAR-01, PAR-02).

Run as:  python3 -I tools/test-titles.py                       (rows with Status "Phase 2")
         python3 -I tools/test-titles.py --status "Phase 3"    (rows with that Status; also --status=VALUE)
         python3 -I tools/test-titles.py --self-test

Reads the "(c) Node tests" table in PARITY.md (columns: Node test file, Test title, Status, Kotlin test class),
takes every row whose Status is exactly the chosen status (default "Phase 2"), and looks for @DisplayName("<title>") in any *.kt file under
*/src/test/kotlin (build, .gradle, .git and reference directories are skipped). Prints "FOUND <title> -> <file>" or
"MISSING <title>" per row, then "TITLES OK <n>" and exits 0 when nothing is missing, otherwise exits 1. Exit 2 is a usage error: an unknown
argument, or a status with no rows ("no rows with status <VALUE>" on stderr), so a typo cannot report TITLES OK 0.

Standard library only; the inputs are repository files, read as text and never executed.
"""
import os
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
    print("SELF-TEST OK")
    return 0


def main(argv):
    status = STATUS
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
        else:
            print("usage: test-titles.py [--status VALUE] [--self-test]", file=sys.stderr)
            return 2
        i += 1
    if self_testing:
        return self_test()
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    return run(root, os.path.join(root, "PARITY.md"), sys.stdout, status)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
