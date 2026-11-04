#!/usr/bin/env bash
# tools/sonos-play.sh -- make a Sonos play one URL, from the laptop, with curl SOAP (SPK-05 success criterion 5).
#
# Usage:
#   tools/sonos-play.sh --speaker IP --url URL [--mime MIME] [--seconds N] [--volume N] [--title TEXT] [--dry-run]
#   tools/sonos-play.sh --probe --speaker IP        # read-only: GetTransportInfo only, makes no sound
#
#   --speaker IP   the Sonos to play on (required, no default). Only RFC1918 addresses are accepted
#                  (10/8, 172.16/12, 192.168/16); anything else exits 2 before any request is sent.
#   --url URL      what the speaker should fetch, e.g. http://<phone>:8100/stream/<token>.mp3
#   --mime MIME    protocolInfo MIME in the DIDL (default audio/mpeg); try audio/aac, audio/x-aac, audio/mp4
#   --seconds N    how long to poll after Play (default 25)
#   --volume N     Master volume 0-100, set BEFORE any playback (default 15) so test audio stays quiet
#   --title TEXT   DIDL dc:title (default "T2U spike test")
#   --dry-run      print the SOAP bodies, send nothing
#   --probe        send only GetTransportInfo and print CurrentTransportState
#
# Sequence: GetTransportInfo, SetVolume, Stop (a fault is ignored), SetAVTransportURI (URL + DIDL), Play,
# poll GetTransportInfo and GetPositionInfo every 2 s, Stop. Every UPnP fault prints its errorCode and
# errorDescription. Final line:
#   RESULT mime=<mime> soap=<ok|fault:CODE> playing=<yes|no> reltime_advanced=<yes|no> first_playing_s=<seconds|->
# This makes sound: the human is told before it runs (Plan 01-07 checkpoint).
set -euo pipefail

SPEAKER=""
URL=""
MIME="audio/mpeg"
SECS=25
VOLUME=15
TITLE="T2U spike test"
DRY=0
PROBE=0

usage() { sed -n '2,23p' "${BASH_SOURCE[0]}"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --speaker) SPEAKER="${2:?--speaker needs an IP}"; shift 2 ;;
    --url) URL="${2:?--url needs a URL}"; shift 2 ;;
    --mime) MIME="${2:?--mime needs a type}"; shift 2 ;;
    --seconds) SECS="${2:?--seconds needs a number}"; shift 2 ;;
    --volume) VOLUME="${2:?--volume needs a number}"; shift 2 ;;
    --title) TITLE="${2:?--title needs text}"; shift 2 ;;
    --dry-run) DRY=1; shift ;;
    --probe) PROBE=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "sonos-play.sh: unknown option $1" >&2; usage >&2; exit 2 ;;
  esac
done

# ---- guards (before anything is sent) ---------------------------------------------------------------

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
if [ -z "$SPEAKER" ]; then
  echo "sonos-play.sh: --speaker IP is required" >&2
  exit 2
fi
if ! is_private_ipv4 "$SPEAKER"; then
  echo "sonos-play.sh: refusing speaker address '$SPEAKER' (only 10.0.0.0/8, 172.16.0.0/12 and 192.168.0.0/16 are allowed)" >&2
  exit 2
