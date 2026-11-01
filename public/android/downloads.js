/* Download registry + Android HLS/file downloader.
 * Desktop Streambert shells out to vid-dl. That binary cannot run on Android,
 * so the same queue, progress events and library entries are driven by the
 * built-in native downloader (multithreaded HLS, direct mp4, sidecar subs).
 */
(function () {
  var boot = window.__SB_BOOT || {};
  var downloads = [];
  var trusted = new Map();
  var BUILTIN = "android-builtin";

  function call(method, args, timeout) {
    return window.__sbCall(method, args, timeout);
  }

  function storePath() {
    return (boot.userData || "") + "/downloads.json";
  }

  function loadSync() {
    try {
      var raw = boot.downloadsJson || "";
      if (!raw) return;
      var parsed = JSON.parse(raw);
      if (Array.isArray(parsed)) downloads = parsed;
    } catch (e) {
      downloads = [];
    }
  }

  function save() {
    try {
      var toSave = downloads.filter(function (d) {
        return d.status !== "downloading" && d.status !== "error";
      });
      call("fsWriteText", { path: storePath(), text: JSON.stringify(toSave, null, 2) }).catch(
        function () {},
      );
    } catch (e) {}
  }

  function emitProgress(update) {
    var bus = window.__sbElectronBus;
    if (bus) bus.emit("download-progress", update);
  }

  function formatBytes(bytes) {
    if (bytes > 1e9) return (bytes / 1e9).toFixed(2) + " GB";
    if (bytes > 1e6) return (bytes / 1e6).toFixed(1) + " MB";
    if (bytes > 1e3) return (bytes / 1e3).toFixed(1) + " KB";
    return bytes + " B";
  }

  loadSync();
  trusted.set(BUILTIN, BUILTIN);

  window.addEventListener("sb-native-push", function (e) {
    var msg = e.detail || {};
    if (msg.type !== "download") return;
    var idx = -1;
    for (var i = 0; i < downloads.length; i++) {
      if (downloads[i].id === msg.id) idx = i;
    }
    if (idx === -1) return;
    var update = {};
    ["progress", "speed", "size", "lastMessage", "status", "filePath", "totalFragments", "completedFragments"].forEach(
      function (k) {
        if (msg[k] !== undefined && msg[k] !== null) update[k] = msg[k];
      },
    );
    downloads[idx] = Object.assign({}, downloads[idx], update);
    if (msg.status === "completed" || msg.status === "error") {
      downloads[idx].completedAt = Date.now();
      if (msg.status === "completed") {
        downloads[idx].progress = 100;
        downloads[idx].logPath = null;
        var subs = downloads[idx].subtitles || [];
        var filePath = downloads[idx].filePath;
        if (subs.length && filePath && window.__sbSubtitles) {
          window.__sbSubtitles
            .downloadForFile(
              filePath,
              subs.map(function (s) {
                return {
                  direct_url: s.url,
                  language: s.lang,
                  file_name: s.name,
                  file_id: s.file_id,
                  via_subdl: String(s.file_id || "").indexOf("subdl_") === 0,
                };
              }),
              api,
            )
            .then(function (res) {
              if (res && res.ok) emitProgress({ id: msg.id, subtitlePaths: downloads[idx].subtitlePaths });
            })
            .catch(function () {});
        }
      }
      save();
    }
    emitProgress(Object.assign({ id: msg.id, status: downloads[idx].status }, update));
  });

  var api = {
    getDownloads: function () {
      return downloads;
    },
    save: save,
    async checkDownloader() {
      trusted.set(BUILTIN, BUILTIN);
      return { exists: true, token: BUILTIN, builtin: true };
    },
    async runDownload(args) {
      args = args || {};
      if (args.token && args.token !== BUILTIN && !trusted.has(args.token)) {
        return { ok: false, error: "Invalid or unknown downloader token" };
      }
      if (!args.m3u8Url || !args.downloadPath) {
        return { ok: false, error: "Missing stream URL or download folder" };
      }
      var id = "dl_" + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
      var entry = {
        id: id,
        name: args.name,
        m3u8Url: args.m3u8Url,
        downloadPath: args.downloadPath,
        filePath: null,
        status: "downloading",
        progress: 0,
        speed: "",
        size: "",
        totalFragments: 0,
        completedFragments: 0,
        lastMessage: "Starting…",
        startedAt: Date.now(),
        completedAt: null,
        mediaId: args.mediaId || null,
        mediaType: args.mediaType || null,
        season: args.season || null,
        episode: args.episode || null,
        posterPath: args.posterPath || null,
        tmdbId: args.tmdbId || args.mediaId || null,
        subtitles: Array.isArray(args.subtitles) ? args.subtitles : [],
        subtitlePaths: [],
        logPath: null,
      };
      downloads = downloads.filter(function (d) {
        return !(
          d.id !== id &&
          d.tmdbId &&
          d.tmdbId === entry.tmdbId &&
          d.mediaType === entry.mediaType &&
          String(d.season || "") === String(entry.season || "") &&
          String(d.episode || "") === String(entry.episode || "")
        );
      });
      downloads.push(entry);
      var meta = {};
      try {
        meta = (await call("getStreamMeta", { url: args.m3u8Url })) || {};
      } catch (e) {}
      var started = await call("startDownload", {
        id: id,
        url: args.m3u8Url,
        destDir: args.downloadPath,
        fileName: args.name || "video",
        referer: meta.referer || "",
        origin: meta.origin || "",
        userAgent: meta.userAgent || (boot.ua || ""),
      });
      if (!started || started.ok === false) {
        entry.status = "error";
        entry.lastMessage = (started && started.error) || "Failed to start downloader";
        emitProgress({ id: id, status: "error", lastMessage: entry.lastMessage });
        return { ok: false, error: entry.lastMessage };
      }
      return { ok: true, id: id };
    },
    async deleteDownload(args) {
      args = args || {};
      var idx = -1;
      for (var i = 0; i < downloads.length; i++) if (downloads[i].id === args.id) idx = i;
      if (idx === -1) return { ok: false };
      var d = downloads[idx];
      if (d.status === "downloading") {
        try {
          await call("cancelDownload", { id: d.id });
        } catch (e) {}
      }
      var errors = 0;
      if (args.filePath) {
        try {
          await call("fsUnlink", { path: args.filePath });
        } catch (e) {
          errors++;
        }
      }
      (d.subtitlePaths || []).forEach(function (sp) {
        var p = typeof sp === "string" ? sp : sp && sp.path;
        if (p) call("fsUnlink", { path: p }).catch(function () {});
      });
      downloads.splice(idx, 1);
      save();
      return { ok: true, errors: errors };
    },
    async deleteAll() {
      var deleted = 0;
      var errors = 0;
      for (var i = 0; i < downloads.length; i++) {
        var d = downloads[i];
        if (d.status === "downloading") {
          try {
            await call("cancelDownload", { id: d.id });
          } catch (e) {}
        }
        if (d.filePath) {
          var r = await call("fsUnlink", { path: d.filePath });
          if (r && r.ok === false) errors++;
          else deleted++;
        }
        (d.subtitlePaths || []).forEach(function (sp) {
          var p = typeof sp === "string" ? sp : sp && sp.path;
          if (p) call("fsUnlink", { path: p }).catch(function () {});
        });
      }
      downloads = [];
      save();
      return { ok: true, deleted: deleted, errors: errors };
    },
    async downloadsSize() {
      var total = 0;
      for (var i = 0; i < downloads.length; i++) {
        if (!downloads[i].filePath) continue;
        try {
          var st = await call("fsStat", { path: downloads[i].filePath });
          if (st && st.size) total += st.size;
        } catch (e) {}
      }
      return { bytes: total };
    },
    async fileExists(path) {
      if (!path) return false;
      try {
        var st = await call("fsStat", { path: path });
        return !!(st && st.exists);
      } catch (e) {
        return false;
      }
    },
    pruneSubtitles: async function (downloadId) {
      var idx = -1;
      for (var i = 0; i < downloads.length; i++) if (downloads[i].id === downloadId) idx = i;
      if (idx < 0) return { ok: true, subtitlePaths: [] };
      var before = downloads[idx].subtitlePaths || [];
      var after = [];
      for (var j = 0; j < before.length; j++) {
        var p = typeof before[j] === "string" ? before[j] : before[j] && before[j].path;
        if (p && (await api.fileExists(p))) after.push(before[j]);
      }
      if (after.length !== before.length) {
        downloads[idx].subtitlePaths = after;
        save();
      }
      return { ok: true, subtitlePaths: after };
    },
    deleteSubtitleFile: async function (downloadId, subtitlePath) {
      if (subtitlePath) {
        try {
          await call("fsUnlink", { path: subtitlePath });
        } catch (e) {}
      }
      if (downloadId) {
        var idx = -1;
        for (var i = 0; i < downloads.length; i++) if (downloads[i].id === downloadId) idx = i;
        if (idx >= 0) {
          downloads[idx].subtitlePaths = (downloads[idx].subtitlePaths || []).filter(function (sp) {
            return sp.path !== subtitlePath;
          });
          save();
        }
      }
      return { ok: true };
    },
    runningCount: function () {
      return downloads.filter(function (d) {
        return d.status === "downloading";
      }).length;
    },
    killAll: async function () {
      for (var i = 0; i < downloads.length; i++) {
        if (downloads[i].status === "downloading") {
          downloads[i].status = "error";
          downloads[i].lastMessage = "Cancelled on exit";
          try {
            await call("cancelDownload", { id: downloads[i].id });
          } catch (e) {}
        }
      }
      save();
    },
    formatBytes: formatBytes,
  };

  window.__sbDownloads = api;

  try {
    if (!localStorage.getItem("streambert_downloaderFolder")) {
      localStorage.setItem("streambert_downloaderFolder", JSON.stringify(BUILTIN));
    }
    if (!localStorage.getItem("streambert_downloadPath") && boot.movies) {
      localStorage.setItem("streambert_downloadPath", JSON.stringify(boot.movies));
    }
  } catch (e) {}
})();
