/*
 * The same job MainActivity's shouldInterceptRequest does on Android: the page
 * cannot ask another server for anything itself, so it asks this instead and
 * this asks on its behalf.
 *
 * It listens only on 127.0.0.1, on whatever port the machine gives it, and it
 * answers one shape of request: /p?u=<the real address>.
 */
const http = require("http");
const https = require("https");

/* Many panels only answer to VLC... */
const UA = "VLC/3.0.20 LibVLC/3.0.20";
/* ...and a panel behind Cloudflare blocks exactly that, so a browser is next. */
const BROWSER_UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
  "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

const HEAD = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "*",
  "Access-Control-Allow-Methods": "GET,OPTIONS",
  "Cache-Control": "no-store",
  "Content-Type": "text/plain; charset=utf-8"
};

const CONNECT_MS = 30000;
const READ_MS = 40000;

async function once(target, agent, signal) {
  return fetch(target, {
    redirect: "follow",                 /* http -> https included */
    signal,
    headers: {
      "User-Agent": agent,
      "Accept": "*/*",
      "Accept-Language": "en-US,en;q=0.9"
    }
  });
}

/** VLC first, a browser second - exactly the order the Android app uses. */
async function fetchAllowingCloudflare(target) {
  const kill = AbortSignal.timeout(CONNECT_MS + READ_MS);
  let r = await once(target, UA, kill);
  if (r.status === 403 || r.status === 406 || r.status === 503) {
    try { await r.body?.cancel(); } catch (e) { /* nothing was read yet */ }
    r = await once(target, BROWSER_UA, kill);
  }
  return r;
}

/**
 * Make every address in a stream's list a whole address.
 *
 * A panel answers a channel by sending the player somewhere else - twice, for
 * some channels: first to https, then to whichever machine actually carries
 * the stream. The list that finally comes back names its parts in short form
 * ("tracks-v1a1/mono.m3u8"), and those parts hang off the address the list
 * came from, NOT the address that was first asked for. The player on Windows
 * fetches through its own web code, which does not pass the final address on,
 * so it looks for every part back at the panel and finds nothing.
 *
 * Writing the parts out in full, against the address the list really came
 * from, ends the whole question: there is nothing left to resolve.
 */
function inFull(text, base) {
  const whole = u => { try { return new URL(u, base).toString() } catch (e) { return u } };
  return text.split(/\r?\n/).map(line => {
    const t = line.trim();
    if (!t) return line;
    /* a key, a sound track, a starting piece: the address sits in URI="..." */
    if (t.charAt(0) === "#") return line.replace(/URI="([^"]+)"/g, (m, u) => 'URI="' + whole(u) + '"');
    return whole(t);
  }).join("\n");
}

/* ------------------------------------------------------------------------------
 * Streams: the same ways of asking the phone app uses.
 *
 * A link that plays on the phone but not here was one of these: the server
 * only answers some agents; its name is blocked by the internet provider's
 * DNS (the phone looks it up over https, so it still gets through); or its
 * certificate has run out. Each way below fixes one of those. The way that
 * worked is remembered per server, so the next request there goes straight
 * to it, and everything of that stream that mpv would fetch itself - the
 * pieces of a list, a film's file - comes through here too, asked the same
 * way.
 * ---------------------------------------------------------------------------- */
const SMARTERS_UA = "IPTVSmartersPlayer";
const PLAIN = { ua: UA, doh: false, insecure: false };
const WAYS = [
  PLAIN,
  /* A certificate that is not quite in order - one sent without the
     certificate in the middle, or one that has run out - is the commonest
     reason a film plays in VLC and not here. mpv itself (FFmpeg) does not
     check certificates at all; this asks the same way, at once, and only
     after a certificate was refused. A block page found this way is still
     a web page, not a stream, and is turned away below. */
  { ua: UA, doh: false, insecure: true, sslOnly: true },
  { ua: BROWSER_UA, doh: false, insecure: false },
  { ua: UA, doh: true, insecure: false },
  { ua: SMARTERS_UA, doh: false, insecure: false },
  { ua: BROWSER_UA, doh: true, insecure: false },
  { ua: UA, doh: true, insecure: true },
  { ua: BROWSER_UA, doh: true, insecure: true }
];
/* answers that mean "not like that", rather than "not there" */
const REFUSED = new Set([401, 403, 406, 429, 451, 456, 503]);
const HOP_MS = 9000;          /* one way's chance to connect */
const WAYS_MS = 16000;        /* all of them together - mpv gives up at 20 */

