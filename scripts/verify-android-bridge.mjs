/**
 * Checks the Android window.electron shim against preload.js and exercises
 * the pure JS pieces (HiAnime decode, ZIP extract, update host allow-list,
 * backup schedule).
 */
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";
import { tvRemoteAction } from "../src/utils/tvRemoteActions.js";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

function preloadMethods() {
  const text = fs.readFileSync(path.join(root, "preload.js"), "utf8");
  return [...text.matchAll(/^\s{2}(\w+):\s/gm)].map((m) => m[1]);
}

const store = new Map();
const listeners = new Map();
const sandbox = {
  console,
  setTimeout,
  clearTimeout,
  URL,
  Map,
  Set,
  Promise,
  TextEncoder,
  TextDecoder,
  atob,
  btoa,
  Blob,
  Response,
  Uint8Array,
  DataView,
  TextDecoder,
  encodeURIComponent,
  decodeURIComponent,
  CustomEvent: class CustomEvent {
    constructor(type, init) {
      this.type = type;
      this.detail = init && init.detail;
    }
  },
};
sandbox.window = sandbox;
sandbox.globalThis = sandbox;
sandbox.document = {
  write() {},
  querySelector() {
    return { setAttribute() {} };
  },
  documentElement: { style: {} },
};
sandbox.localStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => store.set(k, String(v)),
};
sandbox.HTMLElement = class HTMLElement {};
sandbox.customElements = {
  get() {
    return undefined;
  },
  define() {},
};
sandbox.addEventListener = (type, fn) => {
  if (!listeners.has(type)) listeners.set(type, new Set());
  listeners.get(type).add(fn);
};
sandbox.dispatchEvent = (ev) => {
  for (const fn of listeners.get(ev.type) || []) fn(ev);
};
sandbox.navigator = {};
sandbox.window.__SB_BOOT = {
  ua: "Mozilla/5.0 (test)",
  version: "2.7.0",
  userData: "/data/streambert",
  cache: "/cache",
  movies: "/movies",
  tmp: "/tmp",
  downloadsJson: "[]",
};
sandbox.window.sbNative = { postMessage() {} };

vm.createContext(sandbox);
for (const file of ["webview.js", "hianime.js", "subtitles.js", "downloads.js", "electron.js"]) {
  const code = fs.readFileSync(path.join(root, "public", "android", file), "utf8");
  vm.runInContext(code, sandbox, { filename: file });
}

const api = sandbox.window.electron;
assert.ok(api, "window.electron was not installed");

const expected = preloadMethods();
const missing = expected.filter((name) => api[name] === undefined);
assert.deepEqual(missing, [], `Android bridge is missing preload methods: ${missing.join(", ")}`);

const extra = Object.keys(api).filter((name) => !expected.includes(name));
assert.deepEqual(extra, [], `Android bridge has methods Electron does not: ${extra.join(", ")}`);

assert.equal(await api.getPlatform(), "android");
assert.equal(await api.isTelevision(), false);
assert.equal(typeof api.startPhonePairing, "function");
assert.equal(typeof api.stopPhonePairing, "function");
assert.equal(typeof api.onPhoneKeysSaved, "function");
assert.equal(await api.detectUpdateFormat(), "apk");
assert.equal(await api.getAppVersion(), "2.7.0");
const debug = await api.debugHiAnime({});
assert.equal(debug.error, "disabled in packaged builds");
const discord = await api.discordRpcSetEnabled(true);
assert.equal(discord.supported, false);
const downloader = await api.checkDownloader("android-builtin");
assert.equal(downloader.exists, false);
assert.equal(downloader.removed, true);
assert.equal((await api.getDownloads()).length, 0);
assert.equal((await api.runDownload({ m3u8Url: "https://cdn.example/a.m3u8" })).ok, false);

const { trustedUpdate, shouldRunBackup } = sandbox.window.__sbTest;
assert.ok(
  trustedUpdate("https://github.com/truelockmc/streambert/releases/download/v2.7.0/app.apk"),
);
assert.equal(trustedUpdate("https://evil.example/app.apk"), null);
assert.equal(trustedUpdate("https://github.com/other/streambert/releases/download/v1/a.apk"), null);
assert.ok(trustedUpdate("https://codeberg.org/attachments/abcd/streambert.apk"));
assert.equal(shouldRunBackup({ enabled: true, path: "/b", frequency: "startup", lastRun: null }), true);
assert.equal(shouldRunBackup({ enabled: false, path: "/b", frequency: "startup" }), false);
assert.equal(
  shouldRunBackup({
    enabled: true,
    path: "/b",
    frequency: "daily",
    lastRun: new Date().toISOString(),
  }),
  false,
);

