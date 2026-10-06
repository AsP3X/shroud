/**
 * The cut our camera goes out in: its shape, where it goes for faces, and how it moves.
 * The iPhone's CallFramingTests and Android's CallFramingTest check the same cases.
 * Run: npx esbuild src/calls/framing.selftest.ts --bundle --platform=node --format=esm | node --input-type=module
 */
import { decodeYunet, facesInPicture, yunetInput, yunetScale, YUNET_SIZE } from "./faceDetect";
import { Framer, coverOffset, faceUnion, largestInside, outputSize, shapeChanged, targetCrop, type Rect } from "./framing";

function check(ok: boolean, what: string): void {
  if (!ok) throw new Error(`framing selftest: ${what}`);
}

function near(a: number, b: number, tolerance = 0.5): boolean {
  return Math.abs(a - b) <= tolerance;
}

const uhd = { width: 3840, height: 2160 };
const fhd = { width: 1920, height: 1080 };
const phone = { width: 1179, height: 2556 };

/* --- the output's shape --- */
{
  const tall = outputSize(uhd, phone);
  check(tall.width === 886 && tall.height === 1920, `a 4K camera seen on a phone: 886×1920 (${JSON.stringify(tall)})`);
  const small = outputSize(fhd, phone);
  check(small.width === 498 && small.height === 1080, `a 1080p camera can only give 498×1080 (${JSON.stringify(small)})`);
  check(JSON.stringify(outputSize(uhd, null)) === JSON.stringify(fhd), "without their view: the camera's own shape, 1080p at most");
  check(JSON.stringify(outputSize({ width: 1280, height: 720 }, null)) === JSON.stringify({ width: 1280, height: 720 }), "never enlarged");
  const portraitPhone = outputSize({ width: 1080, height: 1920 }, phone);
  check(portraitPhone.height === 1920 && portraitPhone.width === 886, "a phone held upright, seen on a phone");
  const sliver = outputSize(uhd, { width: 100, height: 1000 });
  check(near(sliver.width / sliver.height, 0.4, 0.01), "no narrower than 0.4");
  const banner = outputSize(uhd, { width: 1000, height: 100 });
  check(near(banner.width / banner.height, 2.5, 0.01), "no wider than 2.5");
  check(outputSize(uhd, { width: 0, height: 0 }).width === 1920, "an empty view counts as none");
  const odd = outputSize({ width: 1001, height: 999 }, null);
  check(odd.width % 2 === 0 && odd.height % 2 === 0, "even sides");
}

/* --- when a new view counts --- */
check(!shapeChanged({ width: 1179, height: 2556 }, { width: 1180, height: 2540 }), "a few pixels are no new shape");
check(shapeChanged({ width: 1179, height: 2556 }, { width: 2556, height: 1179 }), "a rotation is");
check(shapeChanged(null, phone) && shapeChanged(phone, null) && !shapeChanged(null, null), "appearing and going away are");

/* --- where the cut goes --- */
{
  const output = outputSize(uhd, null);
  const base = targetCrop(uhd, output, null);
  check(base.x === 0 && base.y === 0 && base.width === 3840 && base.height === 2160, "no faces: the whole picture");
  const face: Rect = { x: 1770, y: 900, width: 300, height: 300 };
  const cut = targetCrop(uhd, output, face);
  check(near(cut.height, 1000) && near(cut.width, 1000 * (16 / 9)), `head and shoulders (${JSON.stringify(cut)})`);
  check(near(cut.x + cut.width / 2, 1920) && near(cut.y, 1050 - 420), "the face in the middle, a little above it");
  const tiny = targetCrop(uhd, output, { x: 1900, y: 1000, width: 40, height: 40 });
  check(near(tiny.height, 864), `never tighter than the output allows (${tiny.height})`);
  const edge = targetCrop(uhd, output, { x: 3700, y: 2000, width: 140, height: 140 });
  check(edge.x + edge.width <= 3840 + 1e-6 && edge.y + edge.height <= 2160 + 1e-6, "always inside the picture");
  const group = targetCrop(uhd, output, faceUnion([
    { x: 600, y: 900, width: 250, height: 250 },
    { x: 2900, y: 950, width: 250, height: 250 },
  ]));
  check(near(group.width, 3840) || group.width >= 2550 / 0.6 - 1, `several people side by side all fit (${group.width})`);
  check(faceUnion([]) === null, "no faces, no union");
  const tall = outputSize(uhd, phone);
  const tallCut = targetCrop(uhd, tall, face);
  check(near(tallCut.width / tallCut.height, tall.width / tall.height, 0.002), "the cut keeps the output's shape");
  const inside = largestInside(uhd, 1);
  check(inside.width === 2160 && inside.x === 840, "the largest square in the middle");
}

