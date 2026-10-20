// Lounge traffic capture harness (plan 02-02, feeds CAST-08 fixtures and plan 02-09's decoder tests).
//
// Purpose: run the Node yt-cast-receiver 2.1.0 receiver (the lounge library inside the working Node
// Tube2U[PnP] server) against the real YouTube lounge servers and record, exactly as received:
//   http-req      every outgoing HTTP request       {method, url, body}
//   http-res      every non-stream response         {url (no query), status, text}
//   rpc-raw       the RPC stream text, undecoded    {theme, data}  (keeps the "<len>\n" lines)
//   rx-line       what Message.parseIncoming saw    {data}         (diagnostic only)
//   player        every Player operation            {op, ...args}
//   pairing-code  each "Link with TV code"          {code}
//   log           receiver log lines (error/warn/info) {level, msg}
// Records go to out/capture.jsonl (one JSON object per line with t = epoch ms and kind) and the latest
// pairing code to out/pairing-code.txt.
//
// Dependencies: the Node project's own lockfile at commit 5bd3025 (package.json and package-lock.json
// are byte copies of it). Run:
//   npm --prefix tools/lounge-capture ci --ignore-scripts --omit=dev
//   SECS=900 node tools/lounge-capture/capture.mjs
//
// WARNING: out/ holds live lounge tokens, SIDs, screen IDs and pairing codes. It is gitignored.
// Turn it into committable fixtures only through tools/lounge-fixtures.py.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { format } from 'node:util';
import YouTubeCastReceiver, { Player } from 'yt-cast-receiver';
// The package exports map blocks deep imports by name; a relative path to the same file is the same
// module instance the library itself uses, so wrapping its static method affects RPCConnection.
import Message from './node_modules/yt-cast-receiver/dist/lib/app/Message.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const outDir = path.join(here, 'out');
fs.mkdirSync(outDir, { recursive: true });
const out = fs.createWriteStream(path.join(outDir, 'capture.jsonl'), { flags: 'w' });
const rec = (o) => out.write(JSON.stringify({ t: Date.now(), ...o }) + '\n');

const SECS = Number(process.env.SECS ?? 900);

// ---- Message.parseIncoming: diagnostic only (the library's line reader drops empty lines) ----
const origParse = Message.parseIncoming;
Message.parseIncoming = (data) => {
  rec({ kind: 'rx-line', data });
  return origParse.call(Message, data);
};

// ---- fetch wrapper: requests, non-stream responses, and a tee of the RPC stream ----
const origFetch = globalThis.fetch;
const themeOf = (url) => new URL(url).searchParams.get('theme') ?? '';

async function pumpRaw(stream, theme) {
  const dec = new TextDecoder('utf-8');
  const reader = stream.getReader();
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      const data = dec.decode(value, { stream: true });
      if (data) rec({ kind: 'rpc-raw', theme, data });
    }
    const tail = dec.decode();
    if (tail) rec({ kind: 'rpc-raw', theme, data: tail });
  } catch (err) {
    // Aborted or reset streams are normal (the library reconnects); note it and stop.
    rec({ kind: 'log', level: 'info', msg: `rpc tee ended: ${err?.name ?? 'error'}` });
  }
}

globalThis.fetch = async (input, opts = {}) => {
  const url = String(input);
  rec({ kind: 'http-req', method: opts.method ?? 'GET', url, body: opts.body === undefined ? undefined : String(opts.body) });
  const res = await origFetch(input, opts);
  if (url.includes('RID=rpc')) {
    if (!res.body) return res;
    const [forUs, forLibrary] = res.body.tee();
    void pumpRaw(forUs, themeOf(url));
    return new Response(forLibrary, { status: res.status, statusText: res.statusText, headers: res.headers });
  }
  res.clone().text().then(
    // theme lets the sanitizer pair a response with its request when both sessions run at once.
    (text) => rec({ kind: 'http-res', url: url.split('?')[0], status: res.status, theme: themeOf(url), text }),
    () => {},
  );
  return res;
};

// ---- Player: records every operation, pretends to play ----
class CapturePlayer extends Player {
  #volume = { level: 30, muted: false };
  #base = 0; // position (s) at #since
  #since = null; // epoch ms while playing, null while paused/stopped

