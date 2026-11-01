import { useEffect, useRef, useState } from "react";
import { PLAYER_SOURCES, fetchImdbId, getSourceUrl, imgUrl, tmdbFetch } from "../utils/api";
import { secureStorage } from "../utils/storage";
import PhoneKeyPairing from "../components/PhoneKeyPairing";
import "./tv.css";

const SHELF = "emberlane-shelf";
const PREFER = ["vidrift", "vidsrc", "vidfast", "vidlove", "aether", "vidnest", "frembed"];
const RAIL = [
  ["home", "Home"],
  ["movies", "Movies"],
  ["series", "Series"],
  ["search", "Search"],
  ["library", "Library"],
  ["settings", "Settings"],
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
    (item) =>
      !(item.id === entry.id && item.type === entry.type && item.season === entry.season && item.episode === entry.episode),
  );
  list.unshift(entry);
  localStorage.setItem(SHELF, JSON.stringify(list.slice(0, 24)));
}

function visible(el) {
  const r = el.getBoundingClientRect();
  return r.width > 0 && r.height > 0;
}

function moveFocus(key) {
  const items = [...document.querySelectorAll(".tv-item")].filter(visible);
  const current = document.activeElement;
  if (!items.includes(current)) {
    items[0]?.focus();
    return;
  }
  const box = current.getBoundingClientRect();
  const cx = box.left + box.width / 2;
  const cy = box.top + box.height / 2;
  let best = null;
  let bestScore = Infinity;
  for (const el of items) {
    if (el === current) continue;
    const b = el.getBoundingClientRect();
    const dx = b.left + b.width / 2 - cx;
    const dy = b.top + b.height / 2 - cy;
    if (key === "ArrowLeft" && dx >= -4) continue;
    if (key === "ArrowRight" && dx <= 4) continue;
    if (key === "ArrowUp" && dy >= -4) continue;
    if (key === "ArrowDown" && dy <= 4) continue;
    const primary = key === "ArrowLeft" || key === "ArrowRight" ? Math.abs(dx) : Math.abs(dy);
    const secondary = key === "ArrowLeft" || key === "ArrowRight" ? Math.abs(dy) : Math.abs(dx);
    const score = primary + secondary * 2.4;
    if (score < bestScore) {
      bestScore = score;
      best = el;
    }
  }
  if (!best) return;
  best.focus({ preventScroll: true });
  best.scrollIntoView({ block: "nearest", inline: "nearest" });
}

async function collectSources(type, id, season, episode, apiKey) {
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
  list.sort((a, b) => {
    const ia = PREFER.indexOf(a.id);
    const ib = PREFER.indexOf(b.id);
    return (ia < 0 ? 50 : ia) - (ib < 0 ? 50 : ib);
  });
  return list;
}

function titleOf(item) {
  return item.title || item.name || "Untitled";
}

function yearOf(item) {
  const raw = item.release_date || item.first_air_date || "";
  return raw.slice(0, 4);
}