/* --- how it moves --- */
{
  const output = outputSize(uhd, null);
  const framer = new Framer();
  framer.configure(uhd, output, true);
  const start = framer.next(0);
  check(start.width === 3840, "it starts on the whole picture");
  const face: Rect = { x: 1770, y: 900, width: 300, height: 300 };
  framer.faces([face], 0);
  let cut = framer.next(33);
  check(cut.height < 2160 && cut.height > 1500, `it glides rather than jumps (${cut.height})`);
  for (let t = 66; t <= 5_000; t += 33) cut = framer.next(t);
  check(near(cut.height, 1000, 2) && near(cut.x + cut.width / 2, 1920, 2), "and settles on the face");
  framer.faces([{ ...face, x: face.x + 20 }], 5_000);
  for (let t = 5_033; t <= 8_000; t += 33) cut = framer.next(t);
  check(near(cut.x + cut.width / 2, 1920, 2), "a small move is not followed");
  framer.faces([{ ...face, x: face.x + 600 }], 8_000);
  for (let t = 8_033; t <= 12_000; t += 33) cut = framer.next(t);
  check(near(cut.x + cut.width / 2, 2520, 2), "a real move is");
  framer.faces([{ ...face, x: face.x + 600 }], 11_800);
  framer.faces([], 12_000);
  for (let t = 12_033; t <= 13_000; t += 33) cut = framer.next(t);
  check(near(cut.height, 1000, 2), "a face lost for a moment holds the cut");
  framer.faces([], 14_000);
  for (let t = 14_033; t <= 20_000; t += 33) cut = framer.next(t);
  check(near(cut.height, 2160, 2), "gone for longer: back to the whole picture");
  framer.faces([face], 20_000);
  framer.configure(uhd, output, false);
  for (let t = 20_033; t <= 26_000; t += 33) cut = framer.next(t);
  check(near(cut.height, 2160, 2), "Center Stage off: the whole picture");
  framer.configure(uhd, outputSize(uhd, phone), true);
  cut = framer.next(26_033);
  check(near(cut.width / cut.height, 886 / 1920, 0.002) && near(cut.height, 2160), "a new shape starts again from the whole picture");
  const late = framer.next(26_033 + 10_000);
  check(late.height <= 2160 && late.y >= 0, "a long pause between frames is not a jump past the target");
}

