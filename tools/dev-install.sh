#!/usr/bin/env bash
# One command: build the debug APK, install it over (wireless) adb with all runtime permissions
# granted, and open the app so it starts the foreground service. This is the SPK-01 "one command".
#
#   tools/dev-install.sh               # uses T2U_SERIAL, or the only attached device
#   T2U_SERIAL=192.168.1.20:37000 tools/dev-install.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# shellcheck source=tools/env.sh
. "$ROOT/tools/env.sh"

PKG="dev.t2u.spike"
ACTIVITY="$PKG/dev.t2u.app.MainActivity"
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

./gradlew --console=plain :app:assembleDebug

# -g grants POST_NOTIFICATIONS (and every other runtime permission) at install time
adb -s "$SERIAL" install -r -g "$APK"
adb -s "$SERIAL" shell am start -n "$ACTIVITY"

echo "dev-install: installed at $(adb -s "$SERIAL" shell pm path "$PKG" | tr -d '\r')"
