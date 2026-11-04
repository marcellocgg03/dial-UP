#!/usr/bin/env bash
# adb wrapper for the Phase 1 spike. The adb command channel and the diagnostics exist in debug builds only
# (CR-02): install app-debug.apk to use this script; a release APK has no CommandReceiver.
# Per decision D-02 there is no GUI: this script is the only
# way spike actions are triggered. Commands reach the running service through the DUMP-protected
# CommandReceiver (`am broadcast` is ordered, so the reply comes back as the result data).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=tools/env.sh
. "$ROOT/tools/env.sh"

PKG="dev.dialup"
RECEIVER="$PKG/dev.dialup.app.CommandReceiver"
ACTIVITY="$PKG/dev.dialup.app.MainActivity"
EVIDENCE_REL="files/evidence"

usage() {
  cat <<'EOF'
Usage: tools/spike.sh <subcommand> [args]

Subcommands:
  cmd NAME [key=value ...]   send a command to the running service and print its JSON reply
  dump-state                 one flat line: mcast=<bool> wifi=<bool> wake=<bool> ip=<ip> idle=<bool> interactive=<bool>
  wait FILE [timeoutSec]     poll (every 5 s, default 900 s) until files/evidence/FILE exists; exit 1 on timeout
  pull DEST                  copy files/evidence/* (the event log dialup-events.jsonl* included) and the T2U logcat into DEST
  open                       start the app, which starts the foreground service
  ip                         print the phone's Wi-Fi IPv4 address
  facts                      print phone model, OS and YouTube app versions
  --help                     show this list

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
    echo "spike: no adb device found. Pair and connect first, or set T2U_SERIAL." >&2
    exit 2
  fi
  if [ "$count" -gt 1 ]; then
    echo "spike: several adb devices attached; set T2U_SERIAL to one of:" >&2
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

safe_name() {
  case "$1" in
    '' | */* | *..* | *[!A-Za-z0-9._-]*)
      echo "spike: unsafe file name: $1" >&2
      exit 64
      ;;
  esac
}

cmd_send() {
  local name=${1:-}
  if [ -z "$name" ]; then
    echo "spike: cmd needs a command name" >&2
    exit 64
  fi
  shift
  local remote
  remote="am broadcast -n $(shq "$RECEIVER") -a dev.dialup.CMD --es cmd $(shq "$name")"
  local kv
  for kv in "$@"; do
    case "$kv" in
      ?*=*) ;;
      *)
        echo "spike: bad argument '$kv' (want key=value)" >&2
        exit 64
        ;;
    esac
    remote="$remote --es $(shq "${kv%%=*}") $(shq "${kv#*=}")"
  done

  local out line data
  out="$(adb_ shell "$remote" | tr -d '\r')"
  line="$(printf '%s\n' "$out" | grep -a 'Broadcast completed' | head -n 1 || true)"
  if [ -z "$line" ]; then
    echo "spike: no 'Broadcast completed' line from am broadcast:" >&2
    printf '%s\n' "$out" >&2
    exit 1
  fi
  case "$line" in
    *'data="'*)
      data="${line#*data=\"}"
      data="${data%\"}"
      printf '%s\n' "$data"
      ;;
    *)
      echo "spike: broadcast completed without a reply (receiver refused or not reachable): $line" >&2
      printf '%s\n' "$out" >&2
      exit 1
      ;;
  esac
}

state_json() {
  cmd_send dump-state
}

cmd_dump_state() {
  local json
  json="$(state_json)"
  printf '%s' "$json" | python3 -I -c '
import json, sys
d = json.load(sys.stdin)
if "error" in d:
    print("error=" + str(d["error"]))
    sys.exit(1)
def b(key):
    return str(bool(d.get(key))).lower()
print("mcast=%s wifi=%s wake=%s ip=%s idle=%s interactive=%s" % (
    b("mcast"), b("wifi"), b("wake"), d.get("ip") or "none", b("idle"), b("interactive")))
'
}

