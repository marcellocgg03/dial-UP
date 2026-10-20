#!/usr/bin/env python3
# tools/fake-renderer.py: a laptop stand-in for the Sonos, used for the Phase 1 audible check
# of url-mp3 and url-adts-aac on 2026-10-20 while the author was off the home LAN. It is NOT a
# protocol reference and NOT the Sonos Lamp: it implements only the few AVTransport and
# RenderingControl actions tools/sonos-play.sh sends, and decodes with ffplay on the laptop.
# Usage: python3 tools/fake-renderer.py [port]   (default 1400); then run
#   tools/sonos-play.sh --speaker <laptop-ip> ...
"""Minimal UPnP MediaRenderer for the laptop: just enough SOAP for tools/sonos-play.sh.
AVTransport + RenderingControl on :1400 (same paths as a Sonos), plays the URL with ffplay.
Logs every request (method, UA, Range-less since ffplay fetches) so the phone's media log can be compared."""
import re, subprocess, sys, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 1400
AVT = "urn:schemas-upnp-org:service:AVTransport:1"
RC = "urn:schemas-upnp-org:service:RenderingControl:1"

state = {"uri": "", "mime": "", "proc": None, "t0": None, "volume": 15, "ffplay_out": ""}
lock = threading.Lock()


def env(body):
    return ('<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
            's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>%s</s:Body></s:Envelope>' % body)


def fault(code, desc):
    return env('<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>'
               '<UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>%s</errorCode>'
               '<errorDescription>%s</errorDescription></UPnPError></detail></s:Fault>' % (code, desc))


def playing():
    p = state["proc"]
    return p is not None and p.poll() is None


def rel():
    if state["t0"] is None:
        return "0:00:00"
    s = int(time.time() - state["t0"]) if playing() else 0
    return "%d:%02d:%02d" % (s // 3600, s % 3600 // 60, s % 60)


def stop():
    p = state["proc"]
    if p and p.poll() is None:
        p.terminate()
        try:
            p.wait(3)
        except Exception:
            p.kill()
    state["proc"] = None
    state["t0"] = None


def handle(action, body):
    with lock:
        if action == "GetTransportInfo":
            st = "PLAYING" if playing() else ("STOPPED" if state["proc"] is None else "STOPPED")
            return env('<u:GetTransportInfoResponse xmlns:u="%s"><CurrentTransportState>%s</CurrentTransportState>'
                       '<CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>'
                       '</u:GetTransportInfoResponse>' % (AVT, st))
        if action == "GetPositionInfo":
            return env('<u:GetPositionInfoResponse xmlns:u="%s"><Track>1</Track><TrackDuration>0:00:00</TrackDuration>'
                       '<TrackURI>%s</TrackURI><RelTime>%s</RelTime><AbsTime>%s</AbsTime></u:GetPositionInfoResponse>'
                       % (AVT, state["uri"], rel(), rel()))
        if action == "SetAVTransportURI":
            m = re.search(r"<CurrentURI>([^<]*)</CurrentURI>", body)
            uri = (m.group(1) if m else "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            if not uri.startswith("http://"):
                return fault(714, "Illegal MIME-type") if False else fault(402, "Invalid Args")
            pm = re.search(r"protocolInfo=(?:&quot;|\")http-get:\*:([^:]*):", body)
            state["uri"], state["mime"] = uri, (pm.group(1) if pm else "")
            return env('<u:SetAVTransportURIResponse xmlns:u="%s"/>' % AVT)
        if action == "Play":
            if not state["uri"]:
                return fault(701, "Transition not available")
            stop()
            state["proc"] = subprocess.Popen(
                ["ffplay", "-nodisp", "-autoexit", "-loglevel", "info", "-volume", str(state["volume"]), state["uri"]],
                stdout=open("/tmp/claude-1000/ffplay.log", "ab"), stderr=subprocess.STDOUT)
            state["t0"] = time.time()
            return env('<u:PlayResponse xmlns:u="%s"/>' % AVT)
        if action == "Stop":
            stop()
            return env('<u:StopResponse xmlns:u="%s"/>' % AVT)
        if action == "SetVolume":
            m = re.search(r"<DesiredVolume>(\d+)</DesiredVolume>", body)
            if m:
                state["volume"] = int(m.group(1))
            return env('<u:SetVolumeResponse xmlns:u="%s"/>' % RC)
    return fault(401, "Invalid Action")


class H(BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(n).decode("utf-8", "replace")
        act = (self.headers.get("SOAPACTION", "").strip('"').split("#") + [""])[1]
        out = handle(act, body)
        data = out.encode()
        self.send_response(500 if "<s:Fault>" in out else 200)
        self.send_header("Content-Type", 'text/xml; charset="utf-8"')
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
        print(time.strftime("%H:%M:%S"), act, "uri=%s mime=%s" % (state["uri"][:80], state["mime"]) if act in ("SetAVTransportURI", "Play") else "", flush=True)

    def log_message(self, *a):
        pass


print("fake renderer on :%d" % PORT, flush=True)
ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()
