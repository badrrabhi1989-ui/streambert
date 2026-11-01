import assert from "node:assert/strict";
import { directionFromKey, isActivateKey, pickNext } from "../src/tv/tvFocus.js";

// Settings screen, in CSS pixels. The pairing control has to be reachable
// from the rail and from the token field with only the D-pad.
const input = { left: 400, top: 160, right: 1120, bottom: 224 };
const save = { left: 400, top: 248, right: 640, bottom: 312 };
const pairing = { left: 400, top: 400, right: 760, bottom: 464 };
const rail = { left: 40, top: 500, right: 240, bottom: 564 };
const rects = [rail, input, save, pairing];
const RAIL = 0;
const INPUT = 1;
const SAVE = 2;
const PAIR = 3;

const right = directionFromKey("ArrowRight", 0);
const down = directionFromKey("", 20);
const left = directionFromKey("ArrowLeft", 22);
assert.equal(right, "right");
assert.equal(down, "down");
assert.equal(left, "left");

assert.equal(pickNext(rects, RAIL, "right"), PAIR);
assert.equal(pickNext(rects, INPUT, "down"), SAVE);
assert.equal(pickNext(rects, SAVE, "down"), PAIR);
assert.equal(pickNext(rects, PAIR, "left"), SAVE);
assert.equal(pickNext(rects, SAVE, "left"), RAIL);

assert.equal(isActivateKey("Enter", 0), true);
assert.equal(isActivateKey("", 23), true);
assert.equal(isActivateKey("", 66), true);
assert.equal(isActivateKey("ArrowDown", 20), false);

let index = RAIL;
index = pickNext(rects, index, directionFromKey("", 22));
assert.equal(index, PAIR);
assert.equal(isActivateKey("Select", 23), true);

console.log("TV focus: D-pad reaches Show pairing code");