fi
[[ "$SECS" =~ ^[0-9]+$ ]] || { echo "sonos-play.sh: --seconds must be a whole number" >&2; exit 2; }
[[ "$VOLUME" =~ ^[0-9]+$ ]] && [ "$VOLUME" -le 100 ] || { echo "sonos-play.sh: --volume must be 0-100" >&2; exit 2; }
if [ "$PROBE" = 0 ]; then
  [ -n "$URL" ] || { echo "sonos-play.sh: --url is required (or use --probe)" >&2; exit 2; }
  [[ "$URL" =~ ^https?://[^[:space:]\<\>\"]+$ ]] || { echo "sonos-play.sh: --url must be an http(s) URL without spaces" >&2; exit 2; }
  [[ "$MIME" =~ ^[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+$ ]] || { echo "sonos-play.sh: --mime looks wrong: $MIME" >&2; exit 2; }
fi

# ---- SOAP helpers ----------------------------------------------------------------------------------

AVT_NS="urn:schemas-upnp-org:service:AVTransport:1"
RC_NS="urn:schemas-upnp-org:service:RenderingControl:1"
AVT_PATH="/MediaRenderer/AVTransport/Control"
RC_PATH="/MediaRenderer/RenderingControl/Control"

envelope() {
  printf '%s' "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>$1</s:Body></s:Envelope>"
}

xml_esc() { sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' -e 's/"/\&quot;/g' -e "s/'/\&apos;/g"; }
esc() { printf '%s' "$1" | xml_esc; }

tag_val() { tr -d '\n\r' | sed -n "s:.*<$1>\([^<]*\)</$1>.*:\1:p"; }

# soap PATH NAMESPACE ACTION BODY -> response on stdout (also for a UPnP fault, which Sonos sends with HTTP 500)
soap() {
  local path="$1" ns="$2" action="$3" body="$4"
  if [ "$DRY" = 1 ]; then
    echo "--- $action  POST http://$SPEAKER:1400$path  SOAPACTION: \"$ns#$action\"" >&2
    envelope "$body" >&2
    echo >&2
    return 0
  fi
  curl -s -m 8 "http://$SPEAKER:1400$path" \
    -H 'Content-Type: text/xml; charset="utf-8"' \
    -H "SOAPACTION: \"$ns#$action\"" \
    -d "$(envelope "$body")" || true
}

# fault_of RESPONSE -> prints "code" (and the description on stderr) when RESPONSE is a UPnP fault; empty otherwise
fault_of() {
  local resp="$1" code desc
  [ -n "$resp" ] || { echo "noreply"; return; }
  code="$(printf '%s' "$resp" | tag_val errorCode)"
  if [ -n "$code" ]; then
    desc="$(printf '%s' "$resp" | tag_val errorDescription)"
    echo "UPnP fault errorCode=$code errorDescription=${desc:-(none)}" >&2
    echo "$code"
  elif printf '%s' "$resp" | grep -q 'Fault'; then
    echo "SOAP fault without errorCode" >&2
    echo "fault"
  fi
}

get_state() { # prints CurrentTransportState (or empty)
  soap "$AVT_PATH" "$AVT_NS" GetTransportInfo "<u:GetTransportInfo xmlns:u=\"$AVT_NS\"><InstanceID>0</InstanceID></u:GetTransportInfo>" | tag_val CurrentTransportState
}

get_rel() {
  soap "$AVT_PATH" "$AVT_NS" GetPositionInfo "<u:GetPositionInfo xmlns:u=\"$AVT_NS\"><InstanceID>0</InstanceID></u:GetPositionInfo>" | tag_val RelTime
}

hms_to_s() { local t="${1:-}"; [[ "$t" =~ ^([0-9]+):([0-9]{2}):([0-9]{2}) ]] || { echo -1; return; }; echo $((10#${BASH_REMATCH[1]} * 3600 + 10#${BASH_REMATCH[2]} * 60 + 10#${BASH_REMATCH[3]})); }

now_iso() { date -u +%Y-%m-%dT%H:%M:%SZ; }

# ---- read-only probe -------------------------------------------------------------------------------

if [ "$PROBE" = 1 ]; then
  st="$(DRY=0 get_state)"
  if [ -z "$st" ]; then echo "probe: no GetTransportInfo reply from $SPEAKER:1400" >&2; exit 1; fi
  echo "speaker=$SPEAKER state=$st"
  exit 0
fi

# ---- playback --------------------------------------------------------------------------------------

DIDL="<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>$(esc "$TITLE")</dc:title><upnp:class>object.item.audioItem.musicTrack</upnp:class><res protocolInfo=\"$(esc "http-get:*:$MIME:*")\">$(esc "$URL")</res></item></DIDL-Lite>"

SOAP_RESULT="ok"
PLAYED=0
stop_speaker() {
  [ "$PLAYED" = 1 ] || return 0
  PLAYED=0
  soap "$AVT_PATH" "$AVT_NS" Stop "<u:Stop xmlns:u=\"$AVT_NS\"><InstanceID>0</InstanceID></u:Stop>" >/dev/null || true
}
trap stop_speaker EXIT INT TERM

echo "$(now_iso) speaker=$SPEAKER url=$URL mime=$MIME volume=$VOLUME"

st="$(get_state)"
[ "$DRY" = 1 ] || echo "$(now_iso) start state=${st:-no-reply}"

# volume first, always, so the test audio is quiet before any Play
resp="$(soap "$RC_PATH" "$RC_NS" SetVolume "<u:SetVolume xmlns:u=\"$RC_NS\"><InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>$VOLUME</DesiredVolume></u:SetVolume>")"
if [ "$DRY" = 0 ]; then
  f="$(fault_of "$resp")"
  [ -z "$f" ] || echo "$(now_iso) SetVolume failed ($f); playing nothing" >&2
  if [ -n "$f" ]; then
    echo "RESULT mime=$MIME soap=fault:$f playing=no reltime_advanced=no first_playing_s=-"
    exit 1
  fi
fi

# a fault on Stop is expected when nothing plays and is ignored
soap "$AVT_PATH" "$AVT_NS" Stop "<u:Stop xmlns:u=\"$AVT_NS\"><InstanceID>0</InstanceID></u:Stop>" >/dev/null || true

resp="$(soap "$AVT_PATH" "$AVT_NS" SetAVTransportURI "<u:SetAVTransportURI xmlns:u=\"$AVT_NS\"><InstanceID>0</InstanceID><CurrentURI>$(esc "$URL")</CurrentURI><CurrentURIMetaData>$(esc "$DIDL")</CurrentURIMetaData></u:SetAVTransportURI>")"
if [ "$DRY" = 0 ]; then
  f="$(fault_of "$resp")"
  if [ -n "$f" ]; then
    echo "RESULT mime=$MIME soap=fault:$f playing=no reltime_advanced=no first_playing_s=-"
    exit 1
  fi
fi

# #Play starts the audio; SetVolume above always ran first
PLAYED=1
play_at=$SECONDS
resp="$(soap "$AVT_PATH" "$AVT_NS" Play "<u:Play xmlns:u=\"$AVT_NS\"><InstanceID>0</InstanceID><Speed>1</Speed></u:Play>")"
if [ "$DRY" = 0 ]; then
  f="$(fault_of "$resp")"
  [ -z "$f" ] || SOAP_RESULT="fault:$f"
fi

if [ "$DRY" = 1 ]; then
  echo "RESULT mime=$MIME soap=dry-run playing=no reltime_advanced=no first_playing_s=-"
  exit 0
fi

playing=no
first_playing="-"
first_rel=-1
advanced=no
deadline=$((SECONDS + SECS))
while :; do
  st="$(get_state)"
  rel="$(get_rel)"
  echo "$(now_iso) state=${st:-?} rel=${rel:-?}"
  if [ "$st" = "PLAYING" ]; then
    playing=yes
    [ "$first_playing" != "-" ] || first_playing=$((SECONDS - play_at))
    r="$(hms_to_s "$rel")"
    if [ "$r" -ge 0 ]; then
      if [ "$first_rel" -lt 0 ]; then first_rel="$r"; elif [ "$r" -gt "$first_rel" ]; then advanced=yes; fi
    fi
  fi
  [ "$SECONDS" -lt "$deadline" ] || break
  sleep 2
done

stop_speaker
echo "RESULT mime=$MIME soap=$SOAP_RESULT playing=$playing reltime_advanced=$advanced first_playing_s=$first_playing"