export default function TvApp() {
  const [apiKey, setApiKey] = useState("");
  const [ready, setReady] = useState(false);
  const [view, setView] = useState("home");
  const [detail, setDetail] = useState(null);
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
  const backRef = useRef(() => {});

  useEffect(() => {
    document.documentElement.classList.add("emberlane");
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
    window.__streambertHandleBack = () => {
      const handled = backRef.current();
      return handled ? "handled" : "close";
    };
    const onKey = (event) => {
      if (event.metaKey || event.ctrlKey || event.altKey) return;
      const typing = document.activeElement?.tagName === "INPUT" || document.activeElement?.tagName === "TEXTAREA";
      if (event.key.startsWith("Arrow")) {
        if (typing && (event.key === "ArrowLeft" || event.key === "ArrowRight")) return;
        event.preventDefault();
        event.stopPropagation();
        moveFocus(event.key);
      } else if (event.key === "Enter") {
        const el = document.activeElement;
        if (el?.classList?.contains("tv-item")) {
          event.preventDefault();
          el.click();
        }
      } else if ((event.key === "Escape" || event.key === "Backspace") && !typing) {
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
      const el = document.querySelector("[data-autofocus='1']") || document.querySelector(".stage .tv-item");
      el?.focus();
    }, 50);
    return () => clearTimeout(timer);
  }, [view, detail?.id, season, apiKey, ready]);

  useEffect(() => {
    if (!apiKey || view !== "home") return;
    let live = true;
    Promise.all([
      tmdbFetch("/trending/all/week", apiKey),
      tmdbFetch("/movie/popular", apiKey),
      tmdbFetch("/tv/popular", apiKey),
    ])
      .then(([trending, movies, series]) => {
        if (!live) return;
        setHome({
          hero: (trending.results || []).find((item) => item.backdrop_path) || trending.results?.[0],
          trending: trending.results || [],
          movies: movies.results || [],
          series: series.results || [],
        });
      })
      .catch((error) => setToast(error.message || "Could not reach TMDB"));
    return () => {
      live = false;
    };
  }, [apiKey, view]);

  useEffect(() => {
    if (!apiKey || (view !== "movies" && view !== "series")) return;
    const kind = view === "movies" ? "movie" : "tv";
    let live = true;
    tmdbFetch(`/${kind}/popular`, apiKey)
      .then((data) => {
        if (live) setGrid(data.results || []);
      })
      .catch((error) => setToast(error.message || "Could not reach TMDB"));
    return () => {
      live = false;
    };
  }, [apiKey, view]);

  useEffect(() => {
    if (!detail || !apiKey) return;
    let live = true;
    const kind = detail.media_type === "tv" || detail.name ? "tv" : "movie";
    tmdbFetch(`/${kind}/${detail.id}`, apiKey)
      .then((data) => {
        if (!live) return;
        setDetail((prev) => (prev ? { ...prev, ...data, media_type: kind } : prev));
      })
      .catch(() => {});
    tmdbFetch(`/${kind}/${detail.id}/credits`, apiKey)
      .then((data) => {
        if (live) setCast((data.cast || []).slice(0, 16));
      })
      .catch(() => setCast([]));
    return () => {
      live = false;
    };
  }, [detail?.id, apiKey]);

  useEffect(() => {
    if (!detail || !apiKey || (detail.media_type !== "tv" && !detail.name)) return;
    if (detail.media_type === "movie") return;
    let live = true;
    tmdbFetch(`/tv/${detail.id}/season/${season}`, apiKey)
      .then((data) => {
        if (live) setEpisodes(data.episodes || []);
      })
      .catch(() => setEpisodes([]));
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
    const handle = setTimeout(() => {
      tmdbFetch(`/search/multi?query=${encodeURIComponent(text)}`, apiKey)
        .then((data) => {
          setResults((data.results || []).filter((item) => item.media_type === "movie" || item.media_type === "tv"));
        })
        .catch(() => setResults([]));
    }, 250);
    return () => clearTimeout(handle);
  }, [query, apiKey, view]);

  backRef.current = () => {
    if (detail) {
      setDetail(null);
      setCast([]);
      setEpisodes([]);
      return true;
    }
    if (view !== "home") {
      setView("home");
      return true;
    }
    return false;
  };

  const open = (item) => {
    const kind = item.media_type || (item.title ? "movie" : "tv");
    setSeason(1);
    setEpisodes([]);
    setCast([]);
    setDetail({ ...item, media_type: kind });
  };

  const play = async (item, extra = {}) => {
    if (!window.electron?.playNative) {
      setToast("Native playback is not available.");
      return;
    }
    const kind = item.media_type || (item.name && !item.title ? "tv" : "movie");
    const seasonNo = extra.season || (kind === "tv" ? season : undefined);
    const episodeNo = extra.episode || (kind === "tv" ? 1 : undefined);
    setToast(`Finding a stream for ${titleOf(item)}…`);
    try {
      const sources = await collectSources(kind, item.id, seasonNo, episodeNo, apiKey);
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
      });
      if (result?.positionMs > 10000) {
        remember({
          id: item.id,
          type: kind,
          title: titleOf(item),
          poster: item.poster_path,
          backdrop: item.backdrop_path,
          season: seasonNo || null,
          episode: episodeNo || null,
          positionMs: result.positionMs,
          media_type: kind,
        });
        setShelf(loadShelf());
      }
      if (result && result.ok === false) setToast(result.error || "That title did not start.");
      else setToast("");
    } catch (error) {
      setToast(error.message || "Playback failed");
    }
  };

  const saveKey = async () => {
    const next = draftKey.trim();
    await secureStorage.set("apikey", next);
    setApiKey(next);
    setToast(next ? "Token saved." : "Token cleared.");
    if (next) setView("home");
  };

  const typeKeys = (chunk) => {
    setQuery((value) => {
      if (chunk === "del") return value.slice(0, -1);
      if (chunk === "clear") return "";
      if (chunk === "space") return value + " ";
      return value + chunk;
    });
  };

  return (
    <div className="ember">
      <nav className="rail">
        <div className="wordmark">EMBER<span>LANE</span></div>
        {RAIL.map(([id, label]) => (
          <button
            key={id}
            type="button"
            className={`rail-btn tv-item${view === id && !detail ? " active" : ""}`}
            onClick={() => {
              setDetail(null);
              setView(id);
            }}
          >
            {label}
          </button>
        ))}
      </nav>
      <main className="stage">
        {!ready ? null : !apiKey && view !== "settings" ? (
          <section className="welcome">
            <div className="kicker">A living room, not a desktop</div>
            <h1>EMBERLANE</h1>
            <p className="overview">
              Add a TMDB read token and the shelves fill in. Playback runs in the TV player, with the next source ready when one embed refuses.
            </p>
            <div className="actions">
              <button className="btn primary tv-item" data-autofocus="1" type="button" onClick={() => setView("settings")}>
                Add token
              </button>
            </div>
          </section>
        ) : detail ? (
          <Details
            item={detail}
            season={season}
            episodes={episodes}
            cast={cast}
            onSeason={(value) => setSeason(value)}
            onPlay={(extra) => play(detail, extra)}
          />
        ) : view === "home" ? (
          <Home data={home} shelf={shelf} onOpen={open} onPlay={play} />
        ) : view === "movies" || view === "series" ? (
          <section className="grid">
            {grid.map((item, index) => (
              <Poster key={item.id} item={item} auto={index === 0} onOpen={open} />
            ))}
          </section>
        ) : view === "search" ? (
          <Search query={query} results={results} onKey={typeKeys} onOpen={open} />
        ) : view === "library" ? (
          <section>
            <h2 className="row-head">Continue</h2>
            <div className="scroller">
              {shelf.length === 0 ? <p className="note">Titles you start will wait here.</p> : null}
              {shelf.map((item, index) => (
                <Poster
                  key={`${item.type}-${item.id}-${item.episode || 0}`}
                  item={item}
                  auto={index === 0}
                  onOpen={() => play({ ...item, media_type: item.type }, { season: item.season, episode: item.episode, startMs: item.positionMs })}
                />
              ))}
            </div>
          </section>
        ) : (
          <Settings draft={draftKey} setDraft={setDraftKey} onSave={saveKey} />
        )}
        {toast ? <div className="toast">{toast}</div> : null}
      </main>
    </div>
  );
}

