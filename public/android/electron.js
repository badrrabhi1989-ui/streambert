/* window.electron for Android. Method names and payload shapes match preload.js
 * so the React app does not branch on platform except where Android has no
 * desktop equivalent (reported by the method itself).
 */
(function () {
  var boot = window.__SB_BOOT || {};
  var UA = boot.ua || "";
  if (boot.television) {
    try {
      document.documentElement.classList.add("sb-tv");
      document.documentElement.classList.add("emberlane");
      var vp = document.querySelector('meta[name="viewport"]');
      if (vp) vp.setAttribute("content", "width=1920, initial-scale=1, user-scalable=no");
    } catch (e) {}
  }
  var busListeners = new Map();

  function on(name, cb) {
    if (!busListeners.has(name)) busListeners.set(name, new Set());
    busListeners.get(name).add(cb);
    return cb;
  }
  function off(name, cb) {
    var set = busListeners.get(name);
    if (set && cb) set.delete(cb);
  }
  function emit(name, payload) {
    var set = busListeners.get(name);
    if (!set) return;
    set.forEach(function (cb) {
      try {
        cb(payload);
      } catch (e) {}
    });
  }

  window.__sbElectronBus = { on: on, off: off, emit: emit };

  var pending = new Map();
  var seq = 1;
  window.__sbResult = function (id, payload) {
    var p = pending.get(String(id));
    if (!p) return;
    pending.delete(String(id));
    clearTimeout(p.timer);
    if (payload && payload.__error) p.reject(new Error(payload.__error));
    else p.resolve(payload);
  };
  window.__sbPush = function (msg) {
    try {
      window.dispatchEvent(new CustomEvent("sb-native-push", { detail: msg }));
    } catch (e) {}
    if (!msg || !msg.type) return;
    if (msg.type === "media") {
      if (msg.kind === "m3u8") emit("m3u8-found", msg.url);
      else if (msg.kind === "vtt") {
        var lang = window.__sbSubtitles
          ? window.__sbSubtitles.extractSubtitleLang(msg.url)
          : "unknown";
        emit("subtitle-found", { url: msg.url, lang: lang });
      }
    } else if (msg.type === "blocked") {
      emit("blocked-stats-update", msg.batch);
    } else if (msg.type === "pip") {
      emit(msg.open ? "pip-window-opened" : "pip-window-closed");
    } else if (msg.type === "update") {
      emit("update-progress", { percent: msg.percent, label: msg.label });
    } else if (msg.type === "maximized") {
      emit("window-maximized", !!msg.value);
    } else if (msg.type === "phone-keys") {
      emit("phone-keys", msg);
    }
  };

  window.__sbCall = function (method, args, timeoutMs) {
    return new Promise(function (resolve, reject) {
      var id = String(seq++);
      var ms = timeoutMs == null ? 120000 : timeoutMs;
      var timer = null;
      if (ms > 0) {
        timer = setTimeout(function () {
          if (!pending.has(id)) return;
          pending.delete(id);
          reject(new Error("Native timeout: " + method));
        }, ms);
      }
      pending.set(id, { resolve: resolve, reject: reject, timer: timer });
      try {
        window.sbNative.postMessage(
          JSON.stringify({ id: id, method: method, args: args || {} }),
        );
      } catch (e) {
        clearTimeout(timer);
        pending.delete(id);
        reject(e);
      }
    });
  };

  var call = window.__sbCall;
  var dl = function () {
    return window.__sbDownloads;
  };
  var subs = function () {
    return window.__sbSubtitles;
  };
  var hianime = function () {
    return window.__sbHiAnime;
  };

  var pipOpen = false;
  var PIP_ID = 7;
  var backupPending = false;

  function userDataFile(name) {
    return (boot.userData || "") + "/" + name;
  }

  async function readJson(name, fallback) {
    try {
      var res = await call("fsReadText", { path: userDataFile(name) });
      if (!res || !res.text) return fallback;
      return JSON.parse(res.text);
    } catch (e) {
      return fallback;
    }
  }
  async function writeJson(name, value) {
    await call("fsWriteText", {
      path: userDataFile(name),
      text: JSON.stringify(value, null, 2),
    });
  }

  var DEFAULT_BACKUP = {
    enabled: false,
    path: "",
    keepCount: 5,
    frequency: "startup",
    lastRun: null,
  };

  function shouldRunBackup(settings) {
    if (!settings || !settings.enabled || !settings.path) return false;
    if (settings.frequency === "startup") return true;
    if (!settings.lastRun) return true;
    var diff = Date.now() - new Date(settings.lastRun).getTime();
    if (settings.frequency === "daily") return diff >= 86400000;
    if (settings.frequency === "weekly") return diff >= 604800000;
    if (settings.frequency === "monthly") return diff >= 2592000000;
    return false;
  }

  var TRUSTED = [
    {
      origin: "https://github.com",
      pathPrefix: "/truelockmc/streambert/releases/download/",
      hosts: ["github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com"],
    },
    {
      origin: "https://codeberg.org",
      pathPrefix: "/attachments/",
      hosts: ["codeberg.org"],
    },
  ];

  function trustedUpdate(url) {
    try {
      var u = new URL(url);
      for (var i = 0; i < TRUSTED.length; i++) {
        var s = TRUSTED[i];
        if (u.origin === s.origin && u.pathname.indexOf(s.pathPrefix) === 0) return s;
      }
    } catch (e) {}
    return null;
  }

  var updateJob = 0;

  window.__streambertRequestClose = function () {
    var running = dl() ? dl().runningCount() : 0;
    if (running > 0) {
      emit("confirm-close", { count: running });
      return "pending";
    }
    return "close";
  };

  var api = {
    cleanUserAgent: UA,

    onM3u8Found: function (cb) {
      return on("m3u8-found", function (url) {
        cb(url);
      });
    },
    offM3u8Found: function (h) {
      off("m3u8-found", h);
    },
    onSubtitleFound: function (cb) {
      return on("subtitle-found", function (data) {
        cb(data);
      });
    },
    offSubtitleFound: function (h) {
      off("subtitle-found", h);
    },
    onDownloadProgress: function (cb) {
      return on("download-progress", function (d) {
        cb(d);
      });
    },
    offDownloadProgress: function (h) {
      off("download-progress", h);
    },

    checkDownloader: function () {
      return dl().checkDownloader();
    },
    runDownload: function (args) {
      return dl().runDownload(args);
    },
    getDownloads: function () {
      return Promise.resolve(dl().getDownloads());
    },
    deleteDownload: function (args) {
      return dl().deleteDownload(args);
    },
    showInFolder: function (path) {
      return call("showInFolder", { path: path });
    },
    fileExists: function (path) {
      return dl().fileExists(path);
    },
    scanDirectory: function (path) {
      return call("scanDir", { path: path });
    },
    pickFolder: function () {
      return call("pickFolder", {}, 600000).then(function (r) {
        return (r && r.path) || null;
      });
    },
    openExternal: function (url) {
      return call("openExternal", { url: url });
    },
    openPath: function (filePath) {
      return call("openFile", { path: filePath, positionMs: 0 });
    },
    getInstallPath: function () {
      return call("getInstallPath", {}).then(function (r) {
        return (r && r.path) || "";
      });
    },
    openPathAtTime: function (filePath, seconds, subtitlePaths) {
      var sub = null;
      if (Array.isArray(subtitlePaths) && subtitlePaths.length) {
        var first = subtitlePaths[0];
        sub = typeof first === "string" ? first : first && first.path;
      }
      return call("openFile", {
        path: filePath,
        positionMs: Math.max(0, Math.floor(seconds || 0)) * 1000,
        subtitle: sub || "",
      });
    },
    openStreamExternal: function (args) {
      args = args || {};
      return call("getStreamMeta", { url: args.url || "" }).then(function (meta) {
        meta = meta || {};
        var sub = "";
        if (Array.isArray(args.subtitles) && args.subtitles.length) {
          var s0 = args.subtitles[0];
          sub = typeof s0 === "string" ? s0 : s0 && s0.url ? s0.url : "";
        }
        return call("openStream", {
          url: args.url,
          title: args.title || "",
          referer: meta.referer || "",
          origin: meta.origin || "",
          userAgent: meta.userAgent || UA,
          subtitle: sub,
        });
      });
    },
    pruneSubtitlePaths: function (downloadId) {
      return dl().pruneSubtitles(downloadId);
    },

    onConfirmClose: function (cb) {
      return on("confirm-close", function (data) {
        cb(data);
      });
    },
    offConfirmClose: function (h) {
      off("confirm-close", h);
    },
    respondClose: function (confirm) {
      if (confirm) {
        Promise.resolve(dl().killAll()).finally(function () {
          call("finish", {});
        });
      }
    },

    resolveHiAnime: function (args) {
      return hianime().resolve(args);
    },
    setPlayerVideo: function (args) {
      return hianime().setPlayerVideo(args);
    },
    onHiAnimeProgress: function (cb) {
      var h = function (stage) {
        cb(stage);
      };
      on("hianime-progress", h);
      return function () {
        off("hianime-progress", h);
      };
    },
    debugHiAnime: function () {
      return Promise.resolve({ error: "disabled in packaged builds" });
    },

    getAppVersion: function () {
      return Promise.resolve(boot.version || "2.7.0");
    },

    onWebviewEnterFullscreen: function (cb) {
      return on("webview-enter-fullscreen", function () {
        cb();
      });
    },
    offWebviewEnterFullscreen: function (h) {
      off("webview-enter-fullscreen", h);
    },
    onWebviewLeaveFullscreen: function (cb) {
      return on("webview-leave-fullscreen", function () {
        cb();
      });
    },
    offWebviewLeaveFullscreen: function (h) {
      off("webview-leave-fullscreen", h);
    },

    onBlockedUpdate: function (cb) {
      return on("blocked-stats-update", function (data) {
        cb(data);
      });
    },
    offBlockedUpdate: function (h) {
      off("blocked-stats-update", h);
    },
    getBlockStats: function () {
      return readJson("blockStats.json", { total: 0, domains: {} });
    },

    showNotification: function (opts) {
      opts = opts || {};
      return call("notify", {
        title: opts.title || "Streambert",
        body: opts.body || "",
        silent: !!opts.silent,
      });
    },
    quitApp: function () {
      var answer = window.__streambertRequestClose();
      if (answer === "close") return call("finish", {});
      return Promise.resolve();
    },
    playerStopped: function () {
      return call("playerStopped", {});
    },

    getCacheSize: function () {
      return call("cacheSize", {});
    },
    getDownloadsSize: function () {
      return dl().downloadsSize();
    },
    clearAppCache: function () {
      return call("clearCache", {});
    },
    queryVideoProgress: function (webContentsId) {
      if (webContentsId === PIP_ID && pipOpen) {
        return call("pipQuery", { code: window.__sbProgressJs || "null" }, 8000);
      }
      var el = window.__sbWebviews && window.__sbWebviews.get(webContentsId);
      if (!el || !el.queryAllFrames) return Promise.resolve(null);
      return el.queryAllFrames(window.__sbProgressJs || "null");
    },
    clearWatchData: function () {
      return call("clearCookies", {});
    },
    deleteAllDownloads: function () {
      return dl().deleteAll();
    },
    resetApp: function () {
      downloadsReset();
      return call("resetApp", {});
    },

    searchSubtitles: function (args) {
      return subs().searchSubtitles(args);
    },
    getSubtitleUrl: function (args) {
      return subs().getSubtitleUrl(args && args.fileId);
    },
    downloadSubtitlesForFile: function (args) {
      return subs().downloadForFile(args.filePath, args.selectedSubs, dl());
    },
    deleteSubtitleFile: function (args) {
      return dl().deleteSubtitleFile(args.downloadId, args.subtitlePath);
    },

    wyzieOpenRedeem: function () {
      return call("wyzieRedeem", {}, 600000);
    },
    wyzieValidateKey: async function (key) {
      var trimmed = String(key || "").trim();
      if (!trimmed || trimmed.indexOf("wyzie-") !== 0) {
        return { ok: false, error: "Invalid key, please try again." };
      }
      try {
        var res = await call("http", {
          url: "https://sub.wyzie.io/search?id=550&format=srt&key=" + encodeURIComponent(trimmed),
          method: "GET",
          headers: { "User-Agent": "Streambert" },
          timeoutMs: 12000,
        });
        if (res && (res.status === 401 || res.status === 403)) {
          return { ok: false, error: "Wyzie API key invalid or expired (" + res.status + ")" };
        }
        return { ok: true };
      } catch (e) {
        return { ok: false, error: e.message };
      }
    },

    secureGet: function (key) {
      return call("secureGet", { key: key }).then(function (r) {
        return (r && r.value) || null;
      });
    },
    secureSet: function (key, value) {
      return call("secureSet", { key: key, value: value == null ? "" : String(value) });
    },

    openPipWindow: function (url, title, userAgent) {
      if (!url || url === "about:blank") return Promise.resolve({ ok: false, reason: "no-url" });
      return call("pipOpen", { url: url, title: title || "", userAgent: userAgent || "" }).then(
        function (r) {
          if (r && r.ok) pipOpen = true;
          return r;
        },
      );
    },
    closePipWindow: function () {
      pipOpen = false;
      return call("pipClose", {});
    },
    getPipWebContentsId: function () {
      return Promise.resolve(pipOpen ? PIP_ID : null);
    },
    onPipOpened: function (cb) {
      return on("pip-window-opened", function () {
        pipOpen = true;
        cb();
      });
    },
    offPipOpened: function (h) {
      off("pip-window-opened", h);
    },
    onPipClosed: function (cb) {
      return on("pip-window-closed", function () {
        pipOpen = false;
        cb();
      });
    },
    offPipClosed: function (h) {
      off("pip-window-closed", h);
    },

    windowMinimize: function () {
      return call("windowOp", { op: "minimize" });
    },
    windowToggleMaximize: function () {
      return call("windowOp", { op: "maximize" });
    },
    windowClose: function () {
      return api.quitApp();
    },
    windowIsMaximized: function () {
      return call("windowOp", { op: "isMaximized" }).then(function (r) {
        return !!(r && r.value);
      });
    },
    getPlatform: function () {
      return Promise.resolve("android");
    },
    isTelevision: function () {
      return Promise.resolve(!!boot.television);
    },
    startPhonePairing: function () {
      return call("startPairing", {});
    },
    stopPhonePairing: function () {
      return call("stopPairing", {});
    },
    playNative: function (payload) {
      return call("playNative", payload || {}, -1);
    },
    onPhoneKeysSaved: function (cb) {
      return on("phone-keys", function (data) {
        cb(data);
      });
    },
    offPhoneKeysSaved: function (h) {
      off("phone-keys", h);
    },
    onWindowMaximize: function (cb) {
      return on("window-maximized", function (v) {
        cb(v);
      });
    },
    offWindowMaximize: function (h) {
      off("window-maximized", h);
    },

    getVideoDuration: function (filePath) {
      return call("videoDuration", { path: filePath });
    },
    setZoomFactor: function (factor) {
      var z = Number(factor);
      if (!Number.isFinite(z) || z <= 0) z = 1;
      document.documentElement.style.zoom = String(z);
    },

    detectUpdateFormat: function () {
      return Promise.resolve("apk");
    },
    downloadAndInstallUpdate: async function (args) {
      args = args || {};
      if (args.format !== "apk") return { ok: false, error: "Invalid format" };
      var source = trustedUpdate(args.url);
      if (!source) return { ok: false, error: "Unauthorized update source" };
      var job = ++updateJob;
      var dest = (boot.cache || boot.tmp || "") + "/streambert-update.apk";
      var res = await call(
        "downloadToFile",
        { url: args.url, dest: dest, hosts: source.hosts, id: "update" },
        30 * 60 * 1000,
      );
      if (job !== updateJob) return { ok: false, error: "Cancelled" };
      if (!res || res.ok === false) return { ok: false, error: (res && res.error) || "Download failed" };
      emit("update-progress", { percent: 100, label: "Installing…" });
      var installed = await call("installApk", { path: dest });
      if (!installed || installed.ok === false) {
        return { ok: false, error: (installed && installed.error) || "Could not open the installer" };
      }
      return { ok: true };
    },
    cancelUpdate: function () {
      updateJob++;
      return call("cancelDownload", { id: "update" });
    },
    onUpdateProgress: function (cb) {
      return on("update-progress", function (data) {
        cb(data);
      });
    },
    offUpdateProgress: function (h) {
      off("update-progress", h);
    },

    getScheduledBackupSettings: function () {
      return readJson("scheduled-backup-settings.json", DEFAULT_BACKUP);
    },
    setScheduledBackupSettings: function (settings) {
      return writeJson("scheduled-backup-settings.json", settings).then(function () {
        return { ok: true };
      });
    },
    performScheduledBackup: async function (args) {
      try {
        var settings = (args && args.settings) || {};
        var data = args && args.data;
        if (!settings.path) return { ok: false, error: "No backup path set" };
        var stamp = new Date().toISOString().replace(/[:.]/g, "-").slice(0, 19);
        var filename = "streambert-backup-" + stamp + ".json";
        var fullPath = settings.path.replace(/\/+$/, "") + "/" + filename;
        if (String(settings.path).indexOf("content://") === 0) {
          fullPath = settings.path + "::" + filename;
        }
        await call("fsWriteText", {
          path: fullPath,
          text: JSON.stringify(
            { version: 1, exportedAt: new Date().toISOString(), scheduledBackup: true, data: data },
            null,
            2,
          ),
        });
        var listing = await call("fsReaddir", { path: settings.path });
        var names = (listing && listing.entries) || [];
        var backups = names
          .filter(function (n) {
            return n.indexOf("streambert-backup-") === 0 && n.endsWith(".json");
          })
          .sort()
          .reverse();
        var keep = Math.max(1, Number(settings.keepCount) || 5);
        for (var i = keep; i < backups.length; i++) {
          var p = String(settings.path).indexOf("content://") === 0
            ? settings.path + "::" + backups[i]
            : settings.path.replace(/\/+$/, "") + "/" + backups[i];
          await call("fsUnlink", { path: p });
        }
        var next = Object.assign({}, settings, { lastRun: new Date().toISOString() });
        await writeJson("scheduled-backup-settings.json", next);
        return { ok: true, path: fullPath };
      } catch (e) {
        return { ok: false, error: e.message };
      }
    },
    onScheduledBackupRequested: function (cb) {
      var h = function () {
        cb();
      };
      on("scheduled-backup-requested", h);
      if (backupPending) {
        backupPending = false;
        setTimeout(h, 0);
      }
      return h;
    },
    offScheduledBackupRequested: function (h) {
      off("scheduled-backup-requested", h);
    },

    fetchReleaseImage: async function (url) {
      var allowed = {
        "codeberg.org": 1,
        "github.com": 1,
        "user-images.githubusercontent.com": 1,
        "private-user-images.githubusercontent.com": 1,
        "objects.githubusercontent.com": 1,
      };
      try {
        var u = new URL(url);
        if (u.protocol !== "https:" || !allowed[u.hostname]) return null;
      } catch (e) {
        return null;
      }
      var res = await call("http", {
        url: url,
        method: "GET",
        headers: { "User-Agent": "Streambert-ReleaseNotes" },
        timeoutMs: 20000,
        binary: true,
      });
      if (!res || res.status !== 200 || !res.bodyBase64) return null;
      var ct = (res.contentType || "image/png").split(";")[0];
      if (ct.indexOf("image/") !== 0) return null;
      return "data:" + ct + ";base64," + res.bodyBase64;
    },

    discordRpcSetEnabled: function () {
      return Promise.resolve({ ok: true, supported: false });
    },
    discordRpcUpdateActivity: function () {
      return Promise.resolve({ ok: true, supported: false });
    },
  };

  function downloadsReset() {
    try {
      window.__sbDownloads && (window.__sbDownloads.getDownloads().length = 0);
      window.__sbDownloads && window.__sbDownloads.save();
    } catch (e) {}
  }

  window.electron = api;

  try {
    var meta = document.querySelector('meta[name="viewport"]');
    if (meta) meta.setAttribute("content", "width=1280");
  } catch (e) {}

  readJson("scheduled-backup-settings.json", DEFAULT_BACKUP).then(function (settings) {
    if (shouldRunBackup(settings)) {
      if (busListeners.has("scheduled-backup-requested") && busListeners.get("scheduled-backup-requested").size) {
        emit("scheduled-backup-requested");
      } else {
        backupPending = true;
      }
    }
  });

  window.__sbTest = {
    api: api,
    trustedUpdate: trustedUpdate,
    shouldRunBackup: shouldRunBackup,
  };
})();
