/*
 * Builds the VIDAA / HomeOS TV app (Hisense):   node vidaa/build.js [version]
 *
 * Like webos/build.js, the page is copied from the Android assets folder, so
 * there is never a second version to keep in step, and only the copy gets the
 * TV's changes (each stops the build if the line it needs has moved):
 *   - hls.js, mpegts.js and vidaa-player.js load first, so the page finds
 *     AndroidPlayer when it starts, exactly as on a phone;
 *   - requests go straight to the servers (there is no app in between);
 *   - the self-updater stays quiet: updates come from the VIDAA store.
 *
 * Like the Amazon build, it is a player only: it takes the Amazon channels.js
 * (app/src/amazon/assets), with no channels and no starting playlist, so the
 * user adds their own playlist. Store reviewers refuse an app that streams
 * from third-party sources out of the box.
 *
 * Writes:
 *   vidaa/build/                               the app's files, for a hosted URL
 *   vidaa/dist/KURDISTAN-IPTV-VIDAA-<v>.zip    the same files, for an upload
 * Nothing outside vidaa/ is written. No packages to install: Node.js only.
 */
const fs = require("fs");
const path = require("path");
const zlib = require("zlib");
const { execSync } = require("child_process");

const here = __dirname;
const root = path.join(here, "..");
const assets = path.join(root, "app", "src", "main", "assets");
const bare = path.join(root, "app", "src", "amazon", "assets", "channels.js");
const out = path.join(here, "build");
const dist = path.join(here, "dist");

function versionNow() {
  if (process.argv[2]) return process.argv[2];
  try { return "1.0." + String(execSync("git rev-list --count HEAD", { cwd: root })).trim() }
  catch (e) { return "1.0.0" }
}
const version = versionNow();
if (!/^\d{1,3}\.\d{1,3}\.\d{1,5}$/.test(version)) { console.error("version must be x.y.z, got " + version); process.exit(1) }
const build = version.split(".")[2];

function patch(text, find, put, what) {
  const n = text.split(find).length - 1;
  if (n !== 1) { console.error("vidaa build: cannot place " + what + " (found " + n + " times) - index.html changed?"); process.exit(1) }
  return text.replace(find, put);
}

/* the bare channel list must really be bare: no channel, no starting playlist */
const bareText = fs.readFileSync(bare, "utf8");
if (!/var CHANNELS = \[\];/.test(bareText) || !/var DEFAULT_LIST_URL = "";/.test(bareText)) {
  console.error("vidaa build: " + bare + " is not the empty Amazon list"); process.exit(1);
}

fs.rmSync(out, { recursive: true, force: true });
fs.mkdirSync(out, { recursive: true });

let page = fs.readFileSync(path.join(assets, "index.html"), "utf8");
page = patch(page, "<head>",
  "<head>\n<script>window.KIPTV_VIDAA_BUILD = " + JSON.stringify(build) + ";</script>\n" +
  '<script src="lib/hls.min.js"></script>\n<script src="lib/mpegts.min.js"></script>\n' +
  '<script src="vidaa-player.js"></script>', "the VIDAA scripts");
page = patch(page, "function viaApp(u){\n", "function viaApp(u){\n  if(window.KIPTV_DIRECT) return u;\n", "direct requests");
/* the VIDAA-only fixes (focus, M3U logos, guide, test names) load last */
page = patch(page, "</body>", '<script src="vidaa-extras.js"></script>\n</body>', "the VIDAA extras");
/* the MAC Portal requests too: on a TV there is no Android app to carry them,
   so the page asks the portal itself (the MAC rides in the address) */
