#!/usr/bin/env bash
# One command: build the debug APK, install it over (wireless) adb with all runtime permissions
# granted, and open the app so it starts the foreground service. This is the SPK-01 "one command".
#
#   tools/dev-install.sh               # uses T2U_SERIAL, or the only attached device
#   T2U_SERIAL=192.168.1.20:37000 tools/dev-install.sh
#   tools/dev-install.sh --uninstall-old   # also remove the old Tube2U spike app (OLD_PKG below)
#
# The old app (see OLD_PKG below) and DialUP would both bind UDP 1900, the DIAL ports and port 8000 and
# double the cast-menu entries, so this script refuses to install next to the old app unless told to
# uninstall it. Uninstalling deletes the old app's data (fresh start, decision D-10).
set -euo pipefail

UNINSTALL_OLD=0
for arg in "$@"; do
  case "$arg" in
    --uninstall-old) UNINSTALL_OLD=1 ;;
    -h|--help) sed -n '2,11p' "$0"; exit 0 ;;
    *) echo "dev-install: unknown argument: $arg" >&2; exit 64 ;;
  esac
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# shellcheck source=tools/env.sh
. "$ROOT/tools/env.sh"

PKG="dev.dialup.debug"   # the debug build; the release build is dev.dialup
OLD_PKG="dev.t2u.spike"
ACTIVITY="$PKG/dev.dialup.app.MainActivity"
APK="app/build/outputs/apk/debug/app-debug.apk"

resolve_serial() {
  if [ -n "${T2U_SERIAL:-}" ]; then
    printf '%s' "$T2U_SERIAL"
    return
  fi
  local devices
  devices="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
  local count
  count="$(printf '%s\n' "$devices" | grep -c . || true)"
  if [ "$count" -eq 0 ]; then
    echo "dev-install: no adb device found. Pair and connect first (adb pair / adb connect), or set T2U_SERIAL." >&2
    exit 2
  fi
  if [ "$count" -gt 1 ]; then
    echo "dev-install: several adb devices attached; set T2U_SERIAL to one of:" >&2
    printf '%s\n' "$devices" >&2
    exit 2
  fi
  printf '%s' "$devices"
}

SERIAL="$(resolve_serial)"
echo "dev-install: device $SERIAL"

if [ -n "$(adb -s "$SERIAL" shell pm path "$OLD_PKG" 2>/dev/null | tr -d '\r')" ]; then
  if [ "$UNINSTALL_OLD" -eq 1 ]; then
    echo "dev-install: removing the old app $OLD_PKG"
    adb -s "$SERIAL" shell pm uninstall "$OLD_PKG"
  else
    echo "dev-install: the old app $OLD_PKG is still installed on $SERIAL." >&2
    echo "  Both apps would fight over UDP 1900, the DIAL ports and port 8000 and double the cast-menu entries." >&2
    echo "  Way out: tools/dev-install.sh --uninstall-old   (removes $OLD_PKG and its data, then installs DialUP)" >&2
    exit 3
  fi
fi

# The release build (dev.dialup) can stay installed, but two running bridges fight over UDP 1900, the DIAL ports and
# port 8000 and double the cast-menu entries. Warn when its bridge service is running; nothing is stopped here.
RELEASE_PKG="dev.dialup"
if adb -s "$SERIAL" shell dumpsys activity services "$RELEASE_PKG" 2>/dev/null | grep -q "BridgeService"; then
  echo "dev-install: WARNING: the release app $RELEASE_PKG has its bridge running on $SERIAL." >&2
  echo "  Turn its bridge off (header switch) before using this debug build, or both advertise every speaker." >&2
fi

./gradlew --console=plain :app:assembleDebug

# -g grants POST_NOTIFICATIONS (and every other runtime permission) at install time
adb -s "$SERIAL" install -r -g "$APK"
adb -s "$SERIAL" shell am start -n "$ACTIVITY"

echo "dev-install: installed at $(adb -s "$SERIAL" shell pm path "$PKG" | tr -d '\r')"