const wayKey = w => w.ua + "|" + w.doh + "|" + w.insecure + "|" + !!w.sslOnly;
const isPlain = w => !w || (w.ua === UA && !w.doh && !w.insecure);
const goodWay = new Map();    /* host -> the way that worked there */

function hostOf(u) { try { return new URL(u).host } catch (e) { return "" } }

/** what kind of failure this was - it decides which ways are worth trying */
function whyOf(err) {
  const code = String((err && (err.code || (err.cause && err.cause.code))) || "");
  const msg = String((err && err.message) || "");
  if (/ENOTFOUND|EAI_AGAIN|ENODATA/.test(code)) return "dns";
  if (/CERT|SSL|TLS|SELF_SIGNED|UNABLE_TO_VERIFY|ALTNAME|DEPTH_ZERO/i.test(code) || /certificate/i.test(msg)) return "ssl";
  if (/ECONNREFUSED|ETIMEDOUT|EHOSTUNREACH|ENETUNREACH/.test(code)) return "connect";
  return "dropped";
}
function helps(w, why) {
  if (w.sslOnly) return why === "ssl";
  if (why === "dns" || why === "connect") return w.doh;
  if (why === "ssl") return w.doh || w.insecure;
  return true;
}

/* the name looked up over https - Cloudflare first, then Google - asked by
   address, so the provider's DNS is never asked at all */
const dohCache = new Map();
function dohJson(ip, servername, pathq) {
  return new Promise((ok, no) => {
    const r = https.get({ host: ip, servername, path: pathq, headers: { accept: "application/dns-json" } }, resp => {
      let s = "";
      resp.setEncoding("utf8");
      resp.on("data", d => { s += d });
      resp.on("end", () => { try { ok(JSON.parse(s)) } catch (e) { no(e) } });
    });
    r.setTimeout(6000, () => r.destroy(new Error("doh timeout")));
    r.on("error", no);
  });
}
async function dohResolve(host) {
  const hit = dohCache.get(host);
  if (hit && hit.until > Date.now()) return hit.ip;
  const q = "?name=" + encodeURIComponent(host) + "&type=A";
  for (const [ip, sn, p] of [["1.1.1.1", "cloudflare-dns.com", "/dns-query"], ["8.8.8.8", "dns.google", "/resolve"]]) {
    try {
      const j = await dohJson(ip, sn, p + q);
      const a = (j.Answer || []).filter(x => x.type === 1).map(x => x.data);
      if (a.length) { dohCache.set(host, { ip: a[0], until: Date.now() + 600000 }); return a[0] }
    } catch (e) { /* the next one */ }
  }
  throw Object.assign(new Error("secure DNS found nothing for " + host), { code: "ENOTFOUND" });
}
/* secure DNS first; the machine's own DNS when secure DNS cannot be reached
   at all (some networks block it) - as the phone does */
const dns = require("dns");
function dohOrSystem(host) {
  return dohResolve(host).catch(() => new Promise((ok, no) =>
    dns.lookup(host, { family: 4 }, (e, ip) => e ? no(e) : ok(ip))));
}
function dohLookup(hostname, opts, cb) {
  if (typeof opts === "function") { cb = opts; opts = {} }
  dohOrSystem(hostname).then(
    ip => (opts && opts.all) ? cb(null, [{ address: ip, family: 4 }]) : cb(null, ip, 4),
    e => cb(e));
}

/** one request, one way, no redirects followed */
function hop(target, way, extra, signal) {
  return new Promise((ok, no) => {
    let u;
    try { u = new URL(target) } catch (e) { return no(e) }
    const mod = u.protocol === "https:" ? https : http;
    const opt = {
      method: "GET",
      headers: Object.assign({ "User-Agent": way.ua, "Accept": "*/*", "Accept-Language": "en-US,en;q=0.9" }, extra || {})
    };
    if (way.doh) opt.lookup = dohLookup;
    if (way.insecure) opt.rejectUnauthorized = false;
    const r = mod.request(u, opt, resp => {
      /* connected and answered: from here the stream may rest as long as mpv likes */
      try { resp.socket.setTimeout(0) } catch (e) { }
      ok(resp);
    });
    r.setTimeout(HOP_MS, () => r.destroy(Object.assign(new Error("no answer"), { code: "ETIMEDOUT" })));
    r.on("error", no);
    if (signal) {
      if (signal.aborted) { r.destroy(new Error("aborted")); return }
      signal.addEventListener("abort", () => r.destroy(new Error("aborted")), { once: true });
    }
    r.end();
  });
}