page = patch(page, "  if(window.KIPTV_PROXY) u = window.KIPTV_PROXY + encodeURIComponent(url)", "  if(window.KIPTV_DIRECT) u = \"\";\n  else if(window.KIPTV_PROXY) u = window.KIPTV_PROXY + encodeURIComponent(url)", "direct MAC Portal requests");
page = patch(page, "function updCheck(manual){\n", "function updCheck(manual){\n  if(window.KIPTV_NOUPD) return Promise.resolve(null);\n", "the quiet updater");
page = patch(page, '  h += setRowC("c-green", ICON.down, d.updCheck,', '  if(!window.KIPTV_NOUPD) h += setRowC("c-green", ICON.down, d.updCheck,', "the update row");
fs.writeFileSync(path.join(out, "index.html"), page);

fs.copyFileSync(bare, path.join(out, "channels.js"));
fs.copyFileSync(path.join(assets, "welcome.mp3"), path.join(out, "welcome.mp3"));
for (const name of ["vidaa-player.js", "vidaa-extras.js", "icon.png", "largeIcon.png", "splash.png"])
  fs.copyFileSync(path.join(here, name), path.join(out, name));
fs.mkdirSync(path.join(out, "lib"));
for (const name of fs.readdirSync(path.join(here, "lib")))
  fs.copyFileSync(path.join(here, "lib", name), path.join(out, "lib", name));
fs.writeFileSync(path.join(out, "version.txt"), "KURDISTAN IPTV for VIDAA " + version + "\n");

/* ---- a plain .zip, written here so nothing has to be installed ---- */
const CRC = (() => { const t = []; for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0 } return t })();
function crc32(b) { let c = 0xffffffff; for (let i = 0; i < b.length; i++) c = CRC[(c ^ b[i]) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0 }
function zip(files) {
  const parts = [], central = []; let off = 0;
  for (const f of files) {
    const name = Buffer.from(f.name, "utf8"), data = f.data, comp = zlib.deflateRawSync(data, { level: 9 }), crc = crc32(data);
    const h = Buffer.alloc(30);
    h.writeUInt32LE(0x04034b50, 0); h.writeUInt16LE(20, 4); h.writeUInt16LE(0x0800, 6); h.writeUInt16LE(8, 8);
    h.writeUInt32LE(0, 10); h.writeUInt32LE(crc, 14); h.writeUInt32LE(comp.length, 18); h.writeUInt32LE(data.length, 22);
    h.writeUInt16LE(name.length, 26); h.writeUInt16LE(0, 28);
    parts.push(h, name, comp);
    const c = Buffer.alloc(46);
    c.writeUInt32LE(0x02014b50, 0); c.writeUInt16LE(20, 4); c.writeUInt16LE(20, 6); c.writeUInt16LE(0x0800, 8); c.writeUInt16LE(8, 10);
    c.writeUInt32LE(0, 12); c.writeUInt32LE(crc, 16); c.writeUInt32LE(comp.length, 20); c.writeUInt32LE(data.length, 24);
    c.writeUInt16LE(name.length, 28); c.writeUInt32LE(off, 42);
    central.push(c, name);
    off += h.length + name.length + comp.length;
  }
  const cd = Buffer.concat(central), end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(files.length, 8); end.writeUInt16LE(files.length, 10);
  end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(off, 16);
  return Buffer.concat(parts.concat([cd, end]));
}
function walk(dir, pre) {
  let r = [];
  for (const n of fs.readdirSync(dir).sort()) {
    const p = path.join(dir, n), rel = pre ? pre + "/" + n : n;
    if (fs.statSync(p).isDirectory()) r = r.concat(walk(p, rel)); else r.push({ name: rel, data: fs.readFileSync(p) });
  }
  return r;
}
fs.mkdirSync(dist, { recursive: true });
const zipName = "KURDISTAN-IPTV-VIDAA-" + version + ".zip";
fs.writeFileSync(path.join(dist, zipName), zip(walk(out, "")));

console.log("vidaa/build ready - version " + version);
for (const f of walk(out, "")) console.log("  " + f.name.padEnd(28) + f.data.length + " bytes");
console.log("vidaa/dist/" + zipName + "  " + fs.statSync(path.join(dist, zipName)).size + " bytes");