const key = new TextEncoder().encode("otaku-embed-v1");
const plain = "hello-hianime";
const raw = new TextEncoder().encode(plain);
const xored = Uint8Array.from(raw, (b, i) => b ^ key[i % key.length]);
const decoded = sandbox.window.__sbHiAnime.xorDecode(Buffer.from(xored).toString("base64"));
assert.equal(decoded, plain);

const wrapped = JSON.stringify({ html: '<div class=\\"film\\">ep</div>' });
const unwrapped = sandbox.window.__sbHiAnime.unwrapApiHtml(wrapped);
assert.match(unwrapped, /<div class="film">ep<\/div>/);

function zipStored(name, payload) {
  const nameBytes = Buffer.from(name);
  const local = Buffer.alloc(30 + nameBytes.length + payload.length);
  local.writeUInt32LE(0x04034b50, 0);
  local.writeUInt16LE(0, 8);
  local.writeUInt32LE(payload.length, 18);
  local.writeUInt32LE(payload.length, 22);
  local.writeUInt16LE(nameBytes.length, 26);
  nameBytes.copy(local, 30);
  payload.copy(local, 30 + nameBytes.length);
  return new Uint8Array(local);
}

const extracted = await sandbox.window.__sbSubtitles.extractFirstSubtitleFromZip(
  zipStored("eng.srt", Buffer.from("1\n00:00:01,000 --> 00:00:02,000\nHi\n")),
);
assert.equal(extracted.name, "eng.srt");
assert.equal(Buffer.from(extracted.data).toString("utf8").includes("Hi"), true);
assert.equal(sandbox.window.__sbSubtitles.extractSubtitleLang("https://cdn.example/subs/movie.en.vtt"), "en");
assert.equal(sandbox.window.__sbSubtitles.extractSubtitleLang("https://cdn.example/en/movie.vtt?lang=de"), "de");
assert.equal(await sandbox.window.__sbSubtitles.extractFirstSubtitleFromZip(new Uint8Array([1, 2, 3])), null);

const boot = fs.readFileSync(path.join(root, "public", "android", "boot.js"), "utf8");
assert.match(boot, /window\.electron/);
assert.match(boot, /window\.__SB_BOOT/);
const html = fs.readFileSync(path.join(root, "index.html"), "utf8");
assert.match(html, /android\/boot\.js/);
assert.ok(html.indexOf("android/boot.js") < html.indexOf("/src/main.jsx"));

assert.deepEqual(tvRemoteAction({ key: "ArrowLeft", playerOpen: false, controlsFocused: false, typing: false }), { type: "move", dir: "left" });
assert.deepEqual(tvRemoteAction({ key: "ArrowRight", playerOpen: true, controlsFocused: false, typing: false }), { type: "seek", delta: 10 });
assert.deepEqual(tvRemoteAction({ key: "ArrowLeft", playerOpen: true, controlsFocused: false, typing: true }), null);
assert.deepEqual(tvRemoteAction({ key: "Enter", playerOpen: true, controlsFocused: false, typing: false }), { type: "playpause" });
assert.deepEqual(tvRemoteAction({ key: "Enter", playerOpen: true, controlsFocused: true, typing: false }), { type: "activate" });
assert.deepEqual(tvRemoteAction({ key: "MediaPlayPause", playerOpen: true, controlsFocused: false, typing: false }), { type: "playpause" });
assert.deepEqual(tvRemoteAction({ key: "seek:-10", playerOpen: true, controlsFocused: false, typing: false }), { type: "seek", delta: -10 });
assert.deepEqual(tvRemoteAction({ key: "Escape", playerOpen: false, controlsFocused: false, typing: false }), { type: "back" });
assert.deepEqual(tvRemoteAction({ key: "ArrowDown", playerOpen: false, controlsFocused: false, typing: false }), { type: "move", dir: "down" });

const manifest = fs.readFileSync(path.join(root, "android", "app", "src", "main", "AndroidManifest.xml"), "utf8");
assert.match(manifest, /android.software.leanback/);
assert.match(manifest, /android.hardware.touchscreen" android:required="false"/);
assert.match(manifest, /LEANBACK_LAUNCHER/);
assert.match(manifest, /@drawable\/tv_banner/);

console.log(`Android bridge matches ${expected.length} preload methods`);
