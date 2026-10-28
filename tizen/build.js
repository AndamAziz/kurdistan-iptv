/*
 * Builds the Samsung TV (Tizen) app in tizen/build/:   node tizen/build.js [version]
 *
 * One page, three apps: like the desktop build, this takes its copy of the
 * page from the Android assets folder, so there is never a second version to
 * keep in step. Three small changes are made to that copy (never to the
 * original), and each one stops the build if the line it needs has moved:
 *   - Samsung's web APIs and tizen-player.js are loaded first, so the page
 *     finds AndroidPlayer when it starts, exactly as on a phone;
 *   - requests go straight to the servers (there is no app in between to
 *     carry them, and a TV app is not held back by the browser's CORS);
 *   - the self-updater stays quiet: updates come from the Samsung store.
 *
 * The version is x.y.z (x and y up to 255, z up to 65535) and must go up with
 * every upload to the Seller Office; it defaults to 1.0.<number of commits>.
 */
const fs = require("fs");
const path = require("path");
const { execSync } = require("child_process");

const root = path.join(__dirname, "..");
const assets = path.join(root, "app", "src", "main", "assets");
const out = path.join(__dirname, "build");

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
  if (n !== 1) { console.error("tizen build: cannot place " + what + " (found " + n + " times) - index.html changed?"); process.exit(1) }
  return text.replace(find, put);
}

fs.rmSync(out, { recursive: true, force: true });
fs.mkdirSync(out, { recursive: true });

let page = fs.readFileSync(path.join(assets, "index.html"), "utf8");
page = patch(page, "<head>",
  "<head>\n<script>window.KIPTV_TIZEN_BUILD = " + JSON.stringify(build) + ";</script>\n" +
  '<script src="$WEBAPIS/webapis/webapis.js"></script>\n' +
  '<script src="tizen-player.js"></script>', "the Tizen scripts");
page = patch(page, "function viaApp(u){\n", "function viaApp(u){\n  if(window.KIPTV_DIRECT) return u;\n", "direct requests");
page = patch(page, "function updCheck(manual){\n", "function updCheck(manual){\n  if(window.KIPTV_NOUPD) return Promise.resolve(null);\n", "the quiet updater");
page = patch(page, '  h += setRowC("c-green", ICON.down, d.updCheck,', '  if(!window.KIPTV_NOUPD) h += setRowC("c-green", ICON.down, d.updCheck,', "the update row");
fs.writeFileSync(path.join(out, "index.html"), page);

for (const name of ["channels.js", "welcome.mp3"]) fs.copyFileSync(path.join(assets, name), path.join(out, name));
fs.copyFileSync(path.join(__dirname, "tizen-player.js"), path.join(out, "tizen-player.js"));
fs.copyFileSync(path.join(__dirname, "icon.png"), path.join(out, "icon.png"));
fs.writeFileSync(path.join(out, "config.xml"),
  fs.readFileSync(path.join(__dirname, "config.xml"), "utf8").replace("@VERSION@", version));

console.log("tizen/build ready - version " + version);
for (const f of fs.readdirSync(out)) console.log("  " + f.padEnd(18) + fs.statSync(path.join(out, f)).size + " bytes");
