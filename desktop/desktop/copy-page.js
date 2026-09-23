/* one page, two apps: the desktop build takes its copy from the Android
   assets folder, so there is never a second version to keep in step */
const fs = require("fs");
const path = require("path");

const from = path.join(__dirname, "..", "app", "src", "main", "assets");
const to = path.join(__dirname, "page");

fs.mkdirSync(to, { recursive: true });
for (const name of ["index.html", "channels.js"]) {
  const src = path.join(from, name);
  if (!fs.existsSync(src)) { console.error("missing: " + src); process.exit(1) }
  fs.copyFileSync(src, path.join(to, name));
  console.log("copied " + name + "  (" + fs.statSync(src).size + " bytes)");
}