function Home({ data, shelf, onOpen, onPlay }) {
  const hero = data?.hero;
  if (!hero) {
    return <section className="welcome"><h1>EMBERLANE</h1><p className="overview">Loading the shelves…</p></section>;
  }
  return (
    <section className="home">
      <div className="hero" style={{ backgroundImage: `url(${imgUrl(hero.backdrop_path, "w1280")})` }}>
        <div className="hero-shade" />
        <div className="hero-copy">
          <div className="kicker">{hero.media_type === "tv" ? "Series" : "Movie"} · {yearOf(hero)}</div>
          <h1>{titleOf(hero)}</h1>
          <p className="overview">{hero.overview}</p>
          <div className="actions">
            <button className="btn primary tv-item" data-autofocus="1" type="button" onClick={() => onPlay(hero)}>Play</button>
            <button className="btn tv-item" type="button" onClick={() => onOpen(hero)}>Details</button>
          </div>
        </div>
      </div>
      <div className="rows">
        <div className="rows-scroll">
          {shelf.length > 0 ? <Row label="Continue" items={shelf} onOpen={(item) => onPlay(item, { season: item.season, episode: item.episode, startMs: item.positionMs })} /> : null}
          <Row label="Trending" items={data.trending} onOpen={onOpen} />
          <Row label="Movies" items={data.movies} onOpen={onOpen} />
          <Row label="Series" items={data.series} onOpen={onOpen} />
        </div>
      </div>
    </section>
  );
}

function Row({ label, items, onOpen }) {
  return (
    <div className="row">
      <div className="row-head">{label}</div>
      <div className="scroller">
        {(items || []).slice(0, 18).map((item) => (
          <Poster key={`${label}-${item.id}`} item={item} onOpen={onOpen} />
        ))}
      </div>
    </div>
  );
}

