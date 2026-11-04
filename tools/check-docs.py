#!/usr/bin/env python3
"""Check the public Markdown documents: every relative link resolves and no private path is named.

Run as:  python3 -I tools/check-docs.py [FILES...]     check FILES (default: every tracked *.md at the repository root)
         python3 -I tools/check-docs.py --self-test    run the built-in tests and print "check-docs self-test OK"

What is checked:
  * relative Markdown links, image links, reference definitions and HTML href/src attributes must point at an
    existing file or directory, relative to the document that contains them;
  * links inside HTML comments and inside code (fenced blocks and inline code) are ignored, so a commented-out
    screenshot list does not count as a broken image;
  * anchors (#section), absolute URLs (http, https and any other scheme) and mailto links are skipped;
  * the text of a document (comments included, because a comment is public too) must not name the private
    planning directory, the private project instructions file, the assistant directory or research and
    device-results notes.

Exit status 0 when everything is fine, 1 when any error is found (one line per error), 2 on a usage error.
Standard library only (Python 3.10).
"""
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from urllib.parse import unquote

# Each entry: (regex, what it names). The text of a public document must match none of them.
PRIVATE_PATTERNS = [
    (re.compile(r"(?<![A-Za-z0-9_])\.planning(?![A-Za-z0-9_])"), "the private planning directory"),
    (re.compile(r"(?<![A-Za-z0-9_])\.claude(?![A-Za-z0-9_])"), "the private assistant directory"),
    (re.compile(r"(?<![A-Za-z0-9_])\.gsd(?![A-Za-z0-9_])"), "the private workflow directory"),
    (re.compile(r"(?<![A-Za-z0-9_])CLAUDE\.md"), "the private project instructions file"),
    (re.compile(r"[0-9]{2}-(?:[A-Z]+-)?DEVICE-RESULTS"), "a private device-results note"),
    (re.compile(r"(?<![A-Za-z0-9_])(?:[0-9]{2}-)?RESEARCH\.md"), "a private research note"),
]

FENCE = re.compile(r"^[ \t]*(```|~~~).*?^[ \t]*\1[ \t]*$", re.S | re.M)
COMMENT = re.compile(r"<!--.*?-->", re.S)
INLINE_CODE = re.compile(r"(`+)(?:(?!\1).)+?\1", re.S)
INLINE_LINK = re.compile(r"!?\[[^\]\n]*\]\(\s*<?([^)\s>]+)>?(?:\s+(?:\"[^\"]*\"|'[^']*'))?\s*\)")
REFERENCE_DEF = re.compile(r"^[ \t]{0,3}\[[^\]\n]+\]:[ \t]*<?(\S+?)>?(?:[ \t]+.*)?$", re.M)
HTML_ATTR = re.compile(r"""\b(?:href|src)\s*=\s*["']([^"']+)["']""", re.I)
SCHEME = re.compile(r"^[A-Za-z][A-Za-z0-9+.-]*:")


def strip_for_links(text: str) -> str:
    """The text with HTML comments, fenced code blocks and inline code removed."""
    text = COMMENT.sub("", text)
    text = FENCE.sub("", text)
    return INLINE_CODE.sub("", text)


def link_targets(text: str) -> list[str]:
    """Every link target of a document, in order of appearance by kind (inline, reference, HTML attribute)."""
    body = strip_for_links(text)
    return INLINE_LINK.findall(body) + REFERENCE_DEF.findall(body) + HTML_ATTR.findall(body)


def resolve(target: str, doc: Path, root: Path) -> Path | None:
    """The file a relative [target] names, or None when the target is not a relative path (anchor, URL, mailto)."""
    if not target or target.startswith("#") or target.startswith("//") or SCHEME.match(target):
        return None
    path = unquote(re.split(r"[#?]", target, maxsplit=1)[0])
    if not path:
        return None
    if path.startswith("/"):
        return root / path.lstrip("/")
    return doc.parent / path


def check_file(doc: Path, root: Path) -> list[str]:
    """The error lines for one document (empty when it is fine)."""
    errors: list[str] = []
    name = doc.relative_to(root).as_posix() if doc.is_relative_to(root) else doc.as_posix()
    try:
        text = doc.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as e:
        return [f"{name}: cannot read the document ({e.__class__.__name__})"]
    for target in link_targets(text):
        path = resolve(target, doc, root)
        if path is not None and not path.exists():
            errors.append(f"{name}: broken relative link: {target}")
    for number, line in enumerate(text.splitlines(), start=1):
        for pattern, what in PRIVATE_PATTERNS:
            if pattern.search(line):
                errors.append(f"{name}:{number}: names {what}")
    return errors


