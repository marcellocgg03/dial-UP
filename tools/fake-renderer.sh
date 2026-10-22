#!/usr/bin/env bash
# tools/fake-renderer.sh -- a fake UPnP speaker on the LAN for the phone app to discover and cast to (plan 03-26).
#
# Usage:
#   bash tools/fake-renderer.sh [--name TEXT] [--port N] [--bind IP] [--sink LIST] [--play-mimes LIST] [--start-delay-ms N]
#   bash tools/fake-renderer.sh --help
#
# Example (the 12 s start-failure walk: only flac plays, so the app must go direct, mp4, flac):
#   bash tools/fake-renderer.sh --play-mimes audio/x-flac
#
# It accepts the cast, never fetches the stream and plays no sound. It prints one line per control call
# (SetAVTransportURI with the mime, Play, PLAYING reached with the ms since Play, Stop, Seek), never a URL.
# Defaults: bind = the host's first private IPv4, port 49200, name "Fake Test Speaker", PLAYING 800 ms after Play.
# Stop it with Ctrl-C: it sends ssdp:byebye first (a killed runner can linger in the cast menu up to 30 minutes).
#
# Windows host prerequisite (WSL in mirrored mode): Hyper-V firewall rules T2U-SSDP-in (UDP 1900) and
# T2U-fake-http-in (TCP 49200); without them WSL receives no SSDP and the phone cannot reach the fake.
# The first run compiles the fixtures, which takes a while; later runs start in a few seconds.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=tools/env.sh
. "$here/env.sh"
cd "$here/.."

# Every argument is double-quoted for Gradle's --args splitter, so a name with spaces survives.
quoted=""
for a in "$@"; do
  a="${a//\\/\\\\}"
  a="${a//\"/\\\"}"
  quoted+="\"$a\" "
done

# exec: Ctrl-C reaches Gradle directly, which stops the runner and lets it send byebye.
exec ./gradlew --console=plain -q :upnp:runFakeRenderer --args="$quoted"
