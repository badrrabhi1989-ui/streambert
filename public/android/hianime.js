/* HiAnime resolver. Same scraping steps as src/ipc/hianime.js.
 * HTTP goes through the native client so CORS and Cloudflare's view of a
 * browser TLS stack stay out of the WebView origin.
 */
(function () {
  var BASE_URL = "https://hianime.at";
  var USER_AGENT =
    (window.__SB_BOOT && window.__SB_BOOT.ua) ||
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
  var OBFUSCATION_KEY = new TextEncoder().encode("otaku-embed-v1");
  var SERVER_NAME = "ZokoAnime";
  var MAX_TEXT = 5 * 1024 * 1024;

  function call(method, args, timeout) {
    return window.__sbCall(method, args, timeout);
  }

  function b64ToBytes(b64) {
    var bin = atob(String(b64).replace(/-/g, "+").replace(/_/g, "/"));
    var out = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }

  function xorDecode(b64) {
    var buf = b64ToBytes(b64);
    var out = new Uint8Array(buf.length);
    for (var i = 0; i < buf.length; i++) {
      out[i] = buf[i] ^ OBFUSCATION_KEY[i % OBFUSCATION_KEY.length];
    }
    return new TextDecoder().decode(out);
  }

  async function hianimeGet(urlStr, opts) {
    opts = opts || {};
    var headers = {
      "User-Agent": USER_AGENT,
      Accept: opts.accept || "*/*",
      "Accept-Language": "en-US,en;q=0.9",
    };
    if (opts.referer) headers.Referer = opts.referer;
    var res = await call("http", {
      url: urlStr,
      method: "GET",
      headers: headers,
      timeoutMs: 12000,
    });
    if (res && res.error) throw new Error(res.error);
    var body = res.body || "";
    if (body.length > MAX_TEXT) throw new Error("response too large");
    return { status: res.status, body: body, url: res.url || urlStr };
  }

  async function hianimeCurl(urlStr, opts) {
    var res = await hianimeGet(urlStr, opts);
    if (/<title>\s*Just a moment/i.test(res.body)) {
      throw new Error("Blocked by Cloudflare (HiAnime)");
    }
    if (res.status < 200 || res.status >= 300) {
      throw new Error("Request failed: HTTP " + res.status + " from " + urlStr);
    }
    return res.body;
  }

  function decodeEntities(s) {
    return String(s)
      .replace(/&#0?39;/g, "'")
      .replace(/&quot;/g, '"')
      .replace(/&lt;/g, "<")
      .replace(/&gt;/g, ">")
      .replace(/&amp;/g, "&");
  }

  function unwrapApiHtml(body) {
    var text = body;
    try {
      var json = JSON.parse(body);
      var parts = [];
      var walk = function (v) {
        if (typeof v === "string") parts.push(v);
        else if (Array.isArray(v)) v.forEach(walk);
        else if (v && typeof v === "object") Object.values(v).forEach(walk);
      };
      walk(json);
      if (parts.length) text = parts.join("\n");
    } catch (e) {}
    return text.replace(/\\"/g, '"').replace(/\\\//g, "/").replace(/\\/g, "");
  }

  function getAttr(tagText, name) {
    var m = String(tagText).match(new RegExp(name + '="([^"]*)"'));
    return m ? m[1] : null;
  }

  async function hianimeSearch(query) {
    var q = encodeURIComponent(query).replace(/%20/g, "+");
    var html = await hianimeCurl(BASE_URL + "/search?keyword=" + q, {
      accept: "text/html",
    });
    var main = html.split('id="main-sidebar"')[0];
    var results = [];
    var re = /<h3 class="film-name">([\s\S]*?)<\/h3>/g;
    var m;
    while ((m = re.exec(main))) {
      var anchor = m[1].match(/<a\s+([^>]*)>/);
      if (!anchor) continue;
      var href = getAttr(anchor[1], "href");
      var title = getAttr(anchor[1], "title");
      if (!href || !title) continue;
      var id = href.split("?")[0].replace(/\/+$/, "").split("/").pop();
      if (!id) continue;
      var jname = getAttr(anchor[1], "data-jname");
      results.push({
        id: id,
        title: decodeEntities(title),
        jname: jname ? decodeEntities(jname) : null,
      });
    }
    return results;
  }

  async function hianimeEpisodes(showId) {
    var numericId = showId.slice(showId.lastIndexOf("-") + 1);
    if (!/^\d+$/.test(numericId)) throw new Error("Invalid show id");
    var body = await hianimeCurl(
      BASE_URL + "/api/theme/episode/list/" + numericId,
      { referer: BASE_URL + "/" },
    );
    var html = unwrapApiHtml(body);
    var episodes = [];
    var chunks = html.split("ep-item").slice(1);
    for (var i = 0; i < chunks.length; i++) {
      var chunk = chunks[i];
      var number = getAttr(chunk, "data-number");
      var epId = getAttr(chunk, "data-id");
      if (!number || !epId || !/^\d+$/.test(epId)) continue;
      if (chunk.indexOf("/watch/" + showId + "?ep=") === -1) continue;
      episodes.push({ epId: epId, number: number });
    }
    return episodes;
  }

  async function hianimeStream(epId, mode) {
    var body = await hianimeCurl(
      BASE_URL + "/api/theme/episode/servers?episodeId=" + epId,
      { referer: BASE_URL + "/" },
    );
    var html = unwrapApiHtml(body);
    var hash = null;
    var chunks = html.split("server-item").slice(1);
    for (var i = 0; i < chunks.length; i++) {
      var chunk = chunks[i];
      if (
        getAttr(chunk, "data-type") === mode &&
        getAttr(chunk, "data-server-name") === SERVER_NAME
      ) {
        hash = getAttr(chunk, "data-hash");
        if (hash) break;
      }
    }
    if (!hash) throw new Error("No " + SERVER_NAME + " server for " + mode);
    var embedUrl = new TextDecoder().decode(b64ToBytes(hash)).trim();
    if (!/^https?:\/\//i.test(embedUrl)) throw new Error("Invalid embed url");
    var referer = embedUrl.replace(/^(https?:\/\/[^/]*).*$/, "$1/");
    var malMatch = embedUrl.match(/\/mal\/(\d+)\//);
    var malId = malMatch ? parseInt(malMatch[1], 10) : null;
    var embedHtml = await hianimeCurl(embedUrl);
    var blobMatch = embedHtml.match(/window\.__P="([^"]*)"/);
    if (!blobMatch) throw new Error("Embed config not found");
    var jsonText = xorDecode(blobMatch[1]);
    var masterUrl = null;
    var subtitle = null;
    try {
      var cfg = JSON.parse(jsonText);
      var findM3u8 = function (v) {
        if (typeof v === "string") return /\.m3u8/i.test(v) ? v : null;
        if (Array.isArray(v)) {
          for (var i = 0; i < v.length; i++) {
            var r = findM3u8(v[i]);
            if (r) return r;
          }
        } else if (v && typeof v === "object") {
          if (typeof v.src === "string" && /\.m3u8/i.test(v.src)) return v.src;
          var vals = Object.values(v);
          for (var j = 0; j < vals.length; j++) {
            var r2 = findM3u8(vals[j]);
            if (r2) return r2;
          }
        }
        return null;
      };
      masterUrl = findM3u8(cfg);
      var subs = Array.isArray(cfg.subtitles) ? cfg.subtitles : [];
      var def = null;
      for (var s = 0; s < subs.length; s++) {
        if (subs[s] && subs[s].default) def = subs[s];
      }
      subtitle = def && def.src ? def.src : null;
    } catch (e) {
      var m = jsonText.match(/"src":"([^"]*\.m3u8[^"]*)"/);
      masterUrl = m ? m[1] : null;
      var parts = jsonText.split(/\},\s*\{/);
      var sub = null;
      for (var k = 0; k < parts.length; k++) {
        if (/"default":true/.test(parts[k]) && /"src":"/.test(parts[k])) sub = parts[k];
      }
      subtitle = sub ? (sub.match(/"src":"([^"]*)"/) || [])[1] || null : null;
    }
    if (!masterUrl) throw new Error("No m3u8 source in embed config");
    masterUrl = masterUrl.replace(/\\\//g, "/");
    if (subtitle) subtitle = subtitle.replace(/\\\//g, "/");
    return {
      masterUrl: masterUrl,
      referer: referer,
      malId: malId,
      subtitle: subtitle,
      resolution: "auto",
    };
  }

  function sanitizeTitle(t) {
    return String(t || "")
      .replace(/[''`´]/g, "")
      .replace(/[:!.]/g, "")
      .replace(/\s+/g, " ")
      .trim();
  }

  function normalizeTitle(t) {
    return String(t || "")
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, " ")
      .trim();
  }

  async function anilistSeasonTitle(baseTitle, seasonNumber) {
    var fallback = {
      title: baseTitle,
      romaji: null,
      episodes: null,
      nextTitle: null,
      nextRomaji: null,
    };
    try {
      var query =
        "query($search:String){Media(search:$search,type:ANIME,sort:SEARCH_MATCH){title{english romaji}episodes relations{edges{relationType node{type format title{english romaji}episodes startDate{year}seasonYear}}}}}";
      var res = await call("http", {
        url: "https://graphql.anilist.co/",
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Accept: "application/json",
          "User-Agent": USER_AGENT,
        },
        body: JSON.stringify({ query: query, variables: { search: baseTitle } }),
        timeoutMs: 8000,
      });
      if (!res || res.error || res.status < 200 || res.status >= 300) return fallback;
      var json = JSON.parse(res.body || "{}");
      var media = json && json.data && json.data.Media;
      if (!media) return fallback;
      var s1Romaji = (media.title && media.title.romaji) || null;
      var s1Episodes = media.episodes || null;
      var edges = (media.relations && media.relations.edges) || [];
      var sequels = edges
        .filter(function (e) {
          return (
            e.relationType === "SEQUEL" &&
            e.node &&
            e.node.type === "ANIME" &&
            (e.node.format === "TV" || e.node.format === "TV_SHORT")
          );
        })
        .sort(function (a, b) {
          var ya = (a.node.startDate && a.node.startDate.year) || a.node.seasonYear || 9999;
          var yb = (b.node.startDate && b.node.startDate.year) || b.node.seasonYear || 9999;
          return ya - yb;
        });
      var getTitle = function (node) {
        return (node.title && (node.title.english || node.title.romaji)) || null;
      };
      var getRomaji = function (node) {
        return (node.title && node.title.romaji) || null;
      };
      if (seasonNumber <= 1) {
        var next = sequels[0] ? sequels[0].node : null;
        return {
          title: (media.title && media.title.english) || baseTitle,
          romaji: s1Romaji,
          episodes: s1Episodes,
          nextTitle: next ? getTitle(next) : null,
          nextRomaji: next ? getRomaji(next) : null,
        };
      }
      var target = sequels[seasonNumber - 2];
      if (!target) return Object.assign({}, fallback, { romaji: s1Romaji });
      var nextNode = sequels[seasonNumber - 1] ? sequels[seasonNumber - 1].node : null;
      return {
        title: getTitle(target.node) || baseTitle,
        romaji: getRomaji(target.node) || s1Romaji,
        episodes: target.node.episodes || null,
        nextTitle: nextNode ? getTitle(nextNode) : null,
        nextRomaji: nextNode ? getRomaji(nextNode) : null,
      };
    } catch (e) {
      return fallback;
    }
  }

  var SHOW_CACHE_TTL = 10 * 60 * 1000;
  var episodeCache = new Map();
  async function cachedEpisodes(showId) {
    var hit = episodeCache.get(showId);
    if (hit && Date.now() - hit.ts < SHOW_CACHE_TTL) return hit.episodes;
    var episodes = await hianimeEpisodes(showId);
    if (episodes.length) episodeCache.set(showId, { ts: Date.now(), episodes: episodes });
    return episodes;
  }

  var SHOW_LOOKUP_TTL = 30 * 60 * 1000;
  var showCache = new Map();
  async function findShow(candidates) {
    var seen = new Set();
    var unique = candidates.filter(function (c) {
      var n = normalizeTitle(c);
      if (!n || seen.has(n)) return false;
      seen.add(n);
      return true;
    });
    var cacheKey = Array.from(seen).join("|");
    var hit = showCache.get(cacheKey);
    if (hit && Date.now() - hit.ts < SHOW_LOOKUP_TTL) return hit.show;
    var fallback = null;
    var found = null;
    for (var i = 0; i < unique.length; i++) {
      var results;
      try {
        results = await hianimeSearch(unique[i]);
      } catch (e) {
        if (/Cloudflare/i.test(e.message)) throw e;
        continue;
      }
      if (!results.length) continue;
      var exact = null;
      for (var r = 0; r < results.length; r++) {
        if (
          seen.has(normalizeTitle(results[r].title)) ||
          (results[r].jname && seen.has(normalizeTitle(results[r].jname)))
        ) {
          exact = results[r];
          break;
        }
      }
      if (exact) {
        found = exact;
        break;
      }
      if (!fallback) fallback = results[0];
    }
    var show = found || fallback;
    if (show) {
      if (showCache.size > 200) showCache.delete(showCache.keys().next().value);
      showCache.set(cacheKey, { ts: Date.now(), show: show });
    }
    return show;
  }

  function asString(v, max) {
    return typeof v === "string" ? v.slice(0, max || 300) : "";
  }
  function asPosInt(v, fallback) {
    var n = Number(v);
    return Number.isFinite(n) && n >= 0 && n < 100000 ? n : fallback;
  }

  function report(stage) {
    var bus = window.__sbElectronBus;
    if (bus) bus.emit("hianime-progress", stage);
  }

  window.__sbHiAnime = {
    hianimeSearch: hianimeSearch,
    hianimeEpisodes: hianimeEpisodes,
    hianimeStream: hianimeStream,
    unwrapApiHtml: unwrapApiHtml,
    xorDecode: xorDecode,
    async resolve(args) {
      args = args || {};
      try {
        var title = asString(args.title);
        if (!title) return { ok: false, error: "Missing title" };
        var isMovie = args.isMovie === true;
        var season = asPosInt(args.seasonNumber, 1) || 1;
        var episodeNumber = asPosInt(args.episodeNumber, 1) || 1;
        var mode = args.translationType === "dub" ? "dub" : "sub";
        report("search");
        var anilistResult = isMovie
          ? { title: title, romaji: null, episodes: null, nextTitle: null, nextRomaji: null }
          : await anilistSeasonTitle(title, season);
        var searchTitle = anilistResult.title;
        var adjusted = episodeNumber;
        if (
          !isMovie &&
          anilistResult.episodes &&
          episodeNumber > anilistResult.episodes &&
          anilistResult.nextTitle
        ) {
          adjusted = episodeNumber - anilistResult.episodes;
          searchTitle = anilistResult.nextTitle;
        }
        var candidates = [];
        function add(v) {
          if (v && candidates.indexOf(v) === -1) candidates.push(v);
        }
        add(searchTitle);
        add(sanitizeTitle(searchTitle));
        if (anilistResult.romaji && searchTitle === anilistResult.title) add(anilistResult.romaji);
        if (anilistResult.nextRomaji && searchTitle === anilistResult.nextTitle)
          add(anilistResult.nextRomaji);
        add(title);
        add(sanitizeTitle(title));
        var show = await findShow(candidates);
        if (!show) return { ok: false, error: "No results for: " + searchTitle };
        report("episodes");
        var episodes = await cachedEpisodes(show.id);
        if (!episodes.length) return { ok: false, error: "No episodes found for: " + show.title };
        var wanted = isMovie ? "1" : String(adjusted);
        var episode = null;
        for (var i = 0; i < episodes.length; i++) {
          if (episodes[i].number === wanted) {
            episode = episodes[i];
            break;
          }
        }
        if (!episode) {
          for (var j = 0; j < episodes.length; j++) {
            if (parseFloat(episodes[j].number) === Number(wanted)) {
              episode = episodes[j];
              break;
            }
          }
        }
        if (!episode && isMovie) episode = episodes[0];
        if (!episode) return { ok: false, error: "Episode " + wanted + " not found" };
        report("stream");
        var stream = await hianimeStream(episode.epId, mode);
        return {
          ok: true,
          url: stream.masterUrl,
          resolution: stream.resolution,
          sourceName: SERVER_NAME,
          isDirectMp4: false,
          referer: stream.referer,
          subtitle: stream.subtitle,
          malId: stream.malId,
          searchTitle: searchTitle,
          matchedTitle: show.title,
        };
      } catch (e) {
        return { ok: false, error: String((e && e.message) || e).slice(0, 200) };
      }
    },
    async setPlayerVideo(args) {
      args = args || {};
      var res = await call("setPlayerConfig", {
        url: asString(args.url, 4096),
        referer: asString(args.referer, 2048) || BASE_URL + "/",
        subtitle: asString(args.subtitle, 4096) || "",
        startTime: asPosInt(args.startTime, 0),
      });
      if (res && res.error) throw new Error(res.error);
      return res;
    },
  };
})();
