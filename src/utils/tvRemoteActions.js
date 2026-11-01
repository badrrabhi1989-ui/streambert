/**
 * Pure remote-key mapping for Android TV. tvRemote.js applies the result.
 * Desktop never installs that listener.
 *
 * @param {{ key: string, playerOpen: boolean, controlsFocused: boolean, typing: boolean }} state
 * @returns {{ type: string, dir?: string, delta?: number } | null}
 */
export function tvRemoteAction(state) {
  const key = state.key;
  const typing = !!state.typing;
  const player = !!state.playerOpen;
  const controls = !!state.controlsFocused;

  if (key === "Escape" || key === "GoBack" || key === "BrowserBack") {
    return { type: "back" };
  }
  if (key === "MediaPause") return { type: "pause" };
  if (key === "MediaPlay") return { type: "play" };
  if (key === "MediaPlayPause" || key === "HeadsetHook") return { type: "playpause" };
  if (key === "MediaFastForward" || key === "MediaTrackNext") return { type: "seek", delta: 30 };
  if (key === "MediaRewind" || key === "MediaTrackPrevious") return { type: "seek", delta: -10 };
  if (key === "playpause" || key === "play" || key === "pause") return { type: key };
  if (typeof key === "string" && key.startsWith("seek:")) {
    const delta = Number(key.slice(5));
    if (Number.isFinite(delta)) return { type: "seek", delta };
  }

  const dir =
    key === "ArrowUp" ? "up" :
    key === "ArrowDown" ? "down" :
    key === "ArrowLeft" ? "left" :
    key === "ArrowRight" ? "right" :
    null;

  if (dir) {
    if (typing && (dir === "left" || dir === "right")) return null;
    if (player && !controls) {
      if (dir === "left") return { type: "seek", delta: -10 };
      if (dir === "right") return { type: "seek", delta: 10 };
      return { type: "controls", dir };
    }
    return { type: "move", dir };
  }

  if (key === "Enter" || key === "NumpadEnter") {
    if (typing) return null;
    if (player && !controls) return { type: "playpause" };
    return { type: "activate" };
  }
  if (key === " " || key === "Spacebar") {
    if (typing) return null;
    if (player && !controls) return { type: "playpause" };
    return { type: "activate" };
  }
  return null;
}
