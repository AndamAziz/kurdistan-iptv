/*
 * What the page finds when it looks for the phone.
 *
 * The page was written for Android and asks for `AndroidPlayer`; it is given
 * exactly that, with the same methods and the same arguments, so not one line
 * of it has to know it is on a desktop. The two that return an answer on the
 * spot - which build this is, and whether this is a television - are asked and
 * answered without waiting, because that is how the page asks them.
 */
const { contextBridge, ipcRenderer } = require("electron");

function argValue(name) {
  const hit = process.argv.find(a => a.startsWith(name + "="));
  return hit ? hit.slice(name.length + 1) : "";
}

contextBridge.exposeInMainWorld("AndroidPlayer", {
  isTv:    () => ipcRenderer.sendSync("kiptv-istv"),
  version: () => ipcRenderer.sendSync("kiptv-version"),
  setLang: l => ipcRenderer.send("kiptv-lang", String(l || "")),
  openUrl: u => ipcRenderer.send("kiptv-open", String(u || "")),

  playNative: (u, n) => ipcRenderer.send("kiptv-play-one", String(u || ""), String(n || "")),

  /* the page calls this with two arguments or three */
  playList: (json, index, startMs) =>
    ipcRenderer.send("kiptv-play-list", String(json || ""), index | 0, startMs | 0)
});

/* a request with its own method and headers (subtitles: OpenSubtitles, Claude),
   made by the app; the page gets {status, text} back */
contextBridge.exposeInMainWorld("KIPTV_HTTP", (method, url, headers, body) =>
  ipcRenderer.invoke("kiptv-http", String(method || "GET"), String(url || ""), String(headers || "{}"), String(body || "")));

/* a finished subtitle file, kept by the app; answers where it lies */
contextBridge.exposeInMainWorld("KIPTV_SUBSAVE", (name, text) =>
  ipcRenderer.sendSync("kiptv-subsave", String(name || ""), String(text || "")));

/* where to send a request the page may not make itself */
contextBridge.exposeInMainWorld("KIPTV_PROXY", argValue("--kiptv-proxy"));

/* which machine this is, so a new version offers the right file to download */
contextBridge.exposeInMainWorld("KIPTV_OS", process.platform === "win32" ? "win" : process.platform);
