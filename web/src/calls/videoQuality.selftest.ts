/**
 * The camera's ladder: where a call starts, when it steps down and up, and what the encoder is told.
 * The iPhone's CallVideoQualityTests and Android's CallVideoQualityTest check the same cases.
 * Run: npx esbuild src/calls/videoQuality.selftest.ts --bundle --platform=node --format=esm | node --input-type=module
 */
import {
  CAMERA_LADDER,
  CAMERA_TILE,
  tileOf,
  CameraQuality,
  ceilingFor,
  cameraEncoding,
  readCameraSample,
  type CameraSample,
} from "./videoQuality";

function check(ok: boolean, what: string): void {
  if (!ok) throw new Error(`video quality selftest: ${what}`);
}

const good: CameraSample = { estimate: 6_000_000, limitation: "none", loss: 0 };
const fhd = { width: 1920, height: 1080 };
const hd = { width: 1280, height: 720 };
const none: CameraSample = { estimate: null, limitation: null, loss: null };

/** Feeds `sample` `times` times; the rung names it passed through. */
function feed(quality: CameraQuality, sample: CameraSample, times: number): string[] {
  const seen: string[] = [];
  for (let i = 0; i < times; i++) if (quality.sample(sample)) seen.push(quality.rung.name);
  return seen;
}

/* --- where a call starts --- */
check(new CameraQuality(fhd).rung.name === "720p", "a 1080p camera starts at 720p");
check(new CameraQuality(hd).rung.name === "720p", "a 720p camera starts at 720p");
check(new CameraQuality({ width: 640, height: 480 }).rung.name === "360p", "a small camera starts at its own size");
check(new CameraQuality(null).rung.name === "720p", "an unknown camera starts at 720p");
check(ceilingFor(fhd) === 5 && ceilingFor(hd) === 4 && ceilingFor({ width: 1300, height: 730 }) === 4, "the ceiling is the camera's size");
check(ceilingFor({ width: 1880, height: 1058 }) === 5, "a capture a little under 1080p still reaches it");
check(ceilingFor({ width: 886, height: 1920 }) === 5, "a tall cut with nearly 1080p's pixels reaches it");
check(ceilingFor({ width: 1080, height: 810 }) === 4, "a squat one with 720p's pixels reaches 720p, though its longer side is 1080");
check(ceilingFor({ width: 498, height: 1080 }) === 3, "a narrow cut from a 1080p camera: 540p");
check(ceilingFor(null) === 4 && ceilingFor({ width: 0, height: 0 }) === 4, "an unknown size: 720p");

/* --- stepping up --- */
{
  const quality = new CameraQuality(fhd);
  check(feed(quality, good, 3).length === 0, "the first three readings settle");
  check(feed(quality, good, 3).length === 0, "three clean readings are not yet enough");
  check(feed(quality, good, 1)[0] === "1080p", "the fourth goes up to 1080p");
  check(feed(quality, good, 20).length === 0, "1080p is the top");
}
{
  const quality = new CameraQuality(hd);
  check(feed(quality, good, 30).length === 0, "a 720p camera never goes above 720p");
}
{
  // A camera sending less than the link could carry: the estimate stays near what it sends.
  const quality = new CameraQuality(fhd);
  check(feed(quality, { estimate: 400_000, limitation: "none", loss: 0 }, 7)[0] === "1080p", "going up does not wait for the estimate");
}
{
  const quality = new CameraQuality(fhd);
  check(feed(quality, none, 7)[0] === "1080p", "without an estimate or a limitation, clean readings still go up");
}
{
  const quality = new CameraQuality(fhd);
  check(feed(quality, { ...good, limitation: "cpu" }, 30).length === 0, "a busy processor does not go up");
  check(feed(quality, { ...good, limitation: "bandwidth" }, 30).length === 0, "an encoder short of bits does not go up");
  check(quality.rung.name === "720p", "nor down while the estimate has room");
  check(feed(quality, { ...good, loss: 0.05 }, 30).length === 0, "a lossy link does not go up");
  quality.sample(good);
  quality.sample(good);
  quality.sample({ ...good, loss: 0.05 });
  check(feed(quality, good, 3).length === 0, "a bad reading starts the count again");
  check(feed(quality, good, 1)[0] === "1080p", "four clean ones in a row go up");
}

