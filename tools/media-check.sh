#!/usr/bin/env bash
# tools/media-check.sh -- curl acceptance checks for the Phase 1 media server (SPK-05).
#
# Usage:
#   tools/media-check.sh BASE_URL TOKENS_JSON [--orphan-cmd CMD]
#   tools/media-check.sh --local
#
# BASE_URL     e.g. http://192.168.50.239:8100 (the phone) or http://127.0.0.1:8100
# TOKENS_JSON  files/evidence/media-tokens-<input>.json pulled with `tools/spike.sh pull`, or the local one:
#              {"base":..., "tokens":[{"label","path","format","mime","kind":"file"|"pipe"}, ...]}
# --orphan-cmd CMD   run 3 s after the last pipe request; it must print 0 (live ffmpeg processes), e.g.
#                    --orphan-cmd "adb shell ps -A | grep -c libffmpeg || true"
# --local            starts `./gradlew :media:serveLocal` (the JVM server with /usr/bin/ffmpeg), runs every check
#                    against http://127.0.0.1:8100 with --orphan-cmd "pgrep -c -x ffmpeg || true", then stops it.
#
# File tokens: HEAD (200, Content-Length, Accept-Ranges, DLNA headers), Range 0-99 (206), suffix -100, 416 beyond
# the end, one Content-Length per response, frame sync of ADTS and MP3 files.
# Pipe tokens: HEAD without Content-Length; GET with --max-time 3 shows Transfer-Encoding: chunked and an ADTS or
# MP3 frame start (curl exit 28, a timeout on an endless stream, is expected). An unknown token is 404.
# One PASS or FAIL line per check, then "ALL CHECKS PASSED" (exit 0) or "CHECKS FAILED: N" (exit 1).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/.." && pwd)"

BASE=""
TOKENS=""
ORPHAN_CMD=""
LOCAL=0
while [ $# -gt 0 ]; do
  case "$1" in
    --local) LOCAL=1; shift ;;
    --orphan-cmd) ORPHAN_CMD="${2:?--orphan-cmd needs a command}"; shift 2 ;;
    -h|--help) sed -n '2,22p' "${BASH_SOURCE[0]}"; exit 0 ;;
    -*) echo "media-check.sh: unknown option $1" >&2; exit 2 ;;
    *) if [ -z "$BASE" ]; then BASE="$1"; elif [ -z "$TOKENS" ]; then TOKENS="$1"; else echo "media-check.sh: too many arguments" >&2; exit 2; fi; shift ;;
  esac
done

work="$(mktemp -d)"
server_pid=""
cleanup() {
  if [ -n "$server_pid" ]; then
    kill "$server_pid" 2>/dev/null || true
    pkill -f 'dev.t2u.media.LocalServeMainKt' 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
  rm -rf "$work"
}
trap cleanup EXIT

if [ "$LOCAL" = 1 ]; then
  [ -z "$BASE" ] || { echo "media-check.sh: --local takes no BASE_URL" >&2; exit 2; }
  command -v ffmpeg >/dev/null || { echo "media-check.sh: --local needs /usr/bin/ffmpeg" >&2; exit 2; }
  # shellcheck disable=SC1091
  . "$here/env.sh"
  TOKENS="$repo/media/build/media-local/tokens.json"
  rm -f "$TOKENS"
  ( cd "$repo" && exec ./gradlew -q :media:serveLocal ) >"$work/serve.log" 2>&1 &
  server_pid=$!
  waited=0
  while [ ! -s "$TOKENS" ]; do
    if ! kill -0 "$server_pid" 2>/dev/null; then echo "FAIL local server exited early:" >&2; cat "$work/serve.log" >&2; exit 1; fi
    if [ "$waited" -ge 120 ]; then echo "FAIL local server not ready after 120 s" >&2; cat "$work/serve.log" >&2; exit 1; fi
    sleep 1
    waited=$((waited + 1))
  done
  BASE="http://127.0.0.1:8100"
  ORPHAN_CMD="pgrep -c -x ffmpeg || true"
fi

[ -n "$BASE" ] && [ -n "$TOKENS" ] || { echo "usage: media-check.sh BASE_URL TOKENS_JSON [--orphan-cmd CMD] | --local" >&2; exit 2; }
[ -s "$TOKENS" ] || { echo "media-check.sh: tokens file not found: $TOKENS" >&2; exit 2; }
BASE="${BASE%/}"

