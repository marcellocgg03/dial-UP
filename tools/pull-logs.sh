#!/usr/bin/env bash
# Copies the rotating event log (spike-events.jsonl, .1, .2) from the phone into DEST with plain adb.
# The log lives in the app external files directory, which adb shell can read without run-as
# (decision D-08). If the app fell back to the internal directory, use `tools/spike.sh pull`.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=tools/env.sh
. "$ROOT/tools/env.sh"

PKG="dev.dialup"
REMOTE="/sdcard/Android/data/$PKG/files/logs"

DEST=${1:-}
if [ -z "$DEST" ] || [ "$DEST" = "--help" ] || [ "$DEST" = "-h" ]; then
  echo "Usage: tools/pull-logs.sh DEST" >&2
  echo "  copies $REMOTE/ into DEST and masks MAC-shaped strings" >&2
  exit 64
fi

SERIAL=""
if [ -n "${T2U_SERIAL:-}" ]; then
  SERIAL="$T2U_SERIAL"
else
  devices="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
  count="$(printf '%s\n' "$devices" | grep -c . || true)"
  if [ "$count" -ne 1 ]; then
    echo "pull-logs: need exactly one adb device (found $count); set T2U_SERIAL." >&2
    exit 2
  fi
  SERIAL="$devices"
fi

# `adb shell` keeps the remote exit status poorly across versions, so read the answer from output.
if [ "$(adb -s "$SERIAL" shell "test -d '$REMOTE' && echo present" 2>/dev/null | tr -d '\r')" != "present" ]; then
  echo "pull-logs: $REMOTE not found; the app may be using the internal fallback, try tools/spike.sh pull DEST" >&2
  exit 1
fi

mkdir -p "$DEST"
adb -s "$SERIAL" pull "$REMOTE/." "$DEST/" >/dev/null

# Other LAN devices embed their MAC address in SSDP USN values; mask MAC-shaped strings right
# after the pull, as tools/spike.sh pull does (threat T-01-20).
shopt -s nullglob
files=("$DEST"/*.jsonl*)
if [ "${#files[@]}" -gt 0 ]; then
  sed -i -E 's/([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}/xx:xx:xx:xx:xx:xx/g' "${files[@]}"
fi
echo "pull-logs: ${#files[@]} file(s) into $DEST"
