/* Injected at document start into every frame (player iframes and the local
 * HLS page). It has no access to the privileged native bridge. It only talks
 * to the app page via postMessage.
 */
(function () {
  if (window.__sbGuest) return;
  window.__sbGuest = true;
  var token = window.name && window.name.indexOf("sb-") === 0 ? window.name.slice(3) : "";

  function hostEnds(host, rule) {
    return host === rule || host.endsWith("." + rule);
  }

  var SITE_CSS = [
    {
      hosts: ["on.kinohub.vip"],
      css:
        "html,body{overflow:hidden!important;background:#000!important}" +
        "body *{visibility:hidden!important}" +
        "section.player_section,section.player_section *{visibility:visible!important}" +
        "section.player_section{position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;max-width:none!important;margin:0!important;z-index:2147483647!important;background:#000!important;overflow:auto!important}",
    },
    {
      hosts: ["aether.ist"],
      css:
        'div:has(> .top-content),.top-content,[id="__overlayRouter::episodes"],[id="__overlayRouter::episodes"] + button,a.tabbable[class*="cancelHover"],button.tabbable.p-2.rounded-full:has(> span.text-xl > svg[width="18"][height="18"]){display:none!important}',
    },
    {
      hosts: ["vidnest.fun"],
      css: ".popup-selector,#overlay-id{display:none!important}",
    },
    {
      hosts: ["frembed.uno"],
      css: ".popup-selector{display:none!important}",
    },
  ];

  function applySiteCss() {
    var host = "";
    try {
      host = location.hostname;
    } catch (e) {
      return;
    }
    for (var i = 0; i < SITE_CSS.length; i++) {
      var rule = SITE_CSS[i];
      var match = false;
      for (var h = 0; h < rule.hosts.length; h++) {
        if (hostEnds(host, rule.hosts[h])) match = true;
      }
      if (!match) continue;
      var style = document.getElementById("__sb_site_css");
      if (!style) {
        style = document.createElement("style");
        style.id = "__sb_site_css";
        (document.head || document.documentElement).appendChild(style);
      }
      style.textContent = rule.css;
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", applySiteCss);
  } else {
    applySiteCss();
  }

  var stubHosts = ["vidrift.net", "vidrift.in"];
  var origOpen = window.open;
  window.open = function () {
    var host = "";
    try {
      host = location.hostname;
    } catch (e) {}
    for (var i = 0; i < stubHosts.length; i++) {
      if (hostEnds(host, stubHosts[i])) {
        try {
          var w = origOpen.call(window, "about:blank", "_blank", "width=1,height=1");
          if (w) return w;
        } catch (e) {}
        return { closed: false, close: function () {}, focus: function () {} };
      }
    }
    return null;
  };

  function mediaToast(msg) {
    var el = document.getElementById("sb-media-toast");
    if (!el) {
      el = document.createElement("div");
      el.id = "sb-media-toast";
      el.style.cssText =
        "position:fixed;bottom:48px;left:50%;transform:translateX(-50%);background:rgba(0,0,0,.82);color:#fff;padding:12px 22px;border-radius:10px;font:700 22px system-ui,sans-serif;z-index:2147483647;pointer-events:none";
      document.documentElement.appendChild(el);
    }
    el.textContent = msg;
    clearTimeout(el._t);
    el._t = setTimeout(function () {
      if (el.parentNode) el.parentNode.removeChild(el);
    }, 900);
  }

  function applyMedia(action, delta) {
    var v = document.querySelector("video");
    if (!v) return false;
    if (action === "playpause") {
      if (v.paused) {
        v.play();
        mediaToast("Play");
      } else {
        v.pause();
        mediaToast("Pause");
      }
    } else if (action === "play") {
      v.play();
      mediaToast("Play");
    } else if (action === "pause") {
      v.pause();
      mediaToast("Pause");
    } else if (action === "seek") {
      var next = Math.max(0, Math.min(v.duration || 1e9, v.currentTime + (delta || 0)));
      v.currentTime = next;
      mediaToast((delta > 0 ? "+" : "") + delta + "s");
    }
    return true;
  }

  window.addEventListener("message", function (e) {
    var d = e.data;
    if (!d || typeof d !== "object") return;
    if (d.type === "sb-media") {
      for (var m = 0; m < window.frames.length; m++) {
        try {
          window.frames[m].postMessage(d, "*");
        } catch (err) {}
      }
      applyMedia(d.action, d.delta);
      return;
    }
    if (d.type === "sb-hello" && d.token) {
      token = d.token;
      for (var i = 0; i < window.frames.length; i++) {
        try {
          window.frames[i].postMessage(d, "*");
        } catch (err) {}
      }
    }
    if (d.type === "sb-broadcast" && d.id && typeof d.code === "string") {
      for (var j = 0; j < window.frames.length; j++) {
        try {
          window.frames[j].postMessage(d, "*");
        } catch (err) {}
      }
      var value = null;
      var error = null;
      try {
        value = (0, eval)(d.code);
      } catch (err) {
        error = String(err && err.message ? err.message : err);
      }
      Promise.resolve(value)
        .then(function (v) {
          try {
            v = v == null ? null : JSON.parse(JSON.stringify(v));
          } catch (err) {
            v = null;
          }
          window.top.postMessage(
            { type: "sb-broadcast-result", id: d.id, value: v, error: error },
            "*",
          );
        })
        .catch(function (err) {
          window.top.postMessage(
            {
              type: "sb-broadcast-result",
              id: d.id,
              value: null,
              error: String(err && err.message ? err.message : err),
            },
            "*",
          );
        });
    }
    if (d.type === "sb-eval" && d.id && typeof d.code === "string") {
      var ev = null;
      var ee = null;
      try {
        ev = (0, eval)(d.code);
      } catch (err) {
        ee = String(err && err.message ? err.message : err);
      }
      Promise.resolve(ev)
        .then(function (v) {
          try {
            v = v == null ? null : JSON.parse(JSON.stringify(v));
          } catch (err) {
            v = null;
          }
          window.top.postMessage(
            { type: "sb-eval-result", id: d.id, value: v, error: ee },
            "*",
          );
        })
        .catch(function (err) {
          window.top.postMessage(
            {
              type: "sb-eval-result",
              id: d.id,
              value: null,
              error: String(err && err.message ? err.message : err),
            },
            "*",
          );
        });
    }
  });

  document.addEventListener(
    "click",
    function (e) {
      var node = e.target;
      var a = node && node.closest ? node.closest("a[href]") : null;
      if (!a || !a.href) return;
      if (a.target === "_blank") return;
      var href = a.href;
      if (!/^https?:/i.test(href)) return;
      e.preventDefault();
      e.stopPropagation();
      var id = Math.random().toString(36).slice(2);
      var decided = false;
      function onMsg(ev) {
        if (!ev.data || ev.data.type !== "sb-nav-decision" || ev.data.id !== id)
          return;
        decided = true;
        window.removeEventListener("message", onMsg);
        if (ev.data.allow) location.href = href;
      }
      window.addEventListener("message", onMsg);
      window.top.postMessage(
        { type: "sb-will-navigate", url: href, id: id, token: token },
        "*",
      );
      setTimeout(function () {
        if (!decided) {
          window.removeEventListener("message", onMsg);
          location.href = href;
        }
      }, 500);
    },
    true,
  );

  document.addEventListener(
    "keydown",
    function (e) {
      if (e.key !== "Enter") return;
      window.top.postMessage(
        { type: "sb-key", token: token, key: "Return", eventType: "keyDown" },
        "*",
      );
    },
    true,
  );

  document.addEventListener("fullscreenchange", function () {
    var on = !!document.fullscreenElement;
    window.top.postMessage(
      { type: "sb-fullscreen", active: on, token: token },
      "*",
    );
  });

  if (window !== window.top) {
    try {
      window.top.postMessage({ type: "sb-request-hello" }, "*");
    } catch (e) {}
  }
})();
