/* Subtitle search (SubDL + Wyzie), ZIP extract, and sidecar files.
 * Mirrors src/ipc/subtitles.js using the native HTTP/file bridge.
 */
(function () {
  var SUBTITLE_EXTS = { srt: 1, vtt: 1, ass: 1, ssa: 1 };
  var ZIP_MAX = 10 * 1024 * 1024;

  function call(method, args, timeout) {
    return window.__sbCall(method, args, timeout);
  }

  function u16(view, offset) {
    return view.getUint16(offset, true);
  }
  function u32(view, offset) {
    return view.getUint32(offset, true);
  }

  async function inflateRaw(bytes) {
    if (typeof DecompressionStream !== "function") {
      throw new Error("deflate not supported");
    }
    var ds = new DecompressionStream("deflate-raw");
    var stream = new Blob([bytes]).stream().pipeThrough(ds);
    var buf = await new Response(stream).arrayBuffer();
    if (buf.byteLength > ZIP_MAX) throw new Error("zip too large");
    return new Uint8Array(buf);
  }

  async function extractFirstSubtitleFromZip(bytes) {
    var view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    var offset = 0;
    while (offset < bytes.length - 30) {
      if (
        bytes[offset] === 0x50 &&
        bytes[offset + 1] === 0x4b &&
        bytes[offset + 2] === 0x03 &&
        bytes[offset + 3] === 0x04
      ) {
        var compression = u16(view, offset + 8);
        var compressedSize = u32(view, offset + 18);
        var fileNameLen = u16(view, offset + 26);
        var extraLen = u16(view, offset + 28);
        var nameBytes = bytes.slice(offset + 30, offset + 30 + fileNameLen);
        var rawFileName = new TextDecoder().decode(nameBytes);
        var dataOffset = offset + 30 + fileNameLen + extraLen;
        var fileName = rawFileName.split("/").pop().split("\\").pop();
        var ext = (fileName.toLowerCase().split(".").pop() || "");
        if (SUBTITLE_EXTS[ext]) {
          var compressed = bytes.slice(dataOffset, dataOffset + compressedSize);
          var data = null;
          if (compression === 0) {
            if (compressed.length > ZIP_MAX) {
              offset = dataOffset + compressedSize;
              continue;
            }
            data = compressed;
          } else if (compression === 8) {
            try {
              data = await inflateRaw(compressed);
            } catch (e) {
              offset = dataOffset + compressedSize;
              continue;
            }
          } else {
            offset = dataOffset + compressedSize;
            continue;
          }
          return { data: data, name: fileName };
        }
        offset = dataOffset + compressedSize;
      } else {
        offset++;
      }
    }
    return null;
  }

  function extractSubtitleLang(url) {
    try {
      var u = new URL(url);
      var params = ["lang", "language", "locale", "sub", "l"];
      for (var i = 0; i < params.length; i++) {
        var v = u.searchParams.get(params[i]);
        if (v && v.length >= 2 && v.length <= 20) return v.toLowerCase();
      }
      var filename = u.pathname.split("/").filter(Boolean).pop() || "";
      var fileMatch = filename.match(/[._-]([a-z]{2,3})[._-]?(vtt|srt|ass)?$/i);
      if (fileMatch) return fileMatch[1].toLowerCase();
      var segments = u.pathname.split("/").filter(Boolean);
      for (var s = 0; s < segments.length - 1; s++) {
        if (/^[a-z]{2,3}(-[A-Z]{2})?$/.test(segments[s])) return segments[s].toLowerCase();
      }
    } catch (e) {}
    return "unknown";
  }

  async function httpText(url, headers, ms) {
    var res = await call(
      "http",
      { url: url, method: "GET", headers: headers || {}, timeoutMs: ms || 12000 },
      ms || 20000,
    );
    return res;
  }

  async function httpBytes(url, headers, ms) {
    var res = await call(
      "http",
      {
        url: url,
        method: "GET",
        headers: headers || {},
        timeoutMs: ms || 30000,
        binary: true,
      },
      (ms || 30000) + 5000,
    );
    if (!res || res.error) throw new Error((res && res.error) || "download failed");
    if (res.status < 200 || res.status >= 300) {
      throw new Error("HTTP " + res.status);
    }
    var bin = atob(res.bodyBase64 || "");
    var out = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }

  function toSubDLLang(lang) {
    if (!lang) return "";
    return String(lang).split("-")[0].toUpperCase();
  }

  async function searchSubDL(args) {
    try {
      var params = new URLSearchParams({
        api_key: args.subdlApiKey,
        tmdb_id: String(args.tmdbId),
        type: args.mediaType === "tv" ? "tv" : "movie",
        subs_per_page: "30",
      });
      if (args.mediaType === "tv" && args.season != null)
        params.set("season_number", String(args.season));
      if (args.mediaType === "tv" && args.episode != null)
        params.set("episode_number", String(args.episode));
      if (args.languages) params.set("languages", toSubDLLang(args.languages));
      var res = await httpText(
        "https://api.subdl.com/api/v1/subtitles?" + params.toString(),
        { "User-Agent": "Streambert" },
        12000,
      );
      if (!res || res.status < 200 || res.status >= 300) {
        return { ok: false, error: "SubDL error " + (res && res.status) + ": " + ((res && res.body) || "") };
      }
      var data = JSON.parse(res.body || "{}");
      if (!data.status) return { ok: false, error: "SubDL returned no results" };
      var results = (data.subtitles || []).map(function (s) {
        return {
          file_id: "subdl_" + s.sd_id + "_" + encodeURIComponent(s.url),
          file_name: s.name || s.release_name || "",
          language: (s.lang || "").toLowerCase(),
          release: s.release_name || s.name || "",
          uploader: s.author || "SubDL",
          download_count: s.downloads || 0,
          hearing_impaired: !!s.hi,
          ai_translated: false,
          machine_translated: false,
          ratings: 0,
          fps: null,
          from_trusted: false,
          via_subdl: true,
        };
      });
      if (!results.length) return { ok: false, error: "SubDL: no results" };
      return { ok: true, results: results, via_subdl: true };
    } catch (e) {
      var msg = /abort|timeout/i.test(e.message)
        ? "SubDL timed out, server may be temporarily unavailable"
        : e.message;
      return { ok: false, error: msg };
    }
  }

  async function searchWyzie(args) {
    try {
      var params = new URLSearchParams({ id: String(args.tmdbId), format: "srt" });
      if (args.languages) params.set("language", args.languages);
      if (args.mediaType === "tv" && args.season != null) params.set("season", String(args.season));
      if (args.mediaType === "tv" && args.episode != null)
        params.set("episode", String(args.episode));
      if (args.wyzieApiKey) params.set("key", args.wyzieApiKey);
      var baseUrl = args.wyzieApiKey ? "https://sub.wyzie.io/search" : "https://subs.wyzie.ru/search";
      var res = await httpText(baseUrl + "?" + params.toString(), {}, 12000);
      if (!res || res.status < 200 || res.status >= 300) {
        if (res && (res.status === 401 || res.status === 403)) {
          return {
            ok: false,
            error: "Wyzie API key invalid or expired (" + res.status + ")",
            wyzie_auth_error: true,
          };
        }
        return { ok: false, error: "Wyzie error " + (res && res.status) };
      }
      var data = JSON.parse(res.body || "[]");
      var rows = Array.isArray(data) ? data : [];
      var results = [];
      for (var i = 0; i < rows.length; i++) {
        var r = rows[i];
        if (!r.url) continue;
        var rawUrl = r.url || "";
        var fullUrl = rawUrl.indexOf("http") === 0
          ? rawUrl
          : "https://subs.wyzie.ru" + (rawUrl.charAt(0) === "/" ? "" : "/") + rawUrl;
        var displayName =
          r.display_name || r.name || r.release_name || r.title || r.SubFileName || r.fileName || "";
        var lang = (r.language || "").toUpperCase();
        var hiTag = r.isHearingImpaired ? " [HI]" : "";
        var aiTag = r.isAiTranslated ? " [AI]" : "";
        var src = r.source ? " · " + r.source : "";
        var fallback = lang + " subtitle" + hiTag + aiTag + src + " #" + (i + 1);
        results.push({
          file_id: "wyzie_" + i + "_" + encodeURIComponent(fullUrl),
          direct_url: fullUrl,
          file_name: displayName || fallback,
          language: r.language || "",
          release: displayName || fallback,
          uploader: "Wyzie",
          download_count: 0,
          hearing_impaired: !!r.isHearingImpaired,
          ai_translated: !!r.isAiTranslated,
          machine_translated: false,
          ratings: 0,
          fps: null,
          from_trusted: false,
          via_wyzie: true,
          original_source: r.source || "",
        });
      }
      if (!results.length) return { ok: false, error: "Wyzie: no results" };
      return { ok: true, results: results, via_wyzie: true };
    } catch (e) {
      var msg = /abort|timeout/i.test(e.message)
        ? "Subtitle service timed out, it may be temporarily down. Try adding a free SubDL API key in Settings for reliable results."
        : e.message;
      return { ok: false, error: msg };
    }
  }

  async function searchSubtitles(args) {
    args = args || {};
    var errors = [];
    if (args.subdlApiKey) {
      var r = await searchSubDL(args);
      if (r.ok) return r;
      errors.push(r.error);
    }
    var w = await searchWyzie(args);
    if (w.ok) return w;
    errors.push(w.error);
    var allTimedOut = errors.every(function (e) {
      return e && e.indexOf("timed out") !== -1;
    });
    return {
      ok: false,
      error: allTimedOut
        ? "Subtitle service timed out, it may be temporarily down. Add a free SubDL API key in Settings for reliable results."
        : errors.length
          ? errors.join(" · ")
          : "No subtitles found. Try a different language or add a SubDL API key in Settings.",
    };
  }

  function joinPath(dir, name) {
    if (!dir) return name;
    if (dir.indexOf("content://") === 0) return dir + "::" + name;
    return dir.replace(/\/+$/, "") + "/" + name;
  }

  function parentDir(p) {
    if (!p) return "";
    if (p.indexOf("content://") === 0 && p.indexOf("::") !== -1) return p.split("::")[0];
    var i = p.lastIndexOf("/");
    return i <= 0 ? p : p.slice(0, i);
  }

  function baseName(p) {
    var name = String(p || "").split("::").pop().split("/").pop();
    var dot = name.lastIndexOf(".");
    return dot > 0 ? name.slice(0, dot) : name;
  }

  async function writeBytes(path, bytes) {
    var bin = "";
    var chunk = 0x8000;
    for (var i = 0; i < bytes.length; i += chunk) {
      bin += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
    }
    return call("fsWriteBase64", { path: path, base64: btoa(bin) });
  }

  async function getSubtitleUrl(fileId) {
    try {
      var id = String(fileId || "");
      if (id.indexOf("subdl_") === 0) {
        var parts = id.split("_");
        var subdlPath = decodeURIComponent(parts.slice(2).join("_"));
        var bytes = await httpBytes("https://dl.subdl.com" + subdlPath, { "User-Agent": "Streambert" }, 30000);
        var extracted = await extractFirstSubtitleFromZip(bytes);
        if (!extracted) return { ok: false, error: "No subtitle file found in SubDL ZIP" };
        var safeName = extracted.name.split("/").pop();
        var tmp = window.__SB_BOOT.tmp + "/streambert_sub_" + Date.now() + "_" + safeName;
        await writeBytes(tmp, extracted.data);
        setTimeout(function () {
          call("fsUnlink", { path: tmp }).catch(function () {});
        }, 5 * 60 * 1000);
        return {
          ok: true,
          url: "file://" + tmp,
          file_name: extracted.name,
          remaining: null,
          reset_time: null,
          via_subdl: true,
        };
      }
      if (id.indexOf("wyzie_") === 0) {
        var url = decodeURIComponent(id.split("_").slice(2).join("_"));
        return { ok: true, url: url, file_name: "", remaining: null, reset_time: null, via_wyzie: true };
      }
      return { ok: false, error: "Unknown subtitle source" };
    } catch (e) {
      return { ok: false, error: e.message };
    }
  }

  window.__sbSubtitles = {
    extractSubtitleLang: extractSubtitleLang,
    extractFirstSubtitleFromZip: extractFirstSubtitleFromZip,
    searchSubtitles: searchSubtitles,
    getSubtitleUrl: getSubtitleUrl,
    parentDir: parentDir,
    baseName: baseName,
    joinPath: joinPath,
    writeBytes: writeBytes,
    httpBytes: httpBytes,
    async downloadForFile(filePath, selectedSubs, downloadsApi) {
      try {
        var stat = await call("fsStat", { path: filePath });
        if (!stat || !stat.exists || !stat.isFile) {
          return { ok: false, error: "Target file does not exist" };
        }
        var downloads = downloadsApi.getDownloads();
        var dir = parentDir(filePath);
        var knownFile = downloads.some(function (d) {
          return d.filePath === filePath;
        });
        var knownDir = downloads.some(function (d) {
          if (!d.downloadPath) return false;
          return dir === d.downloadPath || dir.indexOf(d.downloadPath + "/") === 0;
        });
        if (!knownFile && !knownDir) {
          return { ok: false, error: "File is not in a known download directory" };
        }
        var base = baseName(filePath);
        var results = [];
        var langCounter = {};
        for (var i = 0; i < (selectedSubs || []).length; i++) {
          var sub = selectedSubs[i];
          try {
            var langCode = String(sub.language || sub.lang || "unknown").replace(/[^a-z0-9_-]/gi, "");
            var fileData = null;
            var ext = "srt";
            var fid = String(sub.file_id || "");
            if (fid.indexOf("subdl_") === 0) {
              var parts = fid.split("_");
              var subdlPath = decodeURIComponent(parts.slice(2).join("_"));
              var zip = await httpBytes("https://dl.subdl.com" + subdlPath, { "User-Agent": "Streambert" }, 30000);
              var extracted = await extractFirstSubtitleFromZip(zip);
              if (!extracted) continue;
              fileData = extracted.data;
              ext = (extracted.name.split(".").pop() || "").toLowerCase();
              if (!SUBTITLE_EXTS[ext]) continue;
            } else {
              var url = sub.direct_url;
              if (!url && fid.indexOf("wyzie_") === 0) {
                url = decodeURIComponent(fid.split("_").slice(2).join("_"));
              }
              if (!url) continue;
              fileData = await httpBytes(url, {}, 30000);
              var urlExt = url.split("?")[0].split(".").pop().toLowerCase();
              ext = SUBTITLE_EXTS[urlExt] ? urlExt : "srt";
            }
            var lIdx = langCounter[langCode] || 0;
            langCounter[langCode] = lIdx + 1;
            var suffix = lIdx > 0 ? "." + lIdx : "";
            var dest = joinPath(dir, base + "." + langCode + suffix + "." + ext);
            await writeBytes(dest, fileData);
            results.push({
              lang: langCode,
              path: dest,
              file_id: sub.file_id || null,
              release: sub.release || sub.file_name || null,
              source: sub.via_subdl ? "subdl" : "wyzie",
            });
          } catch (subErr) {}
        }
        if (results.length && downloadsApi) {
          var list = downloadsApi.getDownloads();
          var idx = -1;
          for (var d = 0; d < list.length; d++) {
            if (list[d].filePath === filePath) idx = d;
          }
          if (idx >= 0) {
            var existing = list[idx].subtitlePaths || [];
            var ids = {};
            existing.forEach(function (s) {
              if (s.file_id) ids[s.file_id] = true;
            });
            list[idx].subtitlePaths = existing.concat(
              results.filter(function (r) {
                return !r.file_id || !ids[r.file_id];
              }),
            );
            downloadsApi.save();
          }
        }
        return { ok: true, subtitlePaths: results };
      } catch (e) {
        return { ok: false, error: e.message };
      }
    },
  };
})();