function Poster({ item, onOpen, auto }) {
  const src = imgUrl(item.poster_path, "w342");
  return (
    <button className="poster tv-item" type="button" data-autofocus={auto ? "1" : undefined} onClick={() => onOpen(item)}>
      {src ? <img src={src} alt="" /> : <div className="ph" />}
      <small>{titleOf(item)}</small>
    </button>
  );
}

function Details({ item, season, episodes, cast, onSeason, onPlay }) {
  const isTv = item.media_type === "tv" || (!!item.name && !item.title);
  const seasons = (item.seasons || []).filter((entry) => entry.season_number > 0);
  return (
    <section className="details">
      <div className="details-hero" style={{ backgroundImage: `url(${imgUrl(item.backdrop_path, "w1280")})` }}>
        <div className="hero-shade" />
        <div className="details-copy">
          <div className="meta">
            {isTv ? "Series" : "Movie"}
            {yearOf(item) ? ` · ${yearOf(item)}` : ""}
            {item.vote_average ? ` · ${Number(item.vote_average).toFixed(1)}` : ""}
          </div>
          <h1>{titleOf(item)}</h1>
          <p className="overview">{item.overview}</p>
          <div className="actions">
            <button className="btn primary tv-item" data-autofocus="1" type="button" onClick={() => onPlay(isTv ? { season, episode: 1 } : {})}>
              Play
            </button>
          </div>
        </div>
      </div>
      {isTv && seasons.length > 0 ? (
        <>
          <div className="row-head">Seasons</div>
          <div className="chips">
            {seasons.map((entry) => (
              <button
                key={entry.season_number}
                type="button"
                className={`chip tv-item${entry.season_number === season ? " on" : ""}`}
                onClick={() => onSeason(entry.season_number)}
              >
                {entry.name || `Season ${entry.season_number}`}
              </button>
            ))}
          </div>
          <div className="row-head">Episodes</div>
          <div className="eps">
            {episodes.map((episode) => (
              <button
                key={episode.id}
                type="button"
                className="ep tv-item"
                onClick={() => onPlay({ season, episode: episode.episode_number })}
              >
                <b>{episode.episode_number}. {episode.name}</b>
                <span>{episode.overview || "Play this episode"}</span>
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
              <div className="person" key={person.cast_id || person.credit_id || person.id}>
                {person.profile_path ? <img src={imgUrl(person.profile_path, "w185")} alt="" /> : <div className="ph" />}
                <b>{person.name}</b>
                <span>{person.character}</span>
              </div>
            ))}
          </div>
        </>
      ) : null}
    </section>
  );
}

function Search({ query, results, onKey, onOpen }) {
  const rows = ["abcdef", "ghijkl", "mnopqr", "stuvwx", "yz"];
  return (
    <section className="search">
      <div className="keys">
        <h2>Search</h2>
        <div className="query">{query || "Type a title"}</div>
        {rows.map((row) => (
          <div className="keyrow" key={row}>
            {[...row].map((letter, index) => (
              <button key={letter} className="key tv-item" data-autofocus={row === "abcdef" && index === 0 ? "1" : undefined} type="button" onClick={() => onKey(letter)}>
                {letter}
              </button>
            ))}
          </div>
        ))}
        <div className="keyrow">
          <button className="key wide tv-item" type="button" onClick={() => onKey("space")}>space</button>
          <button className="key wide tv-item" type="button" onClick={() => onKey("del")}>delete</button>
          <button className="key wide tv-item" type="button" onClick={() => onKey("clear")}>clear</button>
        </div>
      </div>
      <div className="results">
        <div className="grid">
          {results.map((item) => (
            <Poster key={item.id} item={item} onOpen={onOpen} />
          ))}
        </div>
      </div>
    </section>
  );
}

function Settings({ draft, setDraft, onSave }) {
  return (
    <section className="settings">
      <div className="block">
        <h2>TMDB token</h2>
        <p className="note">A read access token fills the shelves. Pair a phone so you do not have to type it with the remote.</p>
        <input className="field tv-item" data-autofocus="1" value={draft} onChange={(event) => setDraft(event.target.value)} placeholder="eyJ…" />
        <div className="actions">
          <button className="btn primary tv-item" type="button" onClick={onSave}>Save token</button>
        </div>
      </div>
      <div className="block">
        <PhoneKeyPairing />
      </div>
    </section>
  );
}