def repo_root() -> Path:
    out = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True, check=False, cwd=Path(__file__).resolve().parent
    )
    if out.returncode == 0 and out.stdout.strip():
        return Path(out.stdout.strip())
    return Path(__file__).resolve().parent.parent


def default_files(root: Path) -> list[Path]:
    """Every tracked Markdown file that sits directly in the repository root."""
    out = subprocess.run(["git", "ls-files", "*.md"], capture_output=True, text=True, check=False, cwd=root)
    names = out.stdout.split() if out.returncode == 0 else [p.name for p in root.glob("*.md")]
    return [root / n for n in names if "/" not in n and (root / n).is_file()]


def run(files: list[Path], root: Path) -> int:
    errors: list[str] = []
    for f in files:
        errors.extend(check_file(f, root))
    for line in errors:
        print(line)
    if errors:
        return 1
    print(f"check-docs: {len(files)} document(s) fine")
    return 0


def self_test() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "docs").mkdir()
        (root / "docs" / "real.md").write_text("x\n")
        (root / "exists.md").write_text("x\n")

        def doc(name: str, text: str) -> Path:
            p = root / name
            p.write_text(text, encoding="utf-8")
            return p

        # Test 1: a missing relative link is reported and the run exits 1.
        bad = doc("bad.md", "See [the guide](missing.md) and [ok](exists.md).\n")
        errs = check_file(bad, root)
        assert len(errs) == 1 and "missing.md" in errs[0], errs
        assert run([bad], root) == 1

        # Test 2: an image link inside an HTML comment is ignored, the same link outside it is checked.
        commented = doc("commented.md", "<!--\n![shot](docs/screenshots/a.png)\n-->\ntext\n")
        assert check_file(commented, root) == [], check_file(commented, root)
        open_img = doc("open.md", "![shot](docs/screenshots/a.png)\n")
        errs = check_file(open_img, root)
        assert len(errs) == 1 and "a.png" in errs[0], errs

        # Test 3: private-path patterns are reported, in prose and inside a comment.
        for text in (
            "See the .planning directory.\n",
            "Rules live in CLAUDE.md.\n",
            "<!-- from .claude/notes -->\n",
            "Numbers in 02-DEVICE-RESULTS.md.\n",
            "Read 01-RESEARCH.md first.\n",
        ):
            errs = check_file(doc("private.md", text), root)
            assert any("names" in e for e in errs), (text, errs)
        assert check_file(doc("clean.md", "A planning step and a Claude-free text.\n"), root) == []

        # Test 4: anchors, absolute URLs and mailto are skipped; existing files and directories pass.
        fine = doc(
            "fine.md",
            "[a](#top) [b](https://example.org/x) [c](mailto:someone@example.org) [d](docs/) [e](docs/real.md#part) "
            "[f](exists.md \"title\") [g](/exists.md) [h](ftp://example.org/y)\n\n[ref]: docs/real.md\n",
        )
        assert check_file(fine, root) == [], check_file(fine, root)

        # Reference definitions and HTML attributes are checked; code is ignored.
        errs = check_file(doc("ref.md", "[ref]: nowhere.md\n<img src=\"nothing.png\">\n"), root)
        assert len(errs) == 2, errs
        code = doc("code.md", "`[x](gone.md)`\n\n```\n[y](gone2.md)\n```\n")
        assert check_file(code, root) == [], check_file(code, root)

        # A directory of documents passes as a whole.
        assert run([fine, code], root) == 0
    print("check-docs self-test OK")
    return 0


def main(argv: list[str]) -> int:
    args = argv[1:]
    if args == ["--self-test"]:
        try:
            return self_test()
        except AssertionError as e:
            print(f"check-docs self-test FAILED: {e}")
            return 1
    if any(a.startswith("-") for a in args):
        print("usage: check-docs.py [--self-test] [FILES...]", file=sys.stderr)
        return 2
    root = repo_root()
    files = [Path(a).resolve() for a in args] if args else default_files(root)
    missing = [f for f in files if not f.is_file()]
    if missing:
        for f in missing:
            print(f"{f}: no such document")
        return 1
    return run(files, root)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
