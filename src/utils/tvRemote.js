import { tvRemoteAction } from "./tvRemoteActions";
import {
  clearGamepadFocus,
  confirmGamepadFocus,
  dismissActiveOverlay,
  ensureInitialFocus,
  getGamepadFocus,
  moveGamepadFocus,
  setGamepadFocus,
} from "./gamepadSpatialNav";

function isTypingTarget(el) {
  if (!el) return false;
  const tag = (el.tagName || "").toUpperCase();
  if (tag === "TEXTAREA" || tag === "SELECT") return true;
  if (tag === "INPUT") {
    const type = (el.getAttribute("type") || "text").toLowerCase();
    return !["button", "submit", "checkbox", "radio", "range", "file"].includes(type);
  }
  return !!el.isContentEditable;
}

function playerOpen() {
  return !!document.querySelector(".player-wrap");
}

function controlsFocused() {
  const el = getGamepadFocus();
  return !!(el && el.closest && el.closest(".player-overlay-group, .player-overlay-btn"));
}

function postToPlayers(message) {
  document.querySelectorAll("webview").forEach((el) => {
    try {
      el.postToGuest?.(message);
    } catch {}
  });
}

function focusPlayerControls(dir) {
  const buttons = Array.from(document.querySelectorAll(".player-overlay-btn")).filter(
    (el) => el.offsetParent !== null || getComputedStyle(el).position === "fixed",
  );
  if (buttons.length === 0) return;
  setGamepadFocus(dir === "up" ? buttons[buttons.length - 1] : buttons[0]);
}

export function applyTvRemoteKey(key) {
  const action = tvRemoteAction({
    key,
    playerOpen: playerOpen(),
    controlsFocused: controlsFocused(),
    typing: isTypingTarget(document.activeElement),
  });
  if (!action) return false;
  if (action.type === "move") {
    ensureInitialFocus();
    moveGamepadFocus(action.dir);
    return true;
  }
  if (action.type === "controls") {
    focusPlayerControls(action.dir);
    return true;
  }
  if (action.type === "activate") {
    const el = getGamepadFocus();
    const tag = (el?.tagName || "").toUpperCase();
    if (tag === "BUTTON" || tag === "A" || tag === "INPUT" || tag === "SELECT") return false;
    ensureInitialFocus();
    confirmGamepadFocus();
    return true;
  }
  if (action.type === "back") {
    const result = window.__streambertHandleBack?.();
    return result === "handled" || result === "pending";
  }
  if (action.type === "seek" || action.type === "playpause" || action.type === "play" || action.type === "pause") {
    postToPlayers({ type: "sb-media", action: action.type === "seek" ? "seek" : action.type, delta: action.delta || 0 });
    return true;
  }
  return false;
}

export function installTvRemote() {
  if (!document.documentElement.classList.contains("sb-tv")) return () => {};
  const onKey = (e) => {
    if (e.metaKey || e.ctrlKey || e.altKey) return;
    const used = applyTvRemoteKey(e.key);
    if (used) {
      e.preventDefault();
      e.stopPropagation();
    }
  };
  window.addEventListener("keydown", onKey, true);
  window.__streambertMediaKey = (name) => applyTvRemoteKey(name);
  const kick = () => {
    if (!getGamepadFocus()) ensureInitialFocus();
  };
  const timer = setTimeout(kick, 300);
  const obs = new MutationObserver(() => {
    const cur = getGamepadFocus();
    if (!cur || !document.contains(cur)) kick();
  });
  if (document.body) obs.observe(document.body, { childList: true, subtree: true });
  return () => {
    clearTimeout(timer);
    obs.disconnect();
    window.removeEventListener("keydown", onKey, true);
    delete window.__streambertMediaKey;
    clearGamepadFocus();
  };
}

export function tvHandleBack() {
  if (dismissActiveOverlay()) return "handled";
  const focused = getGamepadFocus();
  if (playerOpen() && focused && focused.closest(".player-overlay-group, .player-overlay-btn")) {
    clearGamepadFocus();
    return "handled";
  }
  if (playerOpen() && typeof window.__streambertClosePlayer === "function") {
    window.__streambertClosePlayer();
    clearGamepadFocus();
    return "handled";
  }
  return null;
}