/** every redirect followed by hand, so the address the answer really came from is known */
async function follow(target, way, extra, signal) {
  let cur = target;
  for (let i = 0; i < 8; i++) {
    const resp = await hop(cur, way, extra, signal);
    const code = resp.statusCode || 0;
    if (code >= 300 && code < 400 && resp.headers.location) {
      resp.resume();
      cur = new URL(resp.headers.location, cur).toString();
      continue;
    }
    return { resp, url: cur, code };
  }
  throw Object.assign(new Error("too many redirects"), { code: "EREDIRECT" });
}

/**
 * Opens a stream, trying only the ways that can help with the failure just
 * seen, best first: what worked on this server before, then the hint (how
 * the list it belongs to was fetched), then the rest in order.
 */
async function smartOpen(target, o) {
  o = o || {};
  const list = [];
  const add = w => { if (w && !list.some(x => wayKey(x) === wayKey(w))) list.push(w) };
  add(goodWay.get(hostOf(target)));
  add(o.hint);
  WAYS.forEach(add);
  const tried = new Set(), t0 = Date.now();
  let cur = list[0], last = null;
  while (cur) {
    tried.add(wayKey(cur));
    let why = "dropped";
    try {
      const r = await follow(target, cur, o.range ? { Range: o.range } : null, o.signal);
      const type = String(r.resp.headers["content-type"] || "");
      const pageInstead = o.media && r.code < 300 && /text\/html/i.test(type);
      if (!REFUSED.has(r.code) && !pageInstead) {
        goodWay.set(hostOf(target), cur);
        if (r.url !== target) goodWay.set(hostOf(r.url), cur);
        r.way = cur;
        return r;
      }
      r.resp.resume();
      last = Object.assign(new Error("HTTP " + r.code), { status: r.code });
      why = "refused";
    } catch (e) {
      if (o.signal && o.signal.aborted) throw e;
      last = e;
      why = whyOf(e);
    }
    if (Date.now() - t0 > WAYS_MS) break;
    cur = list.find(w => !tried.has(wayKey(w)) && helps(w, why));
  }
  throw last || new Error("no way");
}

/**
 * A stream's list, rewritten so mpv never has to work out an address itself.
 *
 * Every address is written out in full, against the address the list really
 * came from. Lists inside the list (the qualities of a channel, its sound
 * tracks) come back through here, because each of them can redirect too.
 * The pieces stay direct - unless this server needed another way of asking,
 * and then they come through here as well, asked that same way.
 */
function rewriteList(text, base, live, seg) {
  const whole = u => { try { return new URL(u, base).toString() } catch (e) { return u } };
  const viaList = u => live ? live + encodeURIComponent(whole(u)) : whole(u);
  const viaPiece = u => seg ? seg + encodeURIComponent(whole(u)) : whole(u);
  const isList = u => /\.m3u8?(\?|$)/i.test(String(u).split("#")[0]);
  let nextIsList = false;
  return text.split(/\r?\n/).map(line => {
    const t = line.trim();
    if (!t) return line;
    if (t.charAt(0) === "#") {
      if (/^#EXT-X-STREAM-INF/i.test(t)) nextIsList = true;
      if (/^#EXT-X-(MEDIA|I-FRAME-STREAM-INF)/i.test(t))
        return line.replace(/URI="([^"]+)"/g, (m, u) => 'URI="' + viaList(u) + '"');
      return line.replace(/URI="([^"]+)"/g, (m, u) => 'URI="' + viaPiece(u) + '"');
    }
    const out = (nextIsList || isList(t)) ? viaList(t) : viaPiece(t);
    nextIsList = false;
    return out;
  }).join("\n");
}

/** the first piece of an answer, with the rest left waiting */
function firstChunk(resp) {
  return new Promise((ok, no) => {
    const done = d => { resp.removeListener("end", end); resp.removeListener("error", no); ok(d) };
    const end = () => done(Buffer.alloc(0));
    resp.once("data", d => { resp.pause(); done(Buffer.from(d)) });
    resp.once("end", end);
    resp.once("error", no);
  });
}
function readRest(resp, first) {
  return new Promise((ok, no) => {
    const parts = [first];
    /* a short list arrives whole in its first piece, and its end has already
       gone by - waiting for it again would wait for ever */
    if (resp.readableEnded) return ok(Buffer.concat(parts));
    resp.on("data", d => parts.push(Buffer.from(d)));
    resp.on("end", () => ok(Buffer.concat(parts)));
    resp.on("error", no);
    resp.resume();
  });
}

