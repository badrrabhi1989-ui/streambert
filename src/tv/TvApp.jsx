import { useEffect, useRef, useState } from "react";
import { fetchAnilistData, fetchImdbId, getAnimeSourceUrl, getSourceUrl, imgUrl, tmdbFetch, PLAYER_SOURCES } from "../utils/api";
import { secureStorage } from "../utils/storage";
import PhoneKeyPairing from "../components/PhoneKeyPairing";
import { directionFromKey, isActivateKey, pickNext } from "./tvFocus";
import "./tv.css";

const SHELF = "emberlane-shelf";
const PLAYER_PREF = "reelix-player";
const AUDIO_PREF = "reelix-audio-lang";
const PREFER = ["vidrift", "vidsrc", "vidfast", "vidlove", "aether", "vidnest", "frembed"];
const AUDIO_LANGS = [
  ["", "Default"],
  ["en", "English"],
  ["ar", "Arabic"],
  ["fr", "French"],
  ["es", "Spanish"],
  ["de", "German"],
  ["pt", "Portuguese"],
  ["it", "Italian"],
  ["tr", "Turkish"],
  ["hi", "Hindi"],
  ["ja", "Japanese"],
  ["ko", "Korean"],
  ["zh", "Chinese"],
  ["ru", "Russian"],
  ["pl", "Polish"],
];
const RAIL = [
  ["home", "Home", HomeIcon],
  ["movies", "Movies", MovieIcon],
  ["series", "Series", SeriesIcon],
  ["search", "Search", SearchIcon],
  ["library", "Library", LibraryIcon],
  ["settings", "Settings", SettingsIcon],
];

