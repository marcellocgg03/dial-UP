#!/usr/bin/env bash
# uiautomator helper for the DialUP screen. The Compose testTags are exposed as resource-ids
# (testTagsAsResourceId on the root), so every control is addressed by its tag.
#
#   tools/ui.sh dump                 print the current UI hierarchy as XML
#   tools/ui.sh has TAG              exit 0 when a node with resource-id TAG exists, 1 otherwise
#   tools/ui.sh text TAG             the node's text (or its first non-empty descendant text)
#   tools/ui.sh tap TAG              tap the centre of the node's bounds
#   tools/ui.sh count PREFIX         number of nodes whose resource-id starts with PREFIX
#   tools/ui.sh type TEXT            type TEXT into the focused field (spaces escaped)
#   tools/ui.sh tap-text TEXT        tap the centre of the first node whose text equals TEXT
#                                    (fallback for a dialog whose tags do not surface)
#   tools/ui.sh front                wake the phone and bring DialUP to the foreground
#
# UI_DUMP_FILE=path makes has/text/tap/count/tap-text read that XML file instead of dumping the
# phone (used to test the parser off-device; tap then prints the coordinates and does not tap).
# The device is T2U_SERIAL (set by tools/env.sh from .t2u/device).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=tools/env.sh
. "$ROOT/tools/env.sh"

REMOTE_DUMP="/sdcard/dialup-ui.xml"

usage() { sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; }

adb_() {
  if [ -n "${T2U_SERIAL:-}" ]; then
    adb -s "$T2U_SERIAL" "$@"
  else
    adb "$@"
  fi
}

# Print the UI hierarchy XML on stdout (from UI_DUMP_FILE when set, otherwise from the phone).
get_dump() {
  if [ -n "${UI_DUMP_FILE:-}" ]; then
    cat "$UI_DUMP_FILE"
    return
  fi
  # The phone dozes within seconds on wireless adb and a dump of a dozing or locked screen has no app
  # nodes, so wake it and drop the (non-secure) keyguard first; both are no-ops on an awake phone.
  adb_ shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  adb_ shell wm dismiss-keyguard >/dev/null 2>&1 || true
  adb_ shell uiautomator dump "$REMOTE_DUMP" >/dev/null 2>&1 || {
    echo "ui: uiautomator dump failed (device unreachable or screen off?)" >&2
    exit 1
  }
  adb_ exec-out cat "$REMOTE_DUMP"
}

# query MODE ARG: run the parser over the dump on stdin.
query() {
  python3 -I -c '
import sys, re
import xml.etree.ElementTree as ET

mode, arg = sys.argv[1], sys.argv[2]
raw = sys.stdin.read()
start = raw.find("<?xml")
if start < 0:
    start = raw.find("<hierarchy")
if start < 0:
    sys.stderr.write("ui: no hierarchy in the dump\n")
    sys.exit(1)
root = ET.fromstring(raw[start:])
nodes = list(root.iter("node"))
# A dump of another app (or the lock screen) must not read as "no such node" or "zero cards".
pkgs = {n.get("package", "") for n in nodes}
if any(pkgs) and not any(p.startswith("dev.dialup") for p in pkgs):
    sys.stderr.write("ui: DialUP is not in the foreground (saw %s); run tools/ui.sh front\n" % ", ".join(sorted(p for p in pkgs if p)))
    sys.exit(3)

def centre(n):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2

def by_id(tag):
    return [n for n in nodes if n.get("resource-id", "") == tag or n.get("resource-id", "").endswith("/" + tag)]

def first_text(n):
    for d in n.iter("node"):
        t = d.get("text", "")
        if t:
            return t
    return ""

if mode == "has":
    sys.exit(0 if by_id(arg) else 1)
elif mode == "text":
    hits = by_id(arg)
    if not hits:
        sys.exit(1)
    print(first_text(hits[0]))
elif mode == "tap":
    hits = by_id(arg)
    if not hits:
        sys.stderr.write("ui: no node with resource-id %s\n" % arg)
        sys.exit(1)
    c = centre(hits[0])
    if c is None:
        sys.stderr.write("ui: node %s has no bounds\n" % arg)
        sys.exit(1)
    print("%d %d" % c)
elif mode == "count":
    print(sum(1 for n in nodes if n.get("resource-id", "").split("/")[-1].startswith(arg)))
elif mode == "tap-text":
    for n in nodes:
        if n.get("text", "") == arg:
            c = centre(n)
            if c is not None:
                print("%d %d" % c)
                sys.exit(0)
    sys.stderr.write("ui: no node with text %r\n" % arg)
    sys.exit(1)
' "$1" "$2"
}

need_arg() {
  if [ -z "${2:-}" ]; then
    echo "ui: $1 needs an argument" >&2
    exit 64
  fi
}

# Pass the argument as a single shell word to the phone's input command.
escape_input_text() {
  local s=$1
  s="${s// /%s}"
  printf '%s' "$s"
}

main() {
  local sub=${1:-}
  case "$sub" in
    '' | -h | --help | help)
      usage
      ;;
    dump)
      get_dump
      ;;
    has | text | count)
      need_arg "$sub" "${2:-}"
      get_dump | query "$sub" "$2"
      ;;
    tap | tap-text)
      need_arg "$sub" "${2:-}"
      local xy
      xy="$(get_dump | query "$sub" "$2")"
      if [ -n "${UI_DUMP_FILE:-}" ]; then
        printf '%s\n' "$xy"
      else
        # shellcheck disable=SC2086
        adb_ shell input tap $xy
      fi
      ;;
    front)
      adb_ shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
      adb_ shell wm dismiss-keyguard >/dev/null 2>&1 || true
      adb_ shell am start -n dev.dialup.debug/dev.dialup.app.MainActivity >/dev/null
      sleep 1
      ;;
    type)
      need_arg type "${2:-}"
      adb_ shell input text "$(escape_input_text "$2")"
      ;;
    *)
      echo "ui: unknown subcommand '$sub'" >&2
      usage >&2
      exit 64
      ;;
  esac
}

main "$@"