/** hands a stream on to mpv as it arrives: its status, its size, its range */
function passOn(res, r, first) {
  const h = Object.assign({}, HEAD);
  delete h["Content-Type"];
  for (const k of ["content-type", "content-length", "content-range", "accept-ranges"])
    if (r.resp.headers[k]) h[k] = r.resp.headers[k];
  res.writeHead(r.code || 200, h);
  if (first && first.length) res.write(first);
  if (r.resp.readableEnded) { res.end(); return }
  r.resp.pipe(res);
  r.resp.resume();
}

function looksLikeList(type, head) {
  if (/mpegurl|x-mpegURL|m3u/i.test(type || "")) return true;
  return head.slice(0, 7).toString("latin1") === "#EXTM3U";
}

/** the whole first mouthful of the answer, and a way to keep drinking */
async function firstBite(r) {
  const reader = r.body ? r.body.getReader() : null;
  if (!reader) return { head: Buffer.alloc(0), reader: null };
  const bite = await reader.read();
  return { head: bite.value ? Buffer.from(bite.value) : Buffer.alloc(0), reader };
}

function start() {
  const server = http.createServer(async (req, res) => {
    if (req.method === "OPTIONS") { res.writeHead(200, HEAD); res.end(); return }

    let here = null, target = null;
    try {
      here = new URL(req.url, "http://127.0.0.1");
      target = here.searchParams.get("u");
    } catch (e) { }
    if (!target) { res.writeHead(400, HEAD); res.end("PROXY_ERROR NoTarget"); return }

    /* A stream, rather than something the page is fetching for itself.
       /live is the address mpv is given for a channel or a list; /seg is a
       piece of one, or a film's file, when its server needed another way. */
    if (here.pathname === "/live" || here.pathname === "/seg") {
      const ac = new AbortController();
      res.on("close", () => ac.abort());          /* mpv let go: so does the server */
      const port = server.address().port;
      const live = "http://127.0.0.1:" + port + "/live?u=";
      const seg = "http://127.0.0.1:" + port + "/seg?u=";
      try {
        const r = await smartOpen(target, { range: req.headers.range, signal: ac.signal, media: true });
        if (here.pathname === "/seg") { passOn(res, r, null); return }

        const first = await firstChunk(r.resp);
        if (looksLikeList(r.resp.headers["content-type"], first)) {
          const all = await readRest(r.resp, first);
          const body = Buffer.from(rewriteList(all.toString("utf8"), r.url, live, isPlain(r.way) ? null : seg), "utf8");
          res.writeHead(200, Object.assign({}, HEAD, {
            "Content-Type": "application/vnd.apple.mpegurl",
            "Content-Length": body.length
          }));
          res.end(body);
          return;
        }
        /* not a list. Asked the plain way, mpv can fetch it itself from where
           it really is; asked another way, it comes through here, that way.
           The health check reads it here, whichever way it was asked. */
        if (isPlain(r.way) && !req.headers.range && !here.searchParams.get("probe")) {
          r.resp.destroy();
          res.writeHead(302, Object.assign({}, HEAD, { Location: r.url }));
          res.end();
          return;
        }
        passOn(res, r, first);
      } catch (e) {
        if (res.headersSent) { try { res.destroy() } catch (e2) { } return }
        res.writeHead(e && e.status ? e.status : 599, HEAD);
        res.end("PROXY_ERROR " + (e && e.name ? e.name : "Error") + ": " + (e && e.message ? e.message : ""));
      }
      return;
    }

    try {
      const r = await fetchAllowingCloudflare(target);
      const body = Buffer.from(await r.arrayBuffer());
      res.writeHead(r.status, Object.assign({}, HEAD, { "Content-Length": body.length }));
      res.end(body);
    } catch (e) {
      /* the page reads this prefix and shows the reason, so keep its shape */
      res.writeHead(599, HEAD);
      res.end("PROXY_ERROR " + (e && e.name ? e.name : "Error") + ": " + (e && e.message ? e.message : ""));
    }
  });

  return new Promise(done => {
    server.listen(0, "127.0.0.1", () => {
      const port = server.address().port;
      done({
        server, port,
        url: "http://127.0.0.1:" + port + "/p?u=",
        live: "http://127.0.0.1:" + port + "/live?u="
      });
    });
  });
}

module.exports = { start, UA, BROWSER_UA, inFull, looksLikeList, rewriteList, smartOpen, whyOf, helps, isPlain, WAYS };
