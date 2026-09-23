/*
 * The same job MainActivity's shouldInterceptRequest does on Android: the page
 * cannot ask another server for anything itself, so it asks this instead and
 * this asks on its behalf.
 *
 * It listens only on 127.0.0.1, on whatever port the machine gives it, and it
 * answers one shape of request: /p?u=<the real address>.
 */
const http = require("http");

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

function start() {
  const server = http.createServer(async (req, res) => {
    if (req.method === "OPTIONS") { res.writeHead(200, HEAD); res.end(); return }

    let target = null;
    try { target = new URL(req.url, "http://127.0.0.1").searchParams.get("u") } catch (e) { }
    if (!target) { res.writeHead(400, HEAD); res.end("PROXY_ERROR NoTarget"); return }

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
      done({ server, port, url: "http://127.0.0.1:" + port + "/p?u=" });
    });
  });
}

module.exports = { start, UA, BROWSER_UA };
