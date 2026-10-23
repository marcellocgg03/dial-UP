#!/usr/bin/env bash
# Puts the optional files/config.json on the phone from WSL (plan 03-11, SVC-07 and DISC-04).
# The file lives in the debug app's private files directory, so it is only reachable through `run-as`,
# which works on a debuggable build (what tools/dev-install.sh installs) and nowhere else.
#
# The three-step flow:
#   1. tools/config.sh check FILE     validate the file offline (no device needed)
#   2. tools/config.sh push FILE      write it to files/config.json in the app
#   3. tools/config.sh reload         make the running app re-read it (restarts the bridge; playback is interrupted)
#
# Nothing here escalates privileges or writes outside the app's files directory, and the file's content only
# ever travels on stdin: no remote command is built from it.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=tools/env.sh
. "$ROOT/tools/env.sh"

PKG="dev.t2u.spike"
REMOTE_FILE="files/config.json"
REMOTE_TMP="files/config.json.tmp"
MAX_BYTES=65536
FORMATS="auto direct mp4 adts flac mp3"

usage() {
  cat <<'EOF'
Usage: tools/config.sh <subcommand> [args]

Subcommands:
  check FILE     validate FILE offline: at most 64 KiB, UTF-8 JSON, a JSON object; lists the keys the app
                 reads and the keys it ignores on purpose (no device needed)
  push FILE      run check, then write FILE to the app's files/config.json (via adb run-as)
  show           print the app's files/config.json, or say there is none
  clear          remove files/config.json so the app falls back to its defaults
  reload         make the running app re-read the file (restarts the bridge; playback is interrupted)
  format NAME    write a one-key file {"outputFormat":"NAME"} and reload; NAME is one of:
                 auto direct mp4 adts flac mp3 (replaces any existing config.json)
  --help         show this list

The keys: include, exclude, outputFormat, startTimeoutMs, pollIntervalMs, mp3Bitrate, flacBits,
ytdlpVerbose, ffmpegLogLevel, prefetch, gapless, cacheMb. The schema is documented in renderer/.../ConfigFile.kt.
The device is T2U_SERIAL (set by tools/env.sh from .t2u/device) or the only attached adb device.
EOF
}

SERIAL=""
resolve_serial() {
  if [ -n "$SERIAL" ]; then return; fi
  if [ -n "${T2U_SERIAL:-}" ]; then
    SERIAL="$T2U_SERIAL"
    return
  fi
  local devices count
  devices="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
  count="$(printf '%s\n' "$devices" | grep -c . || true)"
  if [ "$count" -eq 0 ]; then
    echo "config: no adb device found. Pair and connect first, or set T2U_SERIAL." >&2
    exit 2
  fi
  if [ "$count" -gt 1 ]; then
    echo "config: several adb devices attached; set T2U_SERIAL to one of:" >&2
    printf '%s\n' "$devices" >&2
    exit 2
  fi
  SERIAL="$devices"
}

adb_() {
  resolve_serial
  adb -s "$SERIAL" "$@"
}

# Single-quote a value for the Android shell that `adb shell` hands the command string to.
shq() {
  local s=$1
  printf "'%s'" "${s//\'/\'\\\'\'}"
}