fails=0
pass() { echo "PASS $*"; }
fail() { echo "FAIL $*"; fails=$((fails + 1)); }
check() { # check "description" status(0 = pass)
  if [ "$2" = 0 ]; then pass "$1"; else fail "$1"; fi
}

# ---- helpers -------------------------------------------------------------------------------------

status_of() { awk 'NR==1 {print $2}' "$1" | tr -d '\r'; }
header_of() { grep -i "^$2:" "$1" | tail -n 1 | cut -d: -f2- | tr -d '\r' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' || true; }
count_header() { grep -ic "^$2:" "$1" || true; }

# head URL -> $work/h (headers of the last response)
do_head() { curl -sS --head -D "$work/h" -o /dev/null --max-time 10 "$1"; }
# get URL [range] -> $work/h, $work/b ; returns curl's exit code in $RC
do_get() {
  local url="$1" range="${2:-}" max="${3:-15}"
  RC=0
  if [ -n "$range" ]; then
    curl -sS -D "$work/h" -o "$work/b" --max-time "$max" -H "Range: $range" "$url" || RC=$?
  else
    curl -sS -D "$work/h" -o "$work/b" --max-time "$max" "$url" || RC=$?
  fi
}
size_of() { wc -c <"$1" | tr -d ' '; }

# sync_ok KIND FILE: KIND adts -> FF Fx with (b1 & 0xF6) == 0xF0; KIND mp3 -> "ID3" or FF with top three bits of b1 set
sync_ok() {
  python3 -I - "$1" "$2" <<'PY'
import sys
kind, path = sys.argv[1], sys.argv[2]
b = open(path, 'rb').read(16)
if kind == 'adts':
    ok = len(b) >= 2 and b[0] == 0xFF and (b[1] & 0xF6) == 0xF0
elif kind == 'mp3':
    ok = b[:3] == b'ID3' or (len(b) >= 2 and b[0] == 0xFF and (b[1] & 0xE0) == 0xE0)
else:
    ok = False
sys.exit(0 if ok else 1)
PY
}

# ---- tokens ----------------------------------------------------------------------------------------

python3 -I - "$TOKENS" >"$work/tokens.tsv" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
for t in d.get("tokens", []):
    label, path, fmt = t.get("label", ""), t.get("path", ""), t.get("format", "")
    kind = t.get("kind") or ("file" if label.endswith("-file") else "pipe")
    print("\t".join([label, path, fmt, t.get("mime", ""), kind]))
PY
[ -s "$work/tokens.tsv" ] || { echo "FAIL no tokens in $TOKENS"; exit 1; }
echo "checking $BASE with $(wc -l <"$work/tokens.tsv" | tr -d ' ') tokens from $TOKENS"

last_ext="mp3"
pipe_seen=0
while IFS=$'\t' read -r label path fmt mime kind; do
  url="$BASE$path"
  last_ext="${path##*.}"
  if [ "$kind" = file ]; then
    # HEAD
    if do_head "$url"; then
      st="$(status_of "$work/h")"
      size="$(header_of "$work/h" Content-Length)"
      check "$label HEAD 200" "$([ "$st" = 200 ] && echo 0 || echo 1)"
      check "$label HEAD Content-Length present (${size:-none})" "$([ -n "$size" ] && echo 0 || echo 1)"
      check "$label HEAD exactly one Content-Length" "$([ "$(count_header "$work/h" Content-Length)" = 1 ] && echo 0 || echo 1)"
      check "$label HEAD Accept-Ranges: bytes" "$([ "$(header_of "$work/h" Accept-Ranges)" = bytes ] && echo 0 || echo 1)"
      check "$label HEAD transferMode.dlna.org" "$([ -n "$(header_of "$work/h" transferMode.dlna.org)" ] && echo 0 || echo 1)"
      check "$label HEAD contentFeatures.dlna.org" "$([ -n "$(header_of "$work/h" contentFeatures.dlna.org)" ] && echo 0 || echo 1)"
      check "$label HEAD Content-Type $mime" "$([ "$(header_of "$work/h" Content-Type)" = "$mime" ] && echo 0 || echo 1)"
    else
      fail "$label HEAD request failed"
      continue
    fi
    [ -n "${size:-}" ] || continue
    # Range 0-99
    do_get "$url" "bytes=0-99"
    st="$(status_of "$work/h")"
    check "$label Range 0-99 -> 206" "$([ "$st" = 206 ] && echo 0 || echo 1)"
    check "$label Range 0-99 Content-Range bytes 0-99/$size" "$([ "$(header_of "$work/h" Content-Range)" = "bytes 0-99/$size" ] && echo 0 || echo 1)"
    check "$label Range 0-99 body is 100 bytes" "$([ "$(size_of "$work/b")" = 100 ] && echo 0 || echo 1)"
    check "$label Range 0-99 exactly one Content-Length" "$([ "$(count_header "$work/h" Content-Length)" = 1 ] && echo 0 || echo 1)"
    # frame sync of ADTS / MP3 files (first bytes of the file)
    case "$fmt" in
      adts|mp3)
        do_get "$url" "bytes=0-15"
        check "$label first bytes are a $fmt frame sync" "$(sync_ok "$fmt" "$work/b" && echo 0 || echo 1)"
        ;;
    esac
    # suffix range
    do_get "$url" "bytes=-100"
    st="$(status_of "$work/h")"
    check "$label Range -100 -> 206" "$([ "$st" = 206 ] && echo 0 || echo 1)"
    check "$label Range -100 body is 100 bytes" "$([ "$(size_of "$work/b")" = 100 ] && echo 0 || echo 1)"
    # beyond the end
    do_get "$url" "bytes=999999999-"
    st="$(status_of "$work/h")"
    check "$label Range 999999999- -> 416" "$([ "$st" = 416 ] && echo 0 || echo 1)"
    check "$label 416 Content-Range bytes */$size" "$([ "$(header_of "$work/h" Content-Range)" = "bytes */$size" ] && echo 0 || echo 1)"
  else
    # pipe token
    if do_head "$url"; then
      st="$(status_of "$work/h")"
      check "$label HEAD 200" "$([ "$st" = 200 ] && echo 0 || echo 1)"
      check "$label HEAD has no Content-Length" "$([ "$(count_header "$work/h" Content-Length)" = 0 ] && echo 0 || echo 1)"
      check "$label HEAD contentFeatures.dlna.org" "$([ -n "$(header_of "$work/h" contentFeatures.dlna.org)" ] && echo 0 || echo 1)"
    else
      fail "$label HEAD request failed"
    fi
    do_get "$url" "" 3
    pipe_seen=1
    st="$(status_of "$work/h")"
    check "$label GET 200 (curl exit $RC; 28 = timeout on an endless stream)" "$([ "$st" = 200 ] && { [ "$RC" = 0 ] || [ "$RC" = 28 ]; } && echo 0 || echo 1)"
    te="$(header_of "$work/h" Transfer-Encoding)"
    check "$label Transfer-Encoding: chunked (${te:-none})" "$(printf '%s' "$te" | grep -qi chunked && echo 0 || echo 1)"
    check "$label no Content-Length" "$([ "$(count_header "$work/h" Content-Length)" = 0 ] && echo 0 || echo 1)"
    case "$fmt" in
      adts|mp3) check "$label first bytes are a $fmt frame sync" "$(sync_ok "$fmt" "$work/b" && echo 0 || echo 1)" ;;
    esac
  fi