/* --- stepping down --- */
const tight: CameraSample = { estimate: 500_000, limitation: "bandwidth", loss: 0 };
{
  const quality = new CameraQuality(fhd);
  feed(quality, good, 3);
  check(feed(quality, tight, 1).length === 0, "one starved reading is not enough");
  check(feed(quality, tight, 1)[0] === "360p", "two go down as far as the estimate needs at once");
  check(feed(quality, tight, 10).length === 0, "and stay where the estimate fits");
}
{
  const quality = new CameraQuality(fhd);
  feed(quality, good, 3);
  check(feed(quality, { ...tight, limitation: "none" }, 2).length === 0, "a low estimate alone does not step down");
  check(quality.rung.name === "720p", "the encoder is not short of bits");
}
{
  const quality = new CameraQuality(fhd);
  feed(quality, good, 3);
  quality.sample(tight);
  quality.sample(good);
  check(feed(quality, tight, 1).length === 0, "starved readings must come in a row");
}
{
  const quality = new CameraQuality(fhd);
  feed(quality, good, 3);
  const lossy: CameraSample = { ...good, loss: 0.15 };
  check(feed(quality, lossy, 2)[0] === "540p", "loss steps down one rung");
  check(feed(quality, lossy, 2).length === 0, "and settles before the next");
  check(feed(quality, lossy, 2)[0] === "360p", "then steps again");
}
{
  const quality = new CameraQuality(fhd);
  feed(quality, good, 3);
  const starved: CameraSample = { estimate: 40_000, limitation: "bandwidth", loss: 0.5 };
  check(feed(quality, starved, 2)[0] === "180p", "a starved link goes to the bottom");
  check(feed(quality, starved, 20).length === 0, "the bottom is the bottom");
  check(quality.rungIndex === 0, "and stays there");
}

/* --- an upgrade that does not hold waits longer next time --- */
{
  const quality = new CameraQuality(fhd);
  feed(quality, good, 7);
  check(quality.rung.name === "1080p", "up to 1080p");
  const short: CameraSample = { estimate: 1_500_000, limitation: "bandwidth", loss: 0 };
  feed(quality, short, 2 + 2);
  check(quality.rung.name === "720p", "the link could not carry it");
  check(feed(quality, good, 2 + 7).length === 0, "the next try waits twice as long");
  check(feed(quality, good, 1)[0] === "1080p", "eight clean readings");
  feed(quality, good, 16);
  feed(quality, short, 2);
  check(quality.rung.name === "720p", "down again after it held");
  check(feed(quality, good, 2 + 3).length === 0 && feed(quality, good, 1)[0] === "1080p", "a held upgrade resets the wait");
}

/* --- the camera's size --- */
{
  const quality = new CameraQuality(fhd);
  feed(quality, good, 7);
  check(quality.setCapture(hd) && quality.rung.name === "720p", "a smaller picture brings the rung down to it");
  check(quality.setCapture(fhd) && quality.rung.name === "1080p", "and the link's rung comes back with a larger one");
  check(!quality.setCapture({ width: 0, height: 0 }), "an unknown size changes nothing");
  check(quality.setCapture({ width: 1080, height: 810 }) && quality.rung.name === "720p", "their view turned sideways for a while");
  feed(quality, good, 30);
  check(quality.rung.name === "720p", "the rung stays at the picture's top while it lasts");
  check(quality.setCapture({ width: 886, height: 1920 }) && quality.rung.name === "1080p", "and is back at once when it ends");
}
{
  const quality = new CameraQuality(fhd);
  feed(quality, good, 6);
  quality.pause();
  check(feed(quality, good, 5).length === 0, "after a pause the count starts again");
  check(feed(quality, good, 1)[0] === "1080p", "and finishes");
}
{
  // A voice call whose camera comes on later: the ladder was paused from the start.
  const quality = new CameraQuality(fhd);
  quality.pause();
  quality.pause();
  check(feed(quality, good, 6).length === 0, "a pause before the first reading keeps the opening settle");
  check(feed(quality, good, 1)[0] === "1080p", "seven readings in");
}