# check FILE: a verdict line, then the keys passed to the app and the keys ignored. Exit 1 on a hard problem.
cmd_check() {
  local file=${1:-}
  if [ -z "$file" ]; then
    echo "config: check needs a file" >&2
    exit 64
  fi
  if [ ! -f "$file" ]; then
    echo "config: not a file: $file" >&2
    exit 1
  fi
  python3 -I -c '
import json, sys
path, limit = sys.argv[1], int(sys.argv[2])
READ = ["include", "exclude", "outputFormat", "startTimeoutMs", "pollIntervalMs",
        "mp3Bitrate", "flacBits", "ytdlpVerbose", "ffmpegLogLevel",
        "prefetch", "gapless", "cacheMb"]
ON_PURPOSE = ["nameSuffix", "nameTemplate"]

def fail(msg):
    print("config check: FAILED - " + msg)
    sys.exit(1)

def show(key):
    return repr(key[:40])

with open(path, "rb") as fh:
    raw = fh.read(limit + 1)
if len(raw) > limit:
    fail("larger than %d bytes (the app ignores such a file)" % limit)
try:
    text = raw.decode("utf-8")
except UnicodeDecodeError:
    fail("not valid UTF-8")
try:
    data = json.loads(text)
except ValueError:
    fail("not valid JSON")
if not isinstance(data, dict):
    fail("not a JSON object")
used = [k for k in data if k in READ]
purpose = [k for k in data if k in ON_PURPOSE]
unknown = [k for k in data if k not in READ and k not in ON_PURPOSE]
print("config check: ok - %d bytes, a JSON object with %d key(s)" % (len(raw), len(data)))
print("  app reads:   " + (", ".join(used) if used else "(none)"))
print("  app ignores on purpose: " + (", ".join(purpose) if purpose else "(none)"))
print("  app ignores (unknown):  " + (", ".join(show(k) for k in unknown) if unknown else "(none)"))
print("  value ranges and patterns are checked by the app; its warnings appear in cast-state and the evidence log")
' "$file" "$MAX_BYTES"
}

# Writes stdin to files/config.json inside the app's files directory (temp file, then rename).
remote_write() {
  adb_ shell "run-as $PKG sh -c $(shq "cat > $REMOTE_TMP && mv $REMOTE_TMP $REMOTE_FILE")"
}

remote_size() {
  adb_ shell "run-as $PKG sh -c $(shq "wc -c < $REMOTE_FILE")" | tr -d '\r ' || true
}

remote_exists() {
  [ "$(adb_ shell "run-as $PKG sh -c $(shq "test -f $REMOTE_FILE && echo present")" 2>/dev/null | tr -d '\r')" = "present" ]
}

cmd_push() {
  local file=${1:-}
  if [ -z "$file" ]; then
    echo "config: push needs a file" >&2
    exit 64
  fi
  cmd_check "$file"
  local bytes
  bytes="$(wc -c <"$file" | tr -d ' ')"
  remote_write <"$file"
  local remote
  remote="$(remote_size)"
  if [ "$remote" != "$bytes" ]; then
    echo "config: wrote $bytes bytes but the phone reports '$remote' for $REMOTE_FILE" >&2
    exit 1
  fi
  echo "config: pushed $bytes bytes to $PKG:$REMOTE_FILE (run 'tools/config.sh reload' to apply)"
}

cmd_show() {
  if remote_exists; then
    adb_ shell "run-as $PKG sh -c $(shq "cat $REMOTE_FILE")" | tr -d '\r'
    echo
  else
    echo "config: no $REMOTE_FILE on the phone (the app uses its defaults)"
  fi
}

cmd_clear() {
  adb_ shell "run-as $PKG sh -c $(shq "rm -f $REMOTE_FILE $REMOTE_TMP")"
  echo "config: removed $REMOTE_FILE (run 'tools/config.sh reload' to apply)"
}

cmd_reload() {
  "$ROOT/tools/spike.sh" cmd reload-config
}

cmd_format() {
  local name=${1:-}
  case " $FORMATS " in
    *" $name "*) ;;
    *)
      echo "config: format must be one of: $FORMATS" >&2
      exit 64
      ;;
  esac
  # NAME was matched against the fixed list above, so it is safe to put into the JSON text.
  printf '{"outputFormat":"%s"}\n' "$name" | remote_write
  echo "config: wrote {\"outputFormat\":\"$name\"} to $PKG:$REMOTE_FILE"
  cmd_reload
}

main() {
  local sub=${1:-}
  case "$sub" in
    '' | -h | --help | help)
      usage
      ;;
    check)
      shift
      cmd_check "$@"
      ;;
    push)
      shift
      cmd_push "$@"
      ;;
    show)
      cmd_show
      ;;
    clear)
      cmd_clear
      ;;
    reload)
      cmd_reload
      ;;
    format)
      shift
      cmd_format "$@"
      ;;
    *)
      echo "config: unknown subcommand '$sub'" >&2
      usage >&2
      exit 64
      ;;
  esac
}

main "$@"
