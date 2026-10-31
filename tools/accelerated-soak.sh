#!/usr/bin/env bash
# Accelerated soak: samples dev.dialup memory every minute and, every N minutes, fires a
# housekeeping broadcast (and optionally toggles Wi-Fi), for a cast that is already playing.
# Wi-Fi toggling is OFF by default: the YouTube app can end the cast ~10-15 s after Wi-Fi is
# lost even for a 5 s outage (seen 2026-10-29, Session H0), which defeats the soak. The loop runs ON the phone (nohup), so
# the adb drop caused by a Wi-Fi toggle does not stop it. Results are pulled afterwards.
#
#   tools/accelerated-soak.sh start [minutes=45] [every_min=5] [wifi_off_seconds=0 (0 = no toggle)]
#   tools/accelerated-soak.sh pull   [outdir]     # waits for the end, pulls, summarises
#
# Needs ANDROID_SERIAL or .t2u/device. Does not touch the battery state or install anything.
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_SERIAL="${ANDROID_SERIAL:-$(cat .t2u/device)}"
PKG=dev.dialup
DIR=/data/local/tmp/t2u-soak

case "${1:-}" in
start)
  MIN=${2:-45}; EVERY=${3:-5}; OFF=${4:-0}
  adb shell "rm -rf $DIR; mkdir -p $DIR"
  adb shell "cat > $DIR/loop.sh" <<EOF
#!/system/bin/sh
PKG=$PKG; DIR=$DIR; END=\$((\$(date +%s) + $MIN*60)); NEXT=\$((\$(date +%s) + $EVERY*60))
echo "start \$(date +%T) min=$MIN every=$EVERY off=$OFF" > \$DIR/events.txt
echo "ts,pid,pss_kb,java_kb,native_kb,threads,fds" > \$DIR/mem.csv
while [ \$(date +%s) -lt \$END ]; do
  PID=\$(pidof \$PKG)
  if [ -z "\$PID" ]; then echo "\$(date +%T) PROCESS GONE" >> \$DIR/events.txt; else
    M=\$(dumpsys meminfo \$PID)
    PSS=\$(echo "\$M" | sed -n 's/.*TOTAL PSS: *\([0-9]*\).*/\1/p' | head -1)
    JH=\$(echo "\$M" | sed -n 's/^ *Java Heap: *\([0-9]*\).*/\1/p' | head -1)
    NH=\$(echo "\$M" | sed -n 's/^ *Native Heap: *\([0-9]*\).*/\1/p' | head -1)
    TH=\$(ls /proc/\$PID/task | wc -l); FD=\$(ls /proc/\$PID/fd | wc -l)
    echo "\$(date +%T),\$PID,\$PSS,\$JH,\$NH,\$TH,\$FD" >> \$DIR/mem.csv
  fi
  if [ \$(date +%s) -ge \$NEXT ]; then
    if [ $OFF -gt 0 ]; then
      echo "\$(date +%T) wifi off" >> \$DIR/events.txt; svc wifi disable; sleep $OFF
      svc wifi enable; echo "\$(date +%T) wifi on" >> \$DIR/events.txt
    fi
    am broadcast -n \$PKG/dev.dialup.app.CommandReceiver -a dev.dialup.CMD --es cmd housekeeping >/dev/null 2>&1
    echo "\$(date +%T) housekeeping" >> \$DIR/events.txt
    NEXT=\$((\$(date +%s) + $EVERY*60))
  fi
  sleep 60
done
echo "end \$(date +%T)" >> \$DIR/events.txt; touch \$DIR/done
EOF
  adb shell "nohup sh $DIR/loop.sh >$DIR/out.txt 2>&1 &"
  sleep 3; adb shell "cat $DIR/events.txt; cat $DIR/mem.csv"
  ;;
pull)
  OUT=${2:-.planning/phases/05-unattended-background-running/evidence/soak-h0}
  mkdir -p "$OUT"
  until adb shell "test -e $DIR/done" 2>/dev/null; do sleep 30
    adb connect "$(adb mdns services 2>/dev/null | awk '/tls-connect/{print $NF}' | head -1)" >/dev/null 2>&1 || true
  done
  for f in events.txt mem.csv out.txt; do adb pull "$DIR/$f" "$OUT/" >/dev/null; done
  adb logcat -d --pid="$(adb shell pidof $PKG)" -s T2U > "$OUT/t2u-log.txt" || true
  echo "pulled to $OUT"; cat "$OUT/events.txt"
  python3 - "$OUT/mem.csv" <<'PY'
import csv, sys
r = list(csv.DictReader(open(sys.argv[1])))
for k in ("pss_kb", "java_kb", "native_kb", "threads", "fds"):
    v = [int(x[k]) for x in r if x[k]]
    if v: print(f"{k:10} first={v[0]} last={v[-1]} min={min(v)} max={max(v)} delta={v[-1]-v[0]}")
PY
  ;;
*) echo "usage: $0 start [min] [every] [off] | pull [outdir]"; exit 2;;
esac
