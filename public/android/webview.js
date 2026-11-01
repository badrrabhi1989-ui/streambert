/* Custom <webview> element. Electron ships this tag; Android does not.
 * The element hosts an iframe so overlay buttons stay in the same DOM stacking
 * order as the desktop app. Request blocking, m3u8 capture and header
 * stripping happen in the native WebViewClient. Script injection (guest.js)
 * provides executeJavaScript, frame-wide progress queries and site CSS.
 */
(function () {
  if (customElements.get("webview")) return;

  var PROGRESS_JS =
    "(() => {\n" +
    "  const P = window.meinecloudPlayer;\n" +
    "  if (P && typeof P.getCurrentTime === 'function') {\n" +
    "    try {\n" +
    "      const t = P.getCurrentTime();\n" +
    "      const d = P.getDuration();\n" +
    "      const paused = P.getPaused();\n" +
    "      if (typeof t === 'number' && typeof d === 'number' && typeof paused === 'boolean') {\n" +
    "        if (!(d > 0) || d === Infinity || paused) return null;\n" +
    "        return { currentTime: t, duration: d, recentUserSeek: false, lastUserSeekTo: null };\n" +
    "      }\n" +
    "    } catch (e) {}\n" +
    "  }\n" +
    "  const v = document.querySelector('video');\n" +
    "  if (!v || !v.duration || v.duration === Infinity || v.paused) return null;\n" +
    "  if (!v._seekTracked) {\n" +
    "    v._seekTracked = true;\n" +
    "    v.addEventListener('seeked', () => {\n" +
    "      v._lastUserSeek = Date.now();\n" +
    "      v._lastUserSeekTo = v.currentTime;\n" +
    "    });\n" +
    "  }\n" +
    "  return {\n" +
    "    currentTime: v.currentTime,\n" +
    "    duration: v.duration,\n" +
    "    recentUserSeek: v._lastUserSeek ? (Date.now() - v._lastUserSeek < 6000) : false,\n" +
    "    lastUserSeekTo: v._lastUserSeekTo ?? null,\n" +
    "  };\n" +
    "})()";

  var registry = new Map();
  var seq = 1;
  window.__sbWebviews = registry;
  window.__sbProgressJs = PROGRESS_JS;

  function findByToken(token) {
    for (const el of registry.values()) {
      if (el._token === token) return el;
    }
    return null;
  }

  function findBySource(source) {
    for (const el of registry.values()) {
      try {
        if (el._iframe && el._iframe.contentWindow === source) return el;
      } catch (e) {}
    }
    return null;
  }

  window.addEventListener("message", function (e) {
    var d = e.data;
    if (!d || typeof d !== "object") return;
    if (d.type === "sb-request-hello") {
      var el = findBySource(e.source);
      if (el) {
        try {
          e.source.postMessage({ type: "sb-hello", token: el._token }, "*");
        } catch (err) {}
      }
      return;
    }
    if (d.type === "sb-will-navigate") {
      var target = findByToken(d.token) || findBySource(e.source);
      var prevented = false;
      if (target) {
        target._emit("will-navigate", {
          url: d.url,
          preventDefault: function () {
            prevented = true;
          },
        });
      }
      try {
        e.source.postMessage(
          { type: "sb-nav-decision", id: d.id, allow: !prevented },
          "*",
        );
      } catch (err) {}
      return;
    }
    if (d.type === "sb-key") {
      var keyTarget = findByToken(d.token);
      if (keyTarget) {
        keyTarget._emit("before-input-event", {
          key: d.key,
          type: d.eventType || "keyDown",
        });
      }
      return;
    }
    if (d.type === "sb-fullscreen") {
      var bus = window.__sbElectronBus;
      if (bus) bus.emit(d.active ? "webview-enter-fullscreen" : "webview-leave-fullscreen");
    }
  });

  class ElectronWebView extends HTMLElement {
    constructor() {
      super();
      this._id = seq++;
      this._token = "g" + this._id + Math.random().toString(36).slice(2, 8);
      this._listeners = new Map();
      this._iframe = null;
      this._userAgent = "";
    }

    static get observedAttributes() {
      return ["src", "useragent"];
    }

    connectedCallback() {
      this.style.display = "block";
      if (!this._iframe) {
        var iframe = document.createElement("iframe");
        iframe.name = "sb-" + this._token;
        iframe.setAttribute(
          "allow",
          "autoplay; fullscreen; encrypted-media; picture-in-picture",
        );
        iframe.setAttribute("allowfullscreen", "true");
        iframe.style.cssText =
          "position:absolute;inset:0;width:100%;height:100%;border:none;background:#000;";
        iframe.addEventListener("load", () => {
          this._emit("dom-ready", {});
          this._emit("did-finish-load", {});
          try {
            iframe.contentWindow.postMessage(
              { type: "sb-hello", token: this._token },
              "*",
            );
          } catch (e) {}
        });
        iframe.addEventListener("error", () => {
          this._emit("did-fail-load", { errorCode: -1 });
        });
        this.appendChild(iframe);
        this._iframe = iframe;
      }
      registry.set(this._id, this);
      var src = this.getAttribute("src");
      if (src) this._applySrc(src);
    }

    disconnectedCallback() {
      registry.delete(this._id);
      if (this._iframe) {
        try {
          this._iframe.src = "about:blank";
        } catch (e) {}
      }
    }

    attributeChangedCallback(name, oldValue, value) {
      if (oldValue === value) return;
      if (name === "src") this._applySrc(value || "about:blank");
      if (name === "useragent") this._userAgent = value || "";
    }

    _applySrc(value) {
      if (!this._iframe) return;
      var next = value || "about:blank";
      if (this._iframe.src !== next && this._iframe.getAttribute("src") !== next) {
        this._iframe.src = next;
      }
    }

    getWebContentsId() {
      return this._id;
    }

    executeJavaScript(code) {
      var iframe = this._iframe;
      if (!iframe || !iframe.contentWindow) {
        return Promise.reject(new Error("webview not attached"));
      }
      var id = Math.random().toString(36).slice(2);
      return new Promise((resolve, reject) => {
        var timer = setTimeout(function () {
          window.removeEventListener("message", onMsg);
          reject(new Error("executeJavaScript timeout"));
        }, 8000);
        function onMsg(e) {
          if (!e.data || e.data.type !== "sb-eval-result" || e.data.id !== id)
            return;
          window.removeEventListener("message", onMsg);
          clearTimeout(timer);
          if (e.data.error) reject(new Error(e.data.error));
          else resolve(e.data.value);
        }
        window.addEventListener("message", onMsg);
        iframe.contentWindow.postMessage(
          { type: "sb-eval", id: id, code: String(code) },
          "*",
        );
      });
    }

    queryAllFrames(code) {
      var iframe = this._iframe;
      if (!iframe || !iframe.contentWindow) return Promise.resolve(null);
      var id = Math.random().toString(36).slice(2);
      return new Promise((resolve) => {
        var best = null;
        function onMsg(e) {
          if (!e.data || e.data.type !== "sb-broadcast-result" || e.data.id !== id)
            return;
          if (!best && e.data.value && e.data.value.duration > 0) best = e.data.value;
        }
        window.addEventListener("message", onMsg);
        try {
          iframe.contentWindow.postMessage(
            { type: "sb-broadcast", id: id, code: String(code) },
            "*",
          );
        } catch (e) {}
        setTimeout(function () {
          window.removeEventListener("message", onMsg);
          resolve(best);
        }, 550);
      });
    }

    addEventListener(type, cb, opts) {
      if (
        type === "dom-ready" ||
        type === "did-finish-load" ||
        type === "did-fail-load" ||
        type === "will-navigate" ||
        type === "before-input-event"
      ) {
        if (!this._listeners.has(type)) this._listeners.set(type, new Set());
        this._listeners.get(type).add(cb);
        return;
      }
      super.addEventListener(type, cb, opts);
    }

    removeEventListener(type, cb, opts) {
      if (this._listeners.has(type)) {
        this._listeners.get(type).delete(cb);
        return;
      }
      super.removeEventListener(type, cb, opts);
    }

    _emit(type, payload) {
      var set = this._listeners.get(type);
      if (!set) return;
      set.forEach(function (cb) {
        try {
          cb(payload);
        } catch (e) {}
      });
    }
  }

  Object.defineProperty(ElectronWebView.prototype, "src", {
    get: function () {
      return this.getAttribute("src") || "";
    },
    set: function (v) {
      this.setAttribute("src", v == null ? "about:blank" : String(v));
    },
  });

  Object.defineProperty(ElectronWebView.prototype, "useragent", {
    get: function () {
      return this._userAgent || this.getAttribute("useragent") || "";
    },
    set: function (v) {
      this._userAgent = v || "";
      if (v) this.setAttribute("useragent", v);
      else this.removeAttribute("useragent");
    },
  });

  customElements.define("webview", ElectronWebView);
})();