done <"$work/tokens.tsv"

# unknown token -> 404
rand="$(python3 -I -c 'import secrets;print(secrets.token_hex(16))')"
curl -sS -o /dev/null -D "$work/h" --max-time 10 "$BASE/stream/$rand.$last_ext" || true
check "unregistered token -> 404" "$([ "$(status_of "$work/h")" = 404 ] && echo 0 || echo 1)"
curl -sS -o /dev/null -D "$work/h" --max-time 10 "$BASE/stream/notatoken.$last_ext" || true
check "malformed token -> 404" "$([ "$(status_of "$work/h")" = 404 ] && echo 0 || echo 1)"
curl -sS -o /dev/null -D "$work/h" --max-time 10 -X POST "$BASE$(cut -f2 "$work/tokens.tsv" | head -n 1)" || true
check "POST on a registered token -> 404" "$([ "$(status_of "$work/h")" = 404 ] && echo 0 || echo 1)"

# no orphan ffmpeg after the pipe requests
if [ -n "$ORPHAN_CMD" ]; then
  sleep 3
  n="$(bash -c "$ORPHAN_CMD" 2>/dev/null | tail -n 1 | tr -d '[:space:]')"
  [ "$pipe_seen" = 1 ] || echo "note: no pipe tokens were requested, the orphan check is vacuous"
  check "no orphan ffmpeg 3 s after the last pipe request (count ${n:-?})" "$([ "${n:-x}" = 0 ] && echo 0 || echo 1)"
fi

if [ "$fails" = 0 ]; then
  echo "ALL CHECKS PASSED"
  exit 0
fi
echo "CHECKS FAILED: $fails"
exit 1
