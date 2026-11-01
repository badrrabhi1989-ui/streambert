/**
 * Copies runtime assets the Android shell needs and that Capacitor sync does
 * not know about: the Electron ad-block list, the guest frame script, and hls.js.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const assetsDir = path.join(root, "android", "app", "src", "main", "assets");
const javaDir = path.join(
  root,
  "android",
  "app",
  "src",
  "main",
  "java",
  "com",
  "truelockmc",
  "streambert",
);
const testDir = path.join(
  root,
  "android",
  "app",
  "src",
  "test",
  "java",
  "com",
  "truelockmc",
  "streambert",
);

function extractBlocklist() {
  const index = fs.readFileSync(path.join(root, "index.js"), "utf8");
  const start = index.indexOf("const BLOCKED_HOSTS = [");
  if (start < 0) throw new Error("BLOCKED_HOSTS not found in index.js");
  const slice = index.slice(start);
  const end = slice.indexOf("];");
  if (end < 0) throw new Error("BLOCKED_HOSTS array is not closed");
  const body = slice.slice(0, end);
  const patterns = [...body.matchAll(/"(\*?:\/\/[^"]+)"/g)].map((m) => m[1]);
  if (patterns.length < 50) {
    throw new Error(`Only extracted ${patterns.length} blocklist patterns`);
  }
  return patterns.join("\n") + "\n";
}

function copyKotlin() {
  const kit = path.join(root, "android-kit");
  if (!fs.existsSync(kit)) return;
  fs.mkdirSync(javaDir, { recursive: true });
  fs.mkdirSync(testDir, { recursive: true });
  for (const name of fs.readdirSync(kit)) {
    if (!name.endsWith(".kt")) continue;
    const destDir = name.endsWith("Test.kt") ? testDir : javaDir;
    fs.copyFileSync(path.join(kit, name), path.join(destDir, name));
  }
  const generated = path.join(javaDir, "MainActivity.java");
  if (fs.existsSync(generated)) fs.unlinkSync(generated);
}

if (!fs.existsSync(path.join(root, "android", "app"))) {
  console.error("android/app is missing. Run `npx cap add android` first.");
  process.exit(1);
}

fs.mkdirSync(assetsDir, { recursive: true });
fs.writeFileSync(path.join(assetsDir, "blocklist.txt"), extractBlocklist());
fs.copyFileSync(
  path.join(root, "public", "android", "guest.js"),
  path.join(assetsDir, "guest.js"),
);
const hls = path.join(root, "node_modules", "hls.js", "dist", "hls.min.js");
if (!fs.existsSync(hls)) {
  throw new Error("hls.js is not installed. Run npm install.");
}
fs.copyFileSync(hls, path.join(assetsDir, "hls.min.js"));
copyKotlin();
console.log("Android assets prepared in", assetsDir);