cmd_ip() {
  local ip
  ip="$(cmd_dump_state | tr ' ' '\n' | sed -n 's/^ip=//p')"
  if [ -z "$ip" ] || [ "$ip" = "none" ]; then
    echo "spike: the phone reports no Wi-Fi IPv4 address" >&2
    exit 1
  fi
  printf '%s\n' "$ip"
}

cmd_wait() {
  local file=${1:-}
  local timeout=${2:-900}
  safe_name "$file"
  local start=$SECONDS
  while :; do
    # `adb exec-out` does not propagate the remote exit status (adb 37: `test -f` of a missing file
    # still exits 0), so the answer is read from the output of `adb shell`.
    if [ "$(adb_ shell "run-as $PKG test -f '$EVIDENCE_REL/$file' && echo present" 2>/dev/null | tr -d '\r')" = "present" ]; then
      return 0
    fi
    if [ $((SECONDS - start)) -ge "$timeout" ]; then
      echo "spike: timed out after ${timeout}s waiting for $file" >&2
      exit 1
    fi
    sleep 5
  done
}

cmd_pull() {
  local dest=${1:-}
  if [ -z "$dest" ]; then
    echo "spike: pull needs a destination directory" >&2
    exit 64
  fi
  mkdir -p "$dest"
  local names name
  names="$(adb_ exec-out run-as "$PKG" ls -1 "$EVIDENCE_REL" | tr -d '\r')"
  while IFS= read -r name; do
    [ -n "$name" ] || continue
    safe_name "$name"
    adb_ exec-out run-as "$PKG" cat "$EVIDENCE_REL/$name" >"$dest/$name"
  done <<<"$names"
  adb_ logcat -d -v threadtime -s 'T2U:*' >"$dest/logcat-T2U.txt"
  # the event log (dialup-events.jsonl, .1, .2) is app-private and part of files/evidence, so the loop above copied it
  # Other LAN devices embed their MAC address in SSDP USN values (uuid:aa:bb:cc:dd:ee:ff::...); the
  # evidence is committed, so MAC-shaped strings are masked right after the pull (threat T-01-20).
  sed -i -E 's/([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}/xx:xx:xx:xx:xx:xx/g' "$dest/logcat-T2U.txt" "$dest"/*.jsonl* "$dest"/*.json 2>/dev/null || true
  echo "spike: pulled $(printf '%s\n' "$names" | grep -c . || true) evidence file(s) and logcat into $dest"
}

cmd_open() {
  adb_ shell am start -n "$ACTIVITY"
}

cmd_facts() {
  local prop
  for prop in ro.product.model ro.product.marketname ro.build.version.release ro.build.version.sdk \
    ro.build.version.oneui ro.build.version.security_patch; do
    printf '%s=%s\n' "$prop" "$(adb_ shell getprop "$prop" | tr -d '\r')"
  done
  local pkg version
  for pkg in com.google.android.youtube com.google.android.apps.youtube.music; do
    version="$(adb_ shell dumpsys package "$pkg" | tr -d '\r' | sed -n 's/^ *versionName=//p' | head -n 1)"
    printf '%s versionName=%s\n' "$pkg" "${version:-not-installed}"
  done
}

main() {
  local sub=${1:-}
  case "$sub" in
    '' | -h | --help | help)
      usage
      ;;
    cmd)
      shift
      cmd_send "$@"
      ;;
    dump-state)
      cmd_dump_state
      ;;
    wait)
      shift
      cmd_wait "$@"
      ;;
    pull)
      shift
      cmd_pull "$@"
      ;;
    open)
      cmd_open
      ;;
    ip)
      cmd_ip
      ;;
    facts)
      cmd_facts
      ;;
    *)
      echo "spike: unknown subcommand '$sub'" >&2
      usage >&2
      exit 64
      ;;
  esac
}

main "$@"
