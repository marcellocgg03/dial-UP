#!/usr/bin/env bash
# tools/speaker-state.sh -- read-only probe of one Sonos: what is it doing and what does it hold (plan 03-14).
#
# Usage:
#   tools/speaker-state.sh --speaker IP
#   tools/speaker-state.sh --help
#
#   --speaker IP   the Sonos to ask. Only RFC1918 addresses are accepted (10/8, 172.16/12, 192.168/16);
#                  anything else exits 2 before any request is sent.
#
# It sends three read-only UPnP actions to port 1400 and nothing else: GetTransportInfo, GetMediaInfo and
# GetPositionInfo. It never loads, plays, pauses, stops, seeks or changes the volume, so it makes no sound
# and cannot clear or interrupt anything.
#
# One line is printed:
#   speaker=<ip> state=<CurrentTransportState> uri=<class> rel=<RelTime> dur=<TrackDuration>
# where <class> says what the speaker has loaded, never the URL itself (it can carry a stream token):
#   empty   no CurrentURI (nothing loaded; what the app leaves behind after a clear)
#   app     the URI points at this app's media server (contains :<port>/stream/)
#   queue   the URI starts with x-rincon-queue (the speaker plays its own queue)
#   other   anything else (a radio station, a library track, another app)
# Exit status: 0 printed a line, 1 the speaker did not answer, 2 bad usage or a refused address.
set -euo pipefail

SPEAKER=""

usage() { sed -n '2,22p' "${BASH_SOURCE[0]}"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --speaker) SPEAKER="${2:?--speaker needs an IP}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "speaker-state.sh: unknown option $1" >&2; usage >&2; exit 2 ;;
  esac
done

# ---- guard (before anything is sent; copied from sonos-play.sh) --------------------------------------

is_private_ipv4() {
  local ip="$1" a b c d
  [[ "$ip" =~ ^([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})$ ]] || return 1
  a="${BASH_REMATCH[1]}"; b="${BASH_REMATCH[2]}"; c="${BASH_REMATCH[3]}"; d="${BASH_REMATCH[4]}"
  for o in "$a" "$b" "$c" "$d"; do [ "$((10#$o))" -le 255 ] || return 1; done
  a=$((10#$a)); b=$((10#$b))
  [ "$a" -eq 10 ] && return 0
  [ "$a" -eq 172 ] && [ "$b" -ge 16 ] && [ "$b" -le 31 ] && return 0
  [ "$a" -eq 192 ] && [ "$b" -eq 168 ] && return 0
  return 1
}
[ -n "$SPEAKER" ] || { echo "speaker-state.sh: --speaker is required" >&2; usage >&2; exit 2; }
if ! is_private_ipv4 "$SPEAKER"; then
  echo "speaker-state.sh: refusing speaker address '$SPEAKER' (only 10.0.0.0/8, 172.16.0.0/12 and 192.168.0.0/16 are allowed)" >&2
  exit 2
fi

# ---- SOAP helpers (copied from sonos-play.sh) ---------------------------------------------------------

AVT_NS="urn:schemas-upnp-org:service:AVTransport:1"
AVT_PATH="/MediaRenderer/AVTransport/Control"

envelope() {
  printf '%s' "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>$1</s:Body></s:Envelope>"
}

tag_val() { tr -d '\n\r' | sed -n "s:.*<$1>\([^<]*\)</$1>.*:\1:p"; }

# avt ACTION -> the raw response on stdout; empty when the speaker does not answer. Only the three
# read-only actions are ever passed in.
avt() {
  local action="$1"
  curl -s -m 8 "http://$SPEAKER:1400$AVT_PATH" \
    -H 'Content-Type: text/xml; charset="utf-8"' \
    -H "SOAPACTION: \"$AVT_NS#$action\"" \
    -d "$(envelope "<u:$action xmlns:u=\"$AVT_NS\"><InstanceID>0</InstanceID></u:$action>")" || true
}

# ---- probe --------------------------------------------------------------------------------------------

transport="$(avt GetTransportInfo)"
state="$(printf '%s' "$transport" | tag_val CurrentTransportState)"
if [ -z "$state" ]; then
  echo "speaker-state.sh: no GetTransportInfo reply from $SPEAKER:1400" >&2
  exit 1
fi

media="$(avt GetMediaInfo)"
position="$(avt GetPositionInfo)"

cur_uri="$(printf '%s' "$media" | tag_val CurrentURI)"
if [ -z "$cur_uri" ]; then
  class="empty"
elif [[ "$cur_uri" == x-rincon-queue* ]]; then
  class="queue"
elif [[ "$cur_uri" =~ :[0-9]+/stream/ ]]; then
  class="app"
else
  class="other"
fi
unset cur_uri

rel="$(printf '%s' "$position" | tag_val RelTime)"
dur="$(printf '%s' "$position" | tag_val TrackDuration)"
echo "speaker=$SPEAKER state=$state uri=$class rel=${rel:--} dur=${dur:--}"