  #pos() {
    return this.#base + (this.#since === null ? 0 : (Date.now() - this.#since) / 1000);
  }

  async doPlay(video, position) {
    rec({ kind: 'player', op: 'doPlay', videoId: video.id, position });
    this.#base = position || 0;
    this.#since = Date.now();
    return true;
  }

  async doPause() {
    rec({ kind: 'player', op: 'doPause' });
    this.#base = this.#pos();
    this.#since = null;
    return true;
  }

  async doResume() {
    rec({ kind: 'player', op: 'doResume' });
    this.#since = Date.now();
    return true;
  }

  async doStop() {
    rec({ kind: 'player', op: 'doStop' });
    this.#base = 0;
    this.#since = null;
    return true;
  }

  async doSeek(position) {
    rec({ kind: 'player', op: 'doSeek', position });
    this.#base = position;
    if (this.#since !== null) this.#since = Date.now();
    return true;
  }

  async doSetVolume(volume) {
    rec({ kind: 'player', op: 'doSetVolume', level: volume.level, muted: volume.muted });
    this.#volume = { level: volume.level, muted: volume.muted };
    return true;
  }

  async doGetVolume() {
    return { ...this.#volume };
  }

  async doGetPosition() {
    return this.#pos();
  }

  async doGetDuration() {
    return 200;
  }
}

// ---- Logger (yt-cast-receiver Logger interface): error/warn/info are recorded, debug is dropped ----
const fmt = (args) => args.map((a) => (a instanceof Error ? (a.stack ?? a.message) : typeof a === 'string' ? a : format('%o', a))).join(' ');
const logger = {
  error: (...a) => rec({ kind: 'log', level: 'error', msg: fmt(a) }),
  warn: (...a) => rec({ kind: 'log', level: 'warn', msg: fmt(a) }),
  info: (...a) => rec({ kind: 'log', level: 'info', msg: fmt(a) }),
  debug: () => {},
  setLevel: () => {},
};

// ---- Receiver ----
const receiver = new YouTubeCastReceiver(new CapturePlayer(), {
  // Loopback only: no LAN device can reach this DIAL server (threat T-02-07).
  dial: { port: 39999, bindToAddresses: ['127.0.0.1'] },
  device: { name: 'T2U capture', screenName: 'T2U capture', brand: 'T2U', model: 'Capture' },
  dataStore: false,
  logger,
});
receiver.on('error', (err) => rec({ kind: 'log', level: 'error', msg: `receiver error: ${err?.message ?? err}` }));
receiver.on('terminate', (err) => rec({ kind: 'log', level: 'error', msg: `receiver terminated: ${err?.message ?? err}` }));
receiver.on('senderConnect', (s) => rec({ kind: 'log', level: 'info', msg: `senderConnect ${s?.name ?? ''}` }));
receiver.on('senderDisconnect', (s) => rec({ kind: 'log', level: 'info', msg: `senderDisconnect ${s?.name ?? ''}` }));

const pairing = receiver.getPairingCodeRequestService();
let firstCode;
const firstCodeSeen = new Promise((resolve) => { firstCode = resolve; });
pairing.on('response', (code) => {
  fs.writeFileSync(path.join(outDir, 'pairing-code.txt'), `${code}\n`);
  rec({ kind: 'pairing-code', code });
  firstCode(code);
});
pairing.on('error', (err) => rec({ kind: 'log', level: 'error', msg: `pairing code service error: ${err?.message ?? err}` }));

let stopping = false;
async function shutdown(exitCode) {
  if (stopping) return;
  stopping = true;
  try {
    pairing.stop();
    await receiver.stop(); // sends loungeScreenDisconnected on both sessions
  } catch (err) {
    rec({ kind: 'log', level: 'error', msg: `stop failed: ${err?.message ?? err}` });
  }
  out.end(() => process.exit(exitCode));
}

process.on('SIGINT', () => { void shutdown(0); });
process.on('SIGTERM', () => { void shutdown(0); });
process.on('uncaughtException', (err) => {
  rec({ kind: 'log', level: 'error', msg: `uncaughtException: ${err?.stack ?? err}` });
  void shutdown(1);
});
process.on('unhandledRejection', (err) => {
  rec({ kind: 'log', level: 'error', msg: `unhandledRejection: ${err?.stack ?? err}` });
});

await receiver.start();
pairing.start();
await Promise.race([
  firstCodeSeen,
  new Promise((_, reject) => setTimeout(() => reject(new Error('no pairing code within 60 s')), 60000)),
]).catch(async (err) => {
  rec({ kind: 'log', level: 'error', msg: String(err?.message ?? err) });
  console.error(String(err?.message ?? err));
  await shutdown(1);
});
if (!stopping) {
  console.log('READY');
  setTimeout(() => { void shutdown(0); }, SECS * 1000);
}