/* --- what the encoder is told --- */
{
  const at720 = cameraEncoding(CAMERA_LADDER[4], { width: 1920, height: 1080 });
  check(at720.scaleResolutionDownBy === 1.5 && at720.maxBitrate === 2_200_000 && at720.maxFramerate === 30, "720p from a 1080p camera");
  check(cameraEncoding(CAMERA_LADDER[4], { width: 1080, height: 1920 }).scaleResolutionDownBy === 1.5, "a portrait camera too");
  check(cameraEncoding(CAMERA_LADDER[5], { width: 1280, height: 720 }).scaleResolutionDownBy === 1, "never enlarged");
  const tall = cameraEncoding(CAMERA_LADDER[4], { width: 886, height: 1920 });
  check(Math.abs(tall.scaleResolutionDownBy - Math.sqrt((886 * 1920) / (1280 * 720))) < 1e-9, "a tall cut keeps its shape at the rung's pixels");
  check(cameraEncoding(CAMERA_LADDER[0], {}).scaleResolutionDownBy === 1, "an unknown size is sent as it is");
  const tile = cameraEncoding(tileOf(CAMERA_LADDER[4]), { width: 1920, height: 1080 });
  check(tile.scaleResolutionDownBy === 3 && tile.maxBitrate === 350_000 && tile.maxFramerate === 15, "the tile while sharing");
  check(JSON.stringify(tileOf(CAMERA_LADDER[5])) === JSON.stringify(CAMERA_TILE), "the tile from the top rung is the tile");
  const low = cameraEncoding(tileOf(CAMERA_LADDER[1]), { width: 1920, height: 1080 });
  check(low.scaleResolutionDownBy === 4 && low.maxBitrate === 300_000 && low.maxFramerate === 15, "a camera below the tile stays below it");
}

/* --- reading the stats --- */
{
  const report = [
    { id: "OT1", type: "outbound-rtp", kind: "video", qualityLimitationReason: "bandwidth" },
    { id: "RI1", type: "remote-inbound-rtp", kind: "video", fractionLost: 0.0625 },
    { id: "T1", type: "transport", selectedCandidatePairId: "CP2" },
    { id: "CP1", type: "candidate-pair", nominated: false, availableOutgoingBitrate: 99 },
    { id: "CP2", type: "candidate-pair", nominated: true, state: "succeeded", availableOutgoingBitrate: 1_234_567 },
  ];
  const sample = readCameraSample(report)!;
  check(sample !== null, "a reading");
  check(sample.estimate === 1_234_567, "the selected pair's estimate");
  check(sample.limitation === "bandwidth", "the encoder's limitation");
  check(sample.loss === 0.0625, "the reported loss");
  const bare = readCameraSample([{ id: "OT1", type: "outbound-rtp", kind: "video" }]);
  check(bare !== null && bare.estimate === null && bare.limitation === null && bare.loss === null, "a browser without those stats");
  const nominated = readCameraSample(report.filter((stat) => stat.type !== "transport"));
  check(nominated?.estimate === 1_234_567, "without a transport, the nominated pair");
  check(readCameraSample(report.filter((stat) => stat.type !== "outbound-rtp")) === null, "nothing sent yet: no reading");
}

console.log("video quality selftest: ok");