/* --- our own small picture centres on the faces --- */
{
  const output = outputSize(uhd, null);
  const framer = new Framer();
  framer.configure(uhd, output, true);
  framer.next(0);
  check(JSON.stringify(framer.focus()) === JSON.stringify({ x: 0.5, y: 0.5 }), "no faces: the middle");
  const face: Rect = { x: 1770, y: 900, width: 300, height: 300 };
  framer.faces([face], 0);
  for (let t = 33; t <= 6_000; t += 33) framer.next(t);
  const settled = framer.focus();
  check(near(settled.x, 0.5, 0.01) && near(settled.y, 0.42, 0.01), `on the face, a little above the middle (${JSON.stringify(settled)})`);
  framer.faces([{ x: 3600, y: 900, width: 200, height: 200 }], 6_000);
  for (let t = 6_033; t <= 12_000; t += 33) framer.next(t);
  check(framer.focus().x > 0.6, `a face the cut cannot centre (at the picture's edge) is off its middle (${framer.focus().x})`);
  framer.configure(uhd, output, false);
  for (let t = 12_033; t <= 20_000; t += 33) framer.next(t);
  const off = framer.focus();
  check(near(off.x, 0.5, 0.01) && near(off.y, 0.5, 0.01), "Center Stage off: back to the middle");
  const fresh = new Framer();
  check(fresh.focus().x === 0.5 && fresh.focus().y === 0.5, "before any frame: the middle");
}
{
  const tile = { width: 200, height: 125 };
  const tall = { width: 886, height: 1920 };
  const at = coverOffset(tile, tall, { x: 0.5, y: 0.35 });
  check(near(at.x, 0) && near(at.y, 62.5 - 0.35 * 1920 * (200 / 886), 0.01), `the face's point in the tile's middle (${JSON.stringify(at)})`);
  check(near(coverOffset(tile, tall, { x: 0.5, y: 0 }).y, 0), "never past the top");
  check(near(coverOffset(tile, tall, { x: 0.5, y: 1 }).y, 125 - 1920 * (200 / 886), 0.01), "nor past the bottom");
  const same = coverOffset({ width: 108, height: 234 }, tall, { x: 0.9, y: 0.1 });
  check(near(same.x, 0, 0.6) && near(same.y, 0, 0.6), "one shape: nothing to move");
  const wide = coverOffset({ width: 108, height: 164 }, { width: 1920, height: 1080 }, { x: 0.8, y: 0.5 });
  check(wide.x < 0 && near(wide.y, 0) && near(wide.x, 54 - 0.8 * 1920 * (164 / 1080), 0.01), "a wide picture in a tall tile moves sideways");
  check(coverOffset({ width: 0, height: 0 }, tall, { x: 0.5, y: 0.5 }).x === 0, "an empty box: nothing");
}

/* --- the face detector's input and output --- */
{
  check(yunetScale(1920, 1080) === YUNET_SIZE / 1920, "a frame is shrunk to fit the square");
  const rgba = new Uint8Array(YUNET_SIZE * YUNET_SIZE * 4);
  rgba.set([10, 20, 30, 255], 0);
  const input = yunetInput(rgba);
  const plane = YUNET_SIZE * YUNET_SIZE;
  check(input[0] === 30 && input[plane] === 20 && input[plane * 2] === 10, "blue, green, red planes");
  // One sure face in the stride-32 grid at row 5, column 9, and a weaker copy of it overlapping.
  const cells = (stride: number) => (YUNET_SIZE / stride) ** 2;
  const outputs: Record<string, Float32Array> = {};
  for (const stride of [8, 16, 32]) {
    outputs[`cls_${stride}`] = new Float32Array(cells(stride));
    outputs[`obj_${stride}`] = new Float32Array(cells(stride));
    outputs[`bbox_${stride}`] = new Float32Array(cells(stride) * 4);
  }
  const at = 5 * 20 + 9;
  outputs.cls_32[at] = 0.95;
  outputs.obj_32[at] = 0.9;
  outputs.bbox_32.set([0.5, 0.5, Math.log(3), Math.log(4)], at * 4);
  outputs.cls_32[at + 1] = 0.7;
  outputs.obj_32[at + 1] = 0.7;
  outputs.bbox_32.set([-0.4, 0.5, Math.log(3), Math.log(4)], (at + 1) * 4);
  outputs.cls_16[0] = 0.3;
  outputs.obj_16[0] = 0.3;
  const faces = decodeYunet(outputs);
  check(faces.length === 1, `the overlapping copy and the unsure cell are dropped (${faces.length})`);
  const face = faces[0];
  check(near(face.x, 9.5 * 32 - 48) && near(face.y, 5.5 * 32 - 64) && near(face.width, 96) && near(face.height, 128), `its box (${JSON.stringify(face)})`);
  check(near(face.score, Math.sqrt(0.95 * 0.9), 1e-6), "its score");
  const back = facesInPicture(faces, 0.5)[0];
  check(near(back.width, 192) && near(back.x, face.x * 2), "back in the picture's pixels");
}

console.log("framing selftest: ok");
