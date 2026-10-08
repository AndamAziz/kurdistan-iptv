/*
 * KURDISTAN IPTV on Windows.
 *
 * The page is the same one the Android app carries, unchanged. Everything it
 * asks of the phone, it asks of this instead - under the same name, with the
 * same arguments - so neither side has to know which one it is talking to.
 */
const { app, BrowserWindow, ipcMain, protocol, shell, net } = require("electron");
const fs = require("fs");
const path = require("path");
const url = require("url");

const proxy = require("./proxy");
const { Player } = require("./player");

const PAGE = path.join(__dirname, "page");

/* A scheme of its own rather than plain files: it gives the page a settled
   home, so playlists, favourites and the activation code survive an update.
   It is deliberately not marked secure - a panel that serves its posters over
   plain http would have them refused as mixed content if it were. */
protocol.registerSchemesAsPrivileged([{
  scheme: "kiptv",
  privileges: { standard: true, supportFetchAPI: true, corsEnabled: true, stream: true }
}]);

let win = null;
let player = null;
let proxied = null;

/* ---------------------------------------------------------------- the window */

function boxFile() { return path.join(app.getPath("userData"), "window.json") }

function lastBox() {
  try { return JSON.parse(fs.readFileSync(boxFile(), "utf8")) } catch (e) { return null }
}

function keepBox() {
  if (!win || win.isDestroyed()) return;
  try {
    const b = win.getNormalBounds();
    fs.writeFileSync(boxFile(), JSON.stringify({ ...b, max: win.isMaximized() }), "utf8");
  } catch (e) { }
}

function makeWindow() {
  const b = lastBox();
  win = new BrowserWindow({
    width: (b && b.width) || 1180,
    height: (b && b.height) || 760,
    x: b && b.x, y: b && b.y,
    minWidth: 420, minHeight: 480,
    backgroundColor: "#070A0E",
    title: "KURDISTAN IPTV",
    autoHideMenuBar: true,
    icon: path.join(__dirname, "build", "icon.png"),
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
      /* the welcome sound plays itself, as it does inside the phone's WebView */
      autoplayPolicy: "no-user-gesture-required",
      /* the page reads the proxy's address from here, as MainActivity's
         WebView reads it from a constant */
      additionalArguments: ["--kiptv-proxy=" + (proxied ? proxied.url : "")]
    }
  });
  if (b && b.max) win.maximize();
  win.on("resize", keepBox);
  win.on("move", keepBox);
  win.on("close", keepBox);
  win.on("closed", () => { win = null });

  /* a link in the page opens in the person's own browser, never in here */
  win.webContents.setWindowOpenHandler(({ url: u }) => {
    if (/^https?:\/\//i.test(u)) shell.openExternal(u);
    return { action: "deny" };
  });

  win.loadURL("kiptv://app/index.html");
  return win;
}

/* ------------------------------------------------------------- serving the page */

function servePage() {
  protocol.handle("kiptv", req => {
    const p = new URL(req.url).pathname;
    const file = path.join(PAGE, path.normalize(p).replace(/^[\\/]+/, ""));
    /* nothing outside the page folder, whatever the address says */
    if (!file.startsWith(PAGE)) return new Response("no", { status: 403 });
    return net.fetch(url.pathToFileURL(file).toString());
  });
}

/* -------------------------------------------------------------- talking to mpv */

function mpvPath() {
  const packed = path.join(process.resourcesPath || "", "mpv", "mpv.exe");
  if (fs.existsSync(packed)) return packed;
  const beside = path.join(__dirname, "mpv", process.platform === "win32" ? "mpv.exe" : "mpv");
  if (fs.existsSync(beside)) return beside;
  return process.platform === "win32" ? "mpv.exe" : "mpv";     /* on the path, if anywhere */
}

/** what to say when a channel simply will not open, in the app's own language */
const WONT_OPEN = {
  en: "this channel would not open",
  ku: "\u0626\u06d5\u0645 \u06a9\u06d5\u0646\u0627\u06b5\u06d5 \u0646\u0627\u06a9\u0631\u06ce\u062a\u06d5\u0648\u06d5",
  ar: "\u0647\u0630\u0647 \u0627\u0644\u0642\u0646\u0627\u0629 \u0644\u0627 \u062a\u0641\u062a\u062d"
};