function loadShelf() {
  try {
    const parsed = JSON.parse(localStorage.getItem(SHELF) || "[]");
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function remember(entry) {
  const list = loadShelf().filter(
    (item) => !(item.id === entry.id && item.type === entry.type && item.season === entry.season && item.episode === entry.episode),
  );
  list.unshift(entry);
  localStorage.setItem(SHELF, JSON.stringify(list.slice(0, 24)));
}

function visible(el) {
  const r = el.getBoundingClientRect();
  return r.width > 0 && r.height > 0;
}

function moveFocus(direction) {
  const asking = document.querySelector(".ask");
  const root = asking || document;
  const items = [...root.querySelectorAll(".tv-item")].filter(visible);
  if (!items.length) return;
  const rects = items.map((el) => {
    const r = el.getBoundingClientRect();
    return { left: r.left, top: r.top, right: r.right, bottom: r.bottom };
  });
  const index = items.indexOf(document.activeElement);
  const next = pickNext(rects, index, direction);
  const el = items[next < 0 ? (index < 0 ? 0 : index) : next];
  if (!el || el === document.activeElement) return;
  el.focus({ preventScroll: true });
  el.scrollIntoView({ block: "nearest", inline: "nearest" });
}

function isLikelyAnime(item) {
  if (!item) return false;
  const genres = item.genres || [];
  const ids = item.genre_ids || genres.map((entry) => entry.id).filter(Boolean);
  const names = genres.map((entry) => (entry.name || "").toLowerCase());
  const animated = ids.includes(16) || names.some((name) => name === "animation" || name === "anime");
  const animeNamed = names.includes("anime");
  return (animated && item.original_language === "ja") || animeNamed;
}

function variantRank(entry) {
  if (entry.variant !== "sub" && entry.variant !== "dub") return 0;
  const pref = localStorage.getItem(AUDIO_PREF) || "";
  if (pref === "ja") return entry.variant === "sub" ? 1 : 2;
  if (pref) return entry.variant === "dub" ? 1 : 2;
  return entry.variant === "sub" ? 1 : 2;
}

async function collectSources(type, id, season, episode, apiKey, item) {
  let imdb = null;
  try {
    imdb = await fetchImdbId(type, id, apiKey);
  } catch {
    imdb = null;
  }
  const list = [];
  for (const src of PLAYER_SOURCES) {
    if (src.async || src.idType === "kinopoisk") continue;
    if (type !== "movie" && !src.tvUrl) continue;
    let ident = String(id);
    if (src.idType === "imdb") {
      if (!imdb) continue;
      ident = imdb;
    }
    const url = getSourceUrl(src.id, type, ident, season, episode);
    if (url) list.push({ id: src.id, label: src.label, url });
  }
  if (isLikelyAnime(item)) {
    try {
      const data = await fetchAnilistData(titleOf(item), "ANIME", id);
      if (data?.id) {
        const ep = episode || 1;
        const sub = getAnimeSourceUrl("vidnest", data.id, ep, "sub");
        const dub = getAnimeSourceUrl("vidnest", data.id, ep, "dub");
        if (sub) list.push({ id: "vidnest", label: "Vidnest · Subbed", url: sub, variant: "sub" });
        if (dub) list.push({ id: "vidnest", label: "Vidnest · Dubbed", url: dub, variant: "dub" });
      }
    } catch {
      /* the regular movie and TV sources still play */
    }
  }
  list.sort((a, b) => {
    const ia = PREFER.indexOf(a.id);
    const ib = PREFER.indexOf(b.id);
    const base = (ia < 0 ? 50 : ia) - (ib < 0 ? 50 : ib);
    if (base !== 0) return base;
    return variantRank(a) - variantRank(b);
  });
  return list;
}

function titleOf(item) {
  return item?.title || item?.name || "Untitled";
}

function yearOf(item) {
  const raw = item?.release_date || item?.first_air_date || "";
  return raw.slice(0, 4);
}

function kindOf(item) {
  return item?.media_type || item?.type || (item?.name && !item?.title ? "tv" : "movie");
}

function art(item, field) {
  if (!item) return null;
  if (field === "poster") return item.poster_path || item.poster || null;
  return item.backdrop_path || item.backdrop || item.poster_path || item.poster || null;
}

export default function TvApp() {
  const [apiKey, setApiKey] = useState("");
  const [ready, setReady] = useState(false);
  const [view, setView] = useState("home");
  const [detail, setDetail] = useState(null);
  const [logo, setLogo] = useState(null);
  const [home, setHome] = useState(null);
  const [grid, setGrid] = useState([]);
  const [query, setQuery] = useState("");
  const [results, setResults] = useState([]);
  const [shelf, setShelf] = useState(loadShelf);
  const [season, setSeason] = useState(1);
  const [episodes, setEpisodes] = useState([]);
  const [cast, setCast] = useState([]);
  const [toast, setToast] = useState("");
  const [draftKey, setDraftKey] = useState("");
  const [spot, setSpot] = useState(null);
  const [ask, setAsk] = useState(null);
  const [playerMode, setPlayerMode] = useState(() => localStorage.getItem(PLAYER_PREF) || "builtin");
  const [audioLang, setAudioLang] = useState(() => localStorage.getItem(AUDIO_PREF) || "");
  const backRef = useRef(() => {});

  useEffect(() => {
    document.documentElement.classList.add("emberlane");
    document.documentElement.style.cursor = "none";
    let live = true;
    secureStorage.get("apikey").then((value) => {
      if (!live) return;
      setApiKey(value || "");
      setDraftKey(value || "");
      setReady(true);
    });
    const onKeys = () => {
      secureStorage.get("apikey").then((value) => {
        setApiKey(value || "");
        setDraftKey(value || "");
        setToast("Saved from your phone.");
      });
    };
    window.addEventListener("streambert:phone-keys", onKeys);
    const off = window.electron?.onPhoneKeysSaved?.(() => {
      window.dispatchEvent(new CustomEvent("streambert:phone-keys"));
    });
    return () => {
      live = false;
      window.removeEventListener("streambert:phone-keys", onKeys);
      window.electron?.offPhoneKeysSaved?.(off);
    };
  }, []);

  useEffect(() => {
    window.__streambertHandleBack = () => (backRef.current() ? "handled" : "close");
    const onKey = (event) => {
      if (event.metaKey || event.ctrlKey || event.altKey) return;
      const typing = document.activeElement?.tagName === "INPUT" || document.activeElement?.tagName === "TEXTAREA";
      const direction = directionFromKey(event.key, event.keyCode);
      if (direction) {
        if (typing && (direction === "left" || direction === "right")) return;
        event.preventDefault();
        event.stopPropagation();
        moveFocus(direction);
        return;
      }
      if (isActivateKey(event.key, event.keyCode)) {
        const el = document.activeElement;
        if (el?.classList?.contains("tv-item") && el.tagName !== "INPUT" && el.tagName !== "TEXTAREA") {
          event.preventDefault();
          el.click();
        }
        return;
      }
      if ((event.key === "Escape" || event.key === "Backspace" || event.keyCode === 4) && !typing) {
        event.preventDefault();
        event.stopPropagation();
        window.__streambertHandleBack?.();
      }
    };
    window.addEventListener("keydown", onKey, true);
    return () => {
      window.removeEventListener("keydown", onKey, true);
      delete window.__streambertHandleBack;
    };
  }, []);

  useEffect(() => {
    const timer = setTimeout(() => {
      const el = (ask ? document.querySelector(".ask [data-autofocus='1']") : null)
        || document.querySelector("[data-autofocus='1']")
        || document.querySelector(".stage .tv-item");
      el?.focus();
    }, 60);
    return () => clearTimeout(timer);
  }, [view, detail?.id, season, apiKey, ready, ask]);

  useEffect(() => {
    setSpot(null);
  }, [view, detail?.id]);

  useEffect(() => {
    if (!apiKey || view !== "home") return;
    let live = true;
    Promise.all([
      tmdbFetch("/trending/all/week", apiKey),
      tmdbFetch("/movie/popular", apiKey),
      tmdbFetch("/tv/popular", apiKey),
    ]).then(([trending, movies, series]) => {
      if (!live) return;
      const list = trending.results || [];
      setHome({
        hero: list.find((item) => item.backdrop_path) || list[0],
        trending: list,
        movies: movies.results || [],
        series: series.results || [],
      });
    }).catch((error) => setToast(error.message || "Could not reach TMDB"));
    return () => {
      live = false;
    };
  }, [apiKey, view]);

  useEffect(() => {
    if (!apiKey || (view !== "movies" && view !== "series")) return;
    let live = true;
    tmdbFetch(`/${view === "movies" ? "movie" : "tv"}/popular`, apiKey).then((data) => {
      if (live) setGrid(data.results || []);
    }).catch((error) => setToast(error.message || "Could not reach TMDB"));
    return () => {
      live = false;
    };
  }, [apiKey, view]);

  useEffect(() => {
    if (!detail || !apiKey) return;
    let live = true;
    const kind = kindOf(detail);
    tmdbFetch(`/${kind}/${detail.id}`, apiKey).then((data) => {
      if (live) setDetail((current) => (current ? { ...current, ...data, media_type: kind } : current));
    }).catch(() => {});
    tmdbFetch(`/${kind}/${detail.id}/credits`, apiKey).then((data) => {
      if (live) setCast((data.cast || []).slice(0, 16));
    }).catch(() => setCast([]));
    tmdbFetch(`/${kind}/${detail.id}/images`, apiKey).then((data) => {
      const logos = data.logos || [];
      const pick = logos.find((item) => item.iso_639_1 === "en") || logos.find((item) => !item.iso_639_1) || logos[0];
      if (live) setLogo(pick ? imgUrl(pick.file_path, "w500") : null);
    }).catch(() => {
      if (live) setLogo(null);
    });
    return () => {
      live = false;
    };
  }, [detail?.id, apiKey]);

  useEffect(() => {
    if (!detail || !apiKey || kindOf(detail) !== "tv") return;
    let live = true;
    tmdbFetch(`/tv/${detail.id}/season/${season}`, apiKey).then((data) => {
      if (live) setEpisodes(data.episodes || []);
    }).catch(() => setEpisodes([]));
    return () => {
      live = false;
    };
  }, [detail?.id, detail?.media_type, season, apiKey]);

  useEffect(() => {
    if (view !== "search") return;
    const text = query.trim();
    if (!apiKey || text.length < 2) {
      setResults([]);
      return;
    }
    const timer = setTimeout(() => {
      tmdbFetch(`/search/multi?query=${encodeURIComponent(text)}`, apiKey).then((data) => {
        setResults((data.results || []).filter((item) => item.media_type === "movie" || item.media_type === "tv"));
      }).catch(() => setResults([]));
    }, 250);
    return () => clearTimeout(timer);
  }, [query, apiKey, view]);

  backRef.current = () => {
    if (ask) {
      setAsk(null);
      return true;
    }
    if (detail) {
      setDetail(null);
      setCast([]);
      setEpisodes([]);
      setLogo(null);
      return true;
    }
    if (view !== "home") {
      setView("home");
      return true;
    }
    return false;
  };

  const open = (item) => {
    const kind = kindOf(item);
    setSeason(item.season || 1);
    setEpisodes([]);
    setCast([]);
    setLogo(null);
    setDetail({ ...item, media_type: kind, poster_path: art(item, "poster"), backdrop_path: art(item, "backdrop") });
  };

  const savedFor = (item) => shelf.find((entry) => entry.id === item?.id && (entry.type === kindOf(item) || entry.media_type === kindOf(item)));

  const play = async (item, extra = {}) => {
    if (!window.electron?.playNative) {
      setToast("Native playback is not available.");
      return;
    }
    const kind = kindOf(item);
    const seasonNo = extra.season || (kind === "tv" ? season : undefined);
    const episodeNo = extra.episode || (kind === "tv" ? 1 : undefined);
    setToast(`Finding a stream for ${titleOf(item)}…`);
    try {
      const sources = await collectSources(kind, item.id, seasonNo, episodeNo, apiKey, item);
      if (sources.length === 0) {
        setToast("No playable source for this title.");
        return;
      }
      const subtitle = kind === "tv" ? `Season ${seasonNo} · Episode ${episodeNo}` : yearOf(item);
      const result = await window.electron.playNative({
        title: titleOf(item),
        subtitle,
        sources,
        startMs: extra.startMs || 0,
        handoff: extra.handoff || "",
        audioLang: localStorage.getItem(AUDIO_PREF) || "",
        hasNext: kind === "tv",
      });
      if (typeof result?.audioLang === "string" && result.audioLang) {
        localStorage.setItem(AUDIO_PREF, result.audioLang);
        setAudioLang(result.audioLang);
      }
      if (result?.positionMs > 10000) {
        remember({
          id: item.id,
          type: kind,
          title: titleOf(item),
          poster: art(item, "poster"),
          backdrop: art(item, "backdrop"),
          season: seasonNo || null,
          episode: episodeNo || null,
          positionMs: result.positionMs,
          media_type: kind,
          original_language: item.original_language || null,
          genre_ids: item.genre_ids || (item.genres || []).map((entry) => entry.id),
          genres: item.genres || null,
        });
        setShelf(loadShelf());
      }
      if (result?.next && kind === "tv") {
        const nextEpisode = (episodeNo || 1) + 1;
        setSeason(seasonNo || 1);
        await play(item, {
          season: seasonNo || 1,
          episode: nextEpisode,
          handoff: extra.handoff || "",
        });
        return;
      }
      if (result && result.ok === false) setToast(result.error || "That title did not start.");
      else setToast("");
    } catch (error) {
      setToast(error.message || "Playback did not start.");
    }
  };

  const begin = (item, extra = {}) => {
    const mode = extra.force || localStorage.getItem(PLAYER_PREF) || "builtin";
    if (mode === "ask" && !extra.force) {
      setAsk({ item, extra });
      return;
    }
    play(item, { ...extra, handoff: mode === "external" ? "external" : "" });
  };

  const choosePlayer = (mode) => {
    localStorage.setItem(PLAYER_PREF, mode);
    setPlayerMode(mode);
  };

  const chooseAudio = (code) => {
    if (code) localStorage.setItem(AUDIO_PREF, code);
    else localStorage.removeItem(AUDIO_PREF);
    setAudioLang(code);
  };

  const saveKey = async () => {
    const next = draftKey.trim();
    await secureStorage.set("apikey", next);
    setApiKey(next);
    setToast(next ? "Token saved." : "Token cleared.");
    if (next) setView("home");
  };

  const featured = spot || (view === "home" ? home?.hero : grid[0]);

  return (
    <div className="reelix">
      <nav className="rail" aria-label="Sections">
        <div className="brand">
          <img src="/reelix-mark.png" alt="" />
          <b>REELIX</b>
        </div>
        {RAIL.map(([id, label, Icon]) => (
          <button
            key={id}
            type="button"
            tabIndex={0}
            className={`rail-btn tv-item${view === id && !detail ? " active" : ""}`}
            onClick={() => {
              setDetail(null);
              setView(id);
            }}
          >
            <Icon />
            <span>{label}</span>
          </button>
        ))}
      </nav>
      <main className="stage">
        {!ready ? null : !apiKey && view !== "settings" ? (
          <section className="welcome">
            <div className="kicker">Reelix</div>
            <h1>REELIX</h1>
            <p className="overview">Add a TMDB read token and the shelves fill in. Playback runs in the TV player.</p>
            <div className="actions">
              <button className="btn primary tv-item" tabIndex={0} data-autofocus="1" type="button" onClick={() => setView("settings")}>Add token</button>
            </div>
          </section>
        ) : detail ? (
          <Details
            item={detail}
            logo={logo}
            season={season}
            episodes={episodes}
            cast={cast}
            resume={savedFor(detail)}
            onSeason={(value) => setSeason(value)}
            onPlay={(extra) => begin(detail, extra)}
          />
        ) : view === "home" ? (
          <Home data={home} shelf={shelf} featured={featured} onSpot={setSpot} onOpen={open} onPlay={begin} />
        ) : view === "movies" || view === "series" ? (
          <Browse items={grid} featured={featured} onSpot={setSpot} onOpen={open} onPlay={begin} />
        ) : view === "search" ? (
          <Search query={query} results={results} onKey={(chunk) => setQuery((value) => (chunk === "del" ? value.slice(0, -1) : chunk === "clear" ? "" : chunk === "space" ? `${value} ` : value + chunk))} onOpen={open} onSpot={setSpot} />
        ) : view === "library" ? (
          <Library shelf={shelf} featured={spot || shelf[0]} onSpot={setSpot} onPlay={begin} />
        ) : (
          <Settings
            draft={draftKey}
            setDraft={setDraftKey}
            onSave={saveKey}
            playerMode={playerMode}
            onPlayer={choosePlayer}
            audioLang={audioLang}
            onAudio={chooseAudio}
          />
        )}
        {ask ? (
          <div className="ask">
            <div className="ask-card">
              <h2>Play with</h2>
              <button className="btn primary tv-item" tabIndex={0} data-autofocus="1" type="button" onClick={() => { const pending = ask; setAsk(null); play(pending.item, { ...pending.extra, handoff: "" }); }}>Built-in player</button>
              <button className="btn tv-item" tabIndex={0} type="button" onClick={() => { const pending = ask; setAsk(null); play(pending.item, { ...pending.extra, handoff: "external" }); }}>External player</button>
              <button className="btn tv-item" tabIndex={0} type="button" onClick={() => setAsk(null)}>Cancel</button>
            </div>
          </div>
        ) : null}
        {toast ? <div className="toast">{toast}</div> : null}
      </main>
    </div>
  );
}

function Hero({ item, onPlay, onOpen, auto }) {
  if (!item) {
    return <section className="welcome"><h1>REELIX</h1><p className="overview">Loading the shelves…</p></section>;
  }
  const backdrop = imgUrl(art(item, "backdrop"), "w1280");
  const kind = kindOf(item);
  return (
    <div className="hero" style={{ backgroundImage: backdrop ? `url(${backdrop})` : undefined }}>
      <div className="hero-shade" />
      <div className="hero-copy">
        <div className="kicker">{kind === "tv" ? "Series" : "Movie"}{yearOf(item) ? ` · ${yearOf(item)}` : ""}</div>
        <h1>{titleOf(item)}</h1>
        {item.overview ? <p className="overview">{item.overview}</p> : null}
        <div className="actions">
          <button className="btn primary tv-item" tabIndex={0} data-autofocus={auto ? "1" : undefined} type="button" onClick={() => onPlay(item)}>Play</button>
          <button className="btn tv-item" tabIndex={0} type="button" onClick={() => onOpen(item)}>Details</button>
        </div>
      </div>
    </div>
  );
}

function Home({ data, shelf, featured, onSpot, onOpen, onPlay }) {
  if (!data?.hero && !featured) return <Hero item={null} />;
  return (
    <section className="home">
      <Hero item={featured || data.hero} auto onPlay={(item) => onPlay(item, { season: item.season, episode: item.episode, startMs: item.positionMs })} onOpen={onOpen} />
      <div className="rows">
        <div className="rows-scroll">
          {shelf.length > 0 ? <Row label="Continue" items={shelf} onOpen={(item) => onPlay(item, { season: item.season, episode: item.episode, startMs: item.positionMs })} onSpot={onSpot} /> : null}
          <Row label="Trending" items={data?.trending} onOpen={onOpen} onSpot={onSpot} />
          <Row label="Movies" items={data?.movies} onOpen={onOpen} onSpot={onSpot} />
          <Row label="Series" items={data?.series} onOpen={onOpen} onSpot={onSpot} />
        </div>
      </div>
    </section>
  );
}

function Browse({ items, featured, onSpot, onOpen, onPlay }) {
  return (
    <section className="browse">
      <Hero item={featured || items[0]} auto onPlay={onPlay} onOpen={onOpen} />
      <div className="grid">
        {items.map((item) => (
          <Poster key={item.id} item={item} onOpen={onOpen} onSpot={onSpot} />
        ))}
      </div>
    </section>
  );
}

function Library({ shelf, featured, onSpot, onPlay }) {
  return (
    <section className="library">
      <Hero item={featured} auto onPlay={(item) => onPlay(item, { season: item.season, episode: item.episode, startMs: item.positionMs })} onOpen={(item) => onPlay(item, { season: item.season, episode: item.episode, startMs: item.positionMs })} />
      <div className="library-scroll">
        <Row label="Continue watching" items={shelf} onOpen={(item) => onPlay(item, { season: item.season, episode: item.episode, startMs: item.positionMs })} onSpot={onSpot} />
        {shelf.length === 0 ? <p className="note">Titles you start will wait here.</p> : null}
      </div>
    </section>
  );
}

function Row({ label, items, onOpen, onSpot }) {
  return (
    <div className="row">
      <div className="row-head">{label}</div>
      <div className="scroller">
        {(items || []).slice(0, 18).map((item) => (
          <Poster key={`${label}-${item.id}-${item.episode || 0}`} item={item} onOpen={onOpen} onSpot={onSpot} />
        ))}
      </div>
    </div>
  );
}

function Poster({ item, onOpen, onSpot }) {
  const src = imgUrl(art(item, "poster"), "w342");
  return (
    <button className="poster tv-item" type="button" tabIndex={0} onClick={() => onOpen(item)} onFocus={() => onSpot?.(item)}>
      {src ? <img src={src} alt="" /> : <div className="ph" />}
      <small>{titleOf(item)}</small>
    </button>
  );
}

function Details({ item, logo, season, episodes, cast, resume, onSeason, onPlay }) {
  const isTv = kindOf(item) === "tv";
  const seasons = (item.seasons || []).filter((entry) => entry.season_number > 0);
  const genres = (item.genres || []).slice(0, 3).map((entry) => entry.name).join(" · ");
  const runtime = item.runtime ? `${item.runtime} min` : item.episode_run_time?.[0] ? `${item.episode_run_time[0]} min` : "";
  const backdrop = imgUrl(art(item, "backdrop"), "w1280");
  return (
    <section className="details">
      <div className="details-hero" style={{ backgroundImage: backdrop ? `url(${backdrop})` : undefined }}>
        <div className="hero-shade" />
        <div className="details-copy">
          <div className="meta">
            {isTv ? "Series" : "Movie"}
            {yearOf(item) ? ` · ${yearOf(item)}` : ""}
            {runtime ? ` · ${runtime}` : ""}
            {item.vote_average ? ` · ${Number(item.vote_average).toFixed(1)}` : ""}
            {genres ? ` · ${genres}` : ""}
          </div>
          {logo ? <img className="logo-lockup" src={logo} alt={titleOf(item)} /> : <h1>{titleOf(item)}</h1>}
          <p className="overview">{item.overview}</p>
          <div className="actions">
            <button className="btn primary tv-item" tabIndex={0} data-autofocus="1" type="button" onClick={() => onPlay(isTv ? { season, episode: 1 } : {})}>Play</button>
            {resume?.positionMs > 10000 ? (
              <button className="btn tv-item" tabIndex={0} type="button" onClick={() => onPlay({ season: resume.season || season, episode: resume.episode || 1, startMs: resume.positionMs })}>Resume</button>
            ) : null}
            <button className="btn tv-item" tabIndex={0} type="button" onClick={() => onPlay({ force: "external", ...(isTv ? { season, episode: 1 } : {}) })}>Play in external player</button>
          </div>
        </div>
      </div>
      {isTv && seasons.length > 0 ? (
        <>
          <div className="row-head">Seasons</div>
          <div className="chips">
            {seasons.map((entry) => (
              <button key={entry.season_number} type="button" tabIndex={0} className={`chip tv-item${entry.season_number === season ? " on" : ""}`} onClick={() => onSeason(entry.season_number)}>
                {entry.name || `Season ${entry.season_number}`}
              </button>
            ))}
          </div>
          <div className="row-head">Episodes</div>
          <div className="eps">
            {episodes.map((episode) => (
              <button key={episode.id} type="button" tabIndex={0} className="ep tv-item" onClick={() => onPlay({ season, episode: episode.episode_number })}>
                <b>{episode.episode_number}</b>
                <span className="ep-copy">
                  <b>{episode.name || `Episode ${episode.episode_number}`}</b>
                  <span>{episode.overview || "Play this episode"}</span>
                </span>
              </button>
            ))}
          </div>
        </>
      ) : null}
      {cast.length > 0 ? (
        <>
          <div className="row-head">Cast</div>
          <div className="cast">
            {cast.map((person) => (
              <button className="person tv-item" type="button" tabIndex={0} key={person.cast_id || person.credit_id || person.id}>
                {person.profile_path ? <img src={imgUrl(person.profile_path, "w185")} alt="" /> : <div className="ph" />}
                <b>{person.name}</b>
                <span>{person.character}</span>
              </button>
            ))}
          </div>
        </>
      ) : null}
    </section>
  );
}

function Search({ query, results, onKey, onOpen, onSpot }) {
  const rows = ["abcdef", "ghijkl", "mnopqr", "stuvwx", "yz0123", "456789"];
  return (
    <section className="search">
      <div className="keys">
        <h2>Search</h2>
        <div className="query">{query || "Type a title"}</div>
        {rows.map((row) => (
          <div className="keyrow" key={row}>
            {[...row].map((letter, index) => (
              <button key={letter} className="key tv-item" tabIndex={0} data-autofocus={row === "abcdef" && index === 0 ? "1" : undefined} type="button" onClick={() => onKey(letter)}>{letter}</button>
            ))}
          </div>
        ))}
        <div className="keyrow">
          <button className="key wide tv-item" tabIndex={0} type="button" onClick={() => onKey("space")}>space</button>
          <button className="key wide tv-item" tabIndex={0} type="button" onClick={() => onKey("del")}>delete</button>
          <button className="key wide tv-item" tabIndex={0} type="button" onClick={() => onKey("clear")}>clear</button>
        </div>
      </div>
      <div className="results">
        <div className="grid">
          {results.map((item) => (
            <Poster key={item.id} item={item} onOpen={onOpen} onSpot={onSpot} />
          ))}
        </div>
      </div>
    </section>
  );
}

function Settings({ draft, setDraft, onSave, playerMode, onPlayer, audioLang, onAudio }) {
  return (
    <section className="settings">
      <div className="block">
        <h2>TMDB token</h2>
        <p className="note">A read access token fills the shelves. Pair a phone so you do not have to type it with the remote.</p>
        <input className="field tv-item" tabIndex={0} data-autofocus="1" value={draft} onChange={(event) => setDraft(event.target.value)} placeholder="Paste token" />
        <div className="actions">
          <button className="btn primary tv-item" tabIndex={0} type="button" onClick={onSave}>Save token</button>
        </div>
      </div>
      <div className="block">
        <h2>Player</h2>
        <p className="note">Choose where Play opens a title. Play in external player always hands the stream to another app.</p>
        <div className="chips">
          {[["builtin", "Built-in"], ["external", "External"], ["ask", "Ask every time"]].map(([id, label]) => (
            <button key={id} type="button" tabIndex={0} className={`chip tv-item${playerMode === id ? " on" : ""}`} onClick={() => onPlayer(id)}>{label}</button>
          ))}
        </div>
      </div>
      <div className="block">
        <h2>Audio language</h2>
        <p className="note">Used when a stream has more than one voice track. Anime titles also offer subbed and dubbed versions.</p>
        <div className="chips">
          {AUDIO_LANGS.map(([code, label]) => (
            <button key={code || "default"} type="button" tabIndex={0} className={`chip tv-item${audioLang === code ? " on" : ""}`} onClick={() => onAudio(code)}>{label}</button>
          ))}
        </div>
      </div>
      <div className="block">
        <PhoneKeyPairing focusable />
      </div>
    </section>
  );
}

function Icon({ children }) {
  return (
    <svg viewBox="0 0 24 24" width="28" height="28" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
      {children}
    </svg>
  );
}
function HomeIcon() {
  return <Icon><path d="M4 11.5 12 4l8 7.5V20a1 1 0 0 1-1 1h-5v-6H10v6H5a1 1 0 0 1-1-1v-8.5Z" /></Icon>;
}
function MovieIcon() {
  return <Icon><rect x="3" y="5" width="18" height="14" rx="2" /><path d="M8 5v14M16 5v14M3 9h5M3 15h5M16 9h5M16 15h5" /></Icon>;
}
function SeriesIcon() {
  return <Icon><rect x="4" y="4" width="12" height="16" rx="2" /><path d="M16 8h4v12a1 1 0 0 1-1 1h-3" /></Icon>;
}
function SearchIcon() {
  return <Icon><circle cx="11" cy="11" r="6" /><path d="m16 16 4 4" /></Icon>;
}
function LibraryIcon() {
  return <Icon><path d="M6 4h3v16H6zM11 7h3v13h-3zM16 5h3v15h-3z" /></Icon>;
}
function SettingsIcon() {
  return <Icon><circle cx="12" cy="12" r="3" /><path d="M12 3.5v2.2M12 18.3v2.2M4.8 6.2l1.6 1.6M17.6 16.2l1.6 1.6M3.5 12h2.2M18.3 12h2.2M4.8 17.8l1.6-1.6M17.6 7.8l1.6-1.6" /></Icon>;
}
