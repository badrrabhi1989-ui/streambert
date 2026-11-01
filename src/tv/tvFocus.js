/** Spatial D-pad focus. Key codes match Android KeyEvent. */

export function directionFromKey(key, keyCode) {
  const code = keyCode || 0;
  if (key === "ArrowLeft" || code === 21) return "left";
  if (key === "ArrowRight" || code === 22) return "right";
  if (key === "ArrowUp" || code === 19) return "up";
  if (key === "ArrowDown" || code === 20) return "down";
  return null;
}

export function isActivateKey(key, keyCode) {
  const code = keyCode || 0;
  return key === "Enter" || key === "NumpadEnter" || key === "Select" || code === 13 || code === 23 || code === 66;
}

export function pickNext(rects, current, direction) {
  if (!rects.length) return -1;
  if (current < 0 || current >= rects.length) return 0;
  const cur = rects[current];
  const cx = (cur.left + cur.right) / 2;
  const cy = (cur.top + cur.bottom) / 2;
  let best = -1;
  let bestScore = Infinity;
  for (let i = 0; i < rects.length; i += 1) {
    if (i === current) continue;
    const box = rects[i];
    const dx = (box.left + box.right) / 2 - cx;
    const dy = (box.top + box.bottom) / 2 - cy;
    if (direction === "left" && dx >= -4) continue;
    if (direction === "right" && dx <= 4) continue;
    if (direction === "up" && dy >= -4) continue;
    if (direction === "down" && dy <= 4) continue;
    const horizontal = direction === "left" || direction === "right";
    const score = (horizontal ? Math.abs(dx) : Math.abs(dy)) + (horizontal ? Math.abs(dy) : Math.abs(dx)) * 2.4;
    if (score < bestScore) {
      bestScore = score;
      best = i;
    }
  }
  return best;
}