function pageLang() {
  if (!win || win.isDestroyed()) return Promise.resolve("en");
  return win.webContents.executeJavaScript("window.lang||'en'").catch(() => "en");
}

function makePlayer() {
  return new Player(mpvPath(), app.getPath("userData"), {
    onPosition(u, posMs, durMs) {
      run("window.savePos&&savePos(" + JSON.stringify(u) + "," + posMs + "," + durMs + ")");
    },
    onClosed() {
      run("window.refreshHome&&refreshHome()");
      if (win && !win.isDestroyed()) win.focus();
    },
    /* A channel that will not open used to be hidden: mpv moved on to the
       next one in the queue and a different channel came up instead. Now it
       stops, and the name of the channel that failed is said out loud. */
    async onFailed(name) {
      const l = await pageLang();
      const say = WONT_OPEN[l] || WONT_OPEN.en;
      run("window.showSync&&showSync(" + JSON.stringify(name ? name + " \u2014 " + say : say) + ",true)");
    }
  }, proxied ? proxied.live : null);
}

function run(js) {
  if (win && !win.isDestroyed()) win.webContents.executeJavaScript(js).catch(() => { });
}

/** where the app is sitting, so the picture opens in the same place */
function box() {
  if (!win || win.isDestroyed()) return null;
  try { return win.getBounds() } catch (e) { return null }
}

async function play(items, index, startMs) {
  if (!player) player = makePlayer();
  const started = await player.open(items, index, startMs, box());
  if (!started) run("window.showSync&&showSync('mpv',true)");
  return started;
}

/* ----------------------------------------------------------------- the bridge */

/** the same names, the same arguments, as the Android side */
function wire() {
  ipcMain.on("kiptv-istv", e => { e.returnValue = false });

  /* "1.0|120", the same shape the phone answers with.
     The number is the last part of the version stamped into the app when it
     was built. It used to be read from the environment, which exists on the
     machine that BUILDS the app and not on the one that RUNS it - so every
     copy called itself build 1 and could never tell it was out of date. */
  ipcMain.on("kiptv-version", e => {
    const v = String(app.getVersion() || "");
    const tail = v.match(/(\d+)\s*$/);
    e.returnValue = "1.0|" + (tail ? tail[1] : "0");
  });

  ipcMain.on("kiptv-lang", () => { /* mpv speaks its own language; nothing to set */ });

  ipcMain.on("kiptv-open", (e, u) => {
    if (typeof u === "string" && /^https?:\/\//i.test(u)) shell.openExternal(u);
  });

  ipcMain.on("kiptv-play-one", (e, u, n) => {
    if (typeof u !== "string" || !u) return;
    play([{ u, n: String(n || "") }], 0, 0);
  });

  ipcMain.on("kiptv-play-list", (e, json, index, startMs) => {
    let arr = null;
    try { arr = JSON.parse(json) } catch (err) { return }
    if (!Array.isArray(arr) || !arr.length) return;
    const items = arr
      .filter(o => o && o.u)
      .map(o => ({ u: String(o.u), n: String(o.n || ""), ua: o.ua || null, rf: o.rf || null, v: o.v === 1 }));
    if (!items.length) return;
    const at = (index >= 0 && index < items.length) ? index : 0;
    play(items, at, startMs > 0 ? startMs : 0);
  });
}

/* ------------------------------------------------------------------- starting */

/* one copy of the app, so two windows never fight over the same playlists */
if (!app.requestSingleInstanceLock()) { app.quit() }
else {
  app.on("second-instance", () => {
    if (win && !win.isDestroyed()) { if (win.isMinimized()) win.restore(); win.focus() }
  });

  app.whenReady().then(async () => {
    proxied = await proxy.start();
    servePage();
    wire();
    makeWindow();

    app.on("activate", () => { if (!BrowserWindow.getAllWindows().length) makeWindow() });
  });

  app.on("window-all-closed", async () => {
    if (player) await player.close();
    if (proxied && proxied.server) proxied.server.close();
    if (process.platform !== "darwin") app.quit();
  });

  app.on("before-quit", () => { if (player) player.close() });
}

module.exports = { mpvPath };
