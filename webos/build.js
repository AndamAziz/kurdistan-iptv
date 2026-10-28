/*
 * Builds the LG Smart TV (webOS) app in webos/build/:   node webos/build.js [version]
 *
 * The same way as tizen/build.js: the page is copied from the Android assets
 * folder, so there is never a second version to keep in step, and the copy
 * (never the original) gets the small changes the TV needs. Each change stops
 * the build if the line it needs has moved:
 *   - webos-player.js is loaded first, so the page finds AndroidPlayer when it
 *     starts, exactly as on a phone;
 *   - requests go straight to the servers (there is no app in between);
 *   - the self-updater stays quiet: updates come from the LG Content Store.
 *
 * The version is x.y.z and must go up with every upload to the LG Seller
 * Lounge; it defaults to 1.0.<number of commits>.
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
  if (n !== 1) { console.error("webos build: cannot place " + what + " (found " + n + " times) - index.html changed?"); process.exit(1) }
  return text.replace(find, put);
}

fs.rmSync(out, { recursive: true, force: true });
fs.mkdirSync(out, { recursive: true });

let page = fs.readFileSync(path.join(assets, "index.html"), "utf8");
page = patch(page, "<head>",
  "<head>\n<script>window.KIPTV_WEBOS_BUILD = " + JSON.stringify(build) + ";</script>\n" +
  '<script src="webos-player.js"></script>', "the webOS scripts");
page = patch(page, "function viaApp(u){\n", "function viaApp(u){\n  if(window.KIPTV_DIRECT) return u;\n", "direct requests");
page = patch(page, "function updCheck(manual){\n", "function updCheck(manual){\n  if(window.KIPTV_NOUPD) return Promise.resolve(null);\n", "the quiet updater");
page = patch(page, '  h += setRowC("c-green", ICON.down, d.updCheck,', '  if(!window.KIPTV_NOUPD) h += setRowC("c-green", ICON.down, d.updCheck,', "the update row");
fs.writeFileSync(path.join(out, "index.html"), page);

for (const name of ["channels.js", "welcome.mp3"]) fs.copyFileSync(path.join(assets, name), path.join(out, name));
for (const name of ["webos-player.js", "icon.png", "largeIcon.png", "splash.png"])
  fs.copyFileSync(path.join(__dirname, name), path.join(out, name));
fs.writeFileSync(path.join(out, "appinfo.json"),
  fs.readFileSync(path.join(__dirname, "appinfo.json"), "utf8").replace("@VERSION@", version));

console.log("webos/build ready - version " + version);
for (const f of fs.readdirSync(out)) console.log("  " + f.padEnd(18) + fs.statSync(path.join(out, f)).size + " bytes");
