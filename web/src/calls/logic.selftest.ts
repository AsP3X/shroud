/**
 * What each side of a call shows, which signals are believed, and when an ICE restart may go.
 * Run: npx esbuild src/calls/logic.selftest.ts --bundle --platform=node --format=esm | node --input-type=module
 */
import { ApiError } from "../api/client";
import {
  CAMERA_UNAVAILABLE,
  CallFailure,
  RestartGate,
  RESTART_GAP_MS,
  SeenSignals,
  callClock,
  callErrorText,
  cameraErrorText,
  cameraOnlyFailure,
  cameraVideoSdp,
  endedText,
  h264Level,
  incomingStaysInBanner,
  isLive,
  linkState,
  mediaErrorText,
  fingerprintsMatch,
  readSignal,
  readViewSize,
  screenErrorText,
  screenSoundSdp,
  screenVideoSdp,
  sameId,
  sdpFingerprint,
  sdpWithoutCandidates,
  signalTypeOf,
  statusLine,
  videoLayout,
  voiceSdp,
  type CallView,
} from "./logic";
import {
  DEFAULT_SCREEN_QUALITY,
  parseScreenQuality,
  screenBitrate,
  screenMotion,
  SCREEN_CAPTURE_CEILING,
  screenQualityText,
  screenScaleDown,
  screenVideoConstraints,
} from "./screenQuality";

function check(ok: boolean, what: string): void {
  if (!ok) throw new Error(`calls logic selftest: ${what}`);
}

/* --- the status table in docs/calls.md --- */
const table: [string, string | null, string | null, string | null][] = [
  // status, reason, caller sees, callee sees
  ["rejected", "rejected", "Declined", null],
  ["missed", "declined", "Declined", null],
  ["missed", "timeout", "No answer", "Missed call"],
  ["cancelled", "cancelled", null, "Missed call"],
  ["cancelled", "connection_lost", "Connection lost", "Missed call"],
  ["ended", "hangup", "Call ended", "Call ended"],
  ["ended", "connection_lost", "Connection lost", "Connection lost"],
];
for (const [status, reason, caller, callee] of table) {
  check(endedText(status, reason, "caller") === caller, `caller on ${status}/${reason}`);
  check(endedText(status, reason, "callee") === callee, `callee on ${status}/${reason}`);
}
check(endedText("ended", null, "caller") === "Call ended", "an ended call without a reason");
check(endedText("busy", null, "callee") === "Call ended", "a status this client does not know");
check(isLive("ringing") && isLive("active") && !isLive("ended") && !isLive("missed"), "live statuses");

/* --- errors --- */
check(callErrorText(new ApiError("CALL_BUSY", "x", 409), "ana") === "ana is on another call.", "busy peer");
check(callErrorText(new ApiError("CALL_IN_PROGRESS", "x", 409), "ana") === "You’re already in a call.", "busy us");
check(callErrorText(new ApiError("FORBIDDEN", "x", 403), "ana") === "You can’t call ana.", "not a contact");
check(callErrorText(new ApiError("transport", "x", 0), "ana").startsWith("Couldn’t reach"), "offline");
check(callErrorText(new CallFailure("Exactly this."), "ana") === "Exactly this.", "a prepared failure");
check(callErrorText(new Error("boom"), "ana") === "Couldn’t start the call.", "anything else");
const denied = { name: "NotAllowedError", message: "denied" };
check(mediaErrorText(denied, false) === "Allow microphone access in your browser to call.", "denied, calling");
check(mediaErrorText(denied, true) === "Allow microphone access in your browser to answer calls.", "denied, answering");
check(mediaErrorText({ name: "NotFoundError" }, false).startsWith("No microphone"), "no microphone");
check(mediaErrorText({ name: "NotReadableError" }, false).includes("another app"), "microphone in use");
check(mediaErrorText(new Error("?"), false) === "Couldn’t start your microphone.", "unknown media error");
check(cameraOnlyFailure(denied) && cameraOnlyFailure({ name: "NotFoundError" }), "camera failures worth audio only");
check(!cameraOnlyFailure({ name: "TypeError" }) && !cameraOnlyFailure(null), "not a camera failure");
check(cameraErrorText(denied) === "Allow camera access in your browser to turn on video.", "camera denied mid-call");
check(cameraErrorText({ name: "NotFoundError" }) === "No camera found.", "no camera");
check(cameraErrorText({ name: "NotReadableError" }).includes("another app"), "camera in use");
check(cameraErrorText(null) === CAMERA_UNAVAILABLE, "camera failed some other way");

/* --- which picture fills the screen --- */
for (const phase of ["outgoing", "connecting", "active"] as const) {
  check(videoLayout({ phase }, true, true) === "theirs", `${phase}: their picture wins`);
  check(videoLayout({ phase }, false, true) === "theirs", `${phase}: theirs alone`);
  check(videoLayout({ phase }, false, false) === null, `${phase}: no picture, the face`);
}
check(videoLayout({ phase: "outgoing" }, true, false) === "mine", "placing a call with the camera on: ours fills it");
check(videoLayout({ phase: "connecting" }, true, false) === "mine", "and while it connects");
check(videoLayout({ phase: "active" }, true, false) === null, "mid-call, ours stays in the corner over their face");

/* --- the clock and the status line --- */
check(callClock(0) === "00:00", "zero");
check(callClock(42_900) === "00:42", "seconds round down");
check(callClock(12 * 60_000 + 5_000) === "12:05", "minutes");
check(callClock(3_723_000) === "1:02:03", "hours");
check(callClock(-5) === "00:00" && callClock(Number.NaN) === "00:00", "nonsense reads zero");

const base: CallView = {
  key: 1,
  phase: "outgoing",
  callId: null,
  role: "caller",
  modality: "voice",
  peer: { id: "p", username: "ana" },
  dialing: true,
  reconnecting: false,
  connectedAt: null,
  micOn: true,
  cameraOn: false,
  cameraPending: false,
  canVideo: true,
  canSwitchCamera: false,
  centerStage: true,
  canCenterStage: false,
  mirrorSelf: true,
  remoteMic: true,
  remoteCamera: false,
  remoteVideo: false,
  screenOn: false,
  screenPending: false,
  screenSound: false,
  shareSupported: true,
  canShare: false,
  screenQuality: DEFAULT_SCREEN_QUALITY,
  remoteScreen: false,
  localStream: null,
  remoteStream: null,
  screenStream: null,
  remoteScreenStream: null,
  audioBlocked: false,
  endedText: null,
  notice: null,
  minimized: false,
  safety: null,
  keyChanged: false,
};
check(statusLine(base, 0) === "Calling…", "placing the ring");
check(statusLine({ ...base, dialing: false }, 0) === "Ringing…", "ringing");
check(statusLine({ ...base, phase: "incoming", role: "callee" }, 0) === "Incoming voice call", "voice ring");
check(statusLine({ ...base, phase: "incoming", modality: "video" }, 0) === "Incoming video call", "video ring");
check(statusLine({ ...base, phase: "connecting" }, 0) === "Connecting…", "connecting");
check(statusLine({ ...base, phase: "active", connectedAt: 1_000 }, 66_000) === "01:05", "the timer");
check(statusLine({ ...base, phase: "active", connectedAt: 1_000, reconnecting: true }, 66_000) === "Reconnecting…", "recovering");
check(statusLine({ ...base, phase: "ended", endedText: "Declined" }, 0) === "Declined", "ended");

/* --- an incoming ring stays a banner until it is opened or connects --- */
check(incomingStaysInBanner({ key: 1, phase: "incoming", role: "callee" }, null), "a new ring is a banner");
check(!incomingStaysInBanner({ key: 1, phase: "incoming", role: "callee" }, 1), "opening the ring takes the screen");
check(incomingStaysInBanner({ key: 1, phase: "ended", role: "callee" }, null), "a ring that ends unseen stays a banner");
check(!incomingStaysInBanner({ key: 1, phase: "ended", role: "callee" }, 1), "a call that connected ends on the full screen");
check(!incomingStaysInBanner({ key: 1, phase: "connecting", role: "callee" }, null), "answering opens the screen");
check(!incomingStaysInBanner({ key: 1, phase: "ended", role: "caller" }, null), "the caller already has the full screen");

/* --- signals --- */
check(signalTypeOf("offer") === "sdp_offer" && signalTypeOf("restart") === "renegotiate", "signal types");
{
  const plain =
    "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111 0\r\na=rtpmap:111 opus/48000/2\r\n" +
    "a=fmtp:111 minptime=10;sprop-stereo=1;useinbandfec=0\r\na=rtpmap:0 PCMU/8000\r\na=fmtp:0 comfort=1\r\n";
  const tuned = voiceSdp(plain);
  check(
    tuned.includes("a=fmtp:111 minptime=10;sprop-stereo=0;useinbandfec=1;usedtx=1;stereo=0;maxaveragebitrate=32000"),
    "opus gains error correction, silence suppression, and a speech bitrate",
  );
  check(!tuned.includes("useinbandfec=0") && !tuned.includes("sprop-stereo=1"), "the old opus values are replaced");
  check(tuned.includes("a=fmtp:0 comfort=1"), "another codec's fmtp is left alone");
  check(tuned.startsWith("v=0\r\n") && tuned === voiceSdp(tuned), "line endings stay, and a second pass changes nothing");
  const inserted = voiceSdp("v=0\nm=audio 9 UDP/TLS/RTP/SAVPF 111\na=rtpmap:111 opus/48000/2\n");
  check(
    inserted ===
      "v=0\nm=audio 9 UDP/TLS/RTP/SAVPF 111\na=rtpmap:111 opus/48000/2\n" +
        "a=fmtp:111 useinbandfec=1;usedtx=1;stereo=0;sprop-stereo=0;maxaveragebitrate=32000\n",
    "a missing fmtp line is added after the opus map",
  );
  check(voiceSdp("v=0\r\n") === "v=0\r\n", "an sdp without opus is unchanged");
  const neighbor = voiceSdp("v=0\r\na=rtpmap:111 opus/48000/2\r\na=fmtp:1110 useinbandfec=0\r\n");
  check(neighbor.includes("a=fmtp:1110 useinbandfec=0"), "payload 111 does not rewrite 1110");
  check(neighbor.includes("a=fmtp:111 useinbandfec=1"), "opus still gets its own fmtp line");
}
const offer = readSignal("sdp_offer", { t: "offer", sdp: "v=0", restart: true, n: 1 });
check(offer?.t === "offer" && offer.restart && offer.n === 1, "an offer");
check(readSignal("sdp_offer", { t: "offer", sdp: "v=0", n: 1 })?.t === "offer", "restart defaults to false");
check(readSignal("sdp_answer", { t: "offer", sdp: "v=0", n: 1 }) === null, "a body claiming another type");
check(readSignal("sdp_offer", { t: "offer", sdp: "", n: 1 }) === null, "an empty sdp");
check(readSignal("sdp_offer", { t: "offer", sdp: "v=0", n: 0 }) === null, "n starts at 1");
check(readSignal("sdp_offer", { t: "offer", sdp: "v=0", n: 1.5 }) === null, "n is a whole number");
check(readSignal("sdp_offer", { t: "offer", sdp: "v=0" }) === null, "n is required");
check(readSignal("sdp_offer", { t: "toString", n: 1 }) === null, "no prototype keys as types");
const ice = readSignal("ice_candidate", {
  t: "ice",
  cs: [
    { candidate: "candidate:1 1 udp 1 10.0.0.1 5000 typ host", sdpMid: "0", sdpMLineIndex: 0 },
    { candidate: "candidate:2", sdpMid: null, sdpMLineIndex: 1 },
    { candidate: "", sdpMid: "0", sdpMLineIndex: 0 },
    { candidate: "candidate:3" },
    "junk",
  ],
  n: 2,
});
check(ice?.t === "ice" && ice.cs.length === 2, "only usable candidates stay");
check(ice?.t === "ice" && ice.cs[1].sdpMid === null && ice.cs[1].sdpMLineIndex === 1, "an index alone will do");
check(readSignal("ice_candidate", { t: "ice", cs: "no", n: 2 }) === null, "candidates are a list");
check(readSignal("renegotiate", { t: "restart", n: 3 })?.t === "restart", "a restart request");
const media = readSignal("media_state", { t: "media", mic: false, camera: true, n: 4 });
check(media?.t === "media" && !media.mic && media.camera, "media state");
check(media?.t === "media" && media.screen === undefined, "an older app says nothing about screens");
const sharing = readSignal("media_state", { t: "media", mic: true, camera: false, screen: true, n: 5 });
check(sharing?.t === "media" && sharing.screen === true, "a shared screen");
const notSharing = readSignal("media_state", { t: "media", mic: true, camera: false, screen: false, n: 6 });
check(notSharing?.t === "media" && notSharing.screen === false, "an app that knows screens and shares none");
check(readSignal("media_state", { t: "media", mic: true, camera: false, screen: "yes", n: 7 }) === null, "screen is a boolean");

/* --- the screen's sound: its own section, its own Opus settings --- */
{
  const offer = [
    "v=0",
    "m=audio 9 UDP/TLS/RTP/SAVPF 111",
    "a=rtpmap:111 opus/48000/2",
    "a=fmtp:111 minptime=10;useinbandfec=1",
    "m=video 9 UDP/TLS/RTP/SAVPF 96",
    "a=rtpmap:96 VP8/90000",
    "m=video 9 UDP/TLS/RTP/SAVPF 96",
    "a=rtpmap:96 VP8/90000",
    "m=audio 9 UDP/TLS/RTP/SAVPF 111",
    "a=rtpmap:111 opus/48000/2",
    "a=fmtp:111 minptime=10;useinbandfec=1",
    "",
  ].join("\r\n");
  const tuned = screenSoundSdp(voiceSdp(offer));
  const fmtps = tuned.split("\r\n").filter((line) => line.startsWith("a=fmtp:111"));
  check(fmtps.length === 2, "one fmtp per audio section");
  check(fmtps[0].includes("stereo=0") && fmtps[0].includes("maxaveragebitrate=32000"), `the microphone stays speech (${fmtps[0]})`);
  check(
    fmtps[1].includes("stereo=1") && fmtps[1].includes("sprop-stereo=1") && fmtps[1].includes("maxaveragebitrate=128000") && fmtps[1].includes("usedtx=0"),
    `the screen's sound is stereo music (${fmtps[1]})`,
  );
  check(screenSoundSdp(tuned) === tuned, "a second pass changes nothing");
  const bare = offer.replace(/a=fmtp:111 minptime=10;useinbandfec=1\r\n(?![\s\S]*m=audio)/, "");
  const inserted = screenSoundSdp(bare).split("\r\n");
  const at = inserted.lastIndexOf("a=rtpmap:111 opus/48000/2");
  check(inserted[at + 1]?.startsWith("a=fmtp:111 ") && inserted[at + 1].includes("stereo=1"), "an fmtp is added where there was none");
  const older = offer.split("\r\nm=video")[0] + "\r\n";
  check(screenSoundSdp(older) === older, "an sdp with one audio section is unchanged");
}

/* --- VP8 first on the screen's picture, the camera's order left alone --- */
{
  const sdp = [
    "v=0",
    "m=audio 9 UDP/TLS/RTP/SAVPF 111",
    "a=rtpmap:111 opus/48000/2",
    "m=video 9 UDP/TLS/RTP/SAVPF 102 103 96 97",
    "a=rtpmap:102 H264/90000",
    "a=rtpmap:103 rtx/90000",
    "a=rtpmap:96 VP8/90000",
    "a=rtpmap:97 rtx/90000",
    "m=video 9 UDP/TLS/RTP/SAVPF 102 103 96 97",
    "a=rtpmap:102 H264/90000",
    "a=rtpmap:103 rtx/90000",
    "a=rtpmap:96 VP8/90000",
    "a=rtpmap:97 rtx/90000",
    "m=audio 9 UDP/TLS/RTP/SAVPF 111",
    "",
  ].join("\r\n");
  const tuned = screenVideoSdp(sdp);
  const mlines = tuned.split("\r\n").filter((line) => line.startsWith("m=video"));
  check(mlines[0] === "m=video 9 UDP/TLS/RTP/SAVPF 102 103 96 97", "the camera keeps its order");
  check(mlines[1] === "m=video 9 UDP/TLS/RTP/SAVPF 96 102 103 97", `VP8 leads the screen's section (${mlines[1]})`);
  check(screenVideoSdp(tuned) === tuned, "a second pass changes nothing");
  const older = sdp.split("\r\nm=video")[0] + "\r\n";
  check(screenVideoSdp(older) === older, "an sdp without the screen's section is unchanged");
}
{
  // A browser's order: VP8 first, H.264 later in two profiles.
  const sdp = [
    "v=0",
    "m=audio 9 UDP/TLS/RTP/SAVPF 111",
    "a=rtpmap:111 opus/48000/2",
    "m=video 9 UDP/TLS/RTP/SAVPF 96 97 102 103 127",
    "a=rtpmap:96 VP8/90000",
    "a=rtpmap:97 rtx/90000",
    "a=rtpmap:102 H264/90000",
    "a=rtpmap:103 rtx/90000",
    "a=rtpmap:127 H264/90000",
    "m=video 9 UDP/TLS/RTP/SAVPF 96 97 102 103 127",
    "a=rtpmap:96 VP8/90000",
    "a=rtpmap:97 rtx/90000",
    "a=rtpmap:102 H264/90000",
    "a=rtpmap:103 rtx/90000",
    "a=rtpmap:127 H264/90000",
    "",
  ].join("\r\n");
  const tuned = cameraVideoSdp(screenVideoSdp(sdp));
  const mlines = tuned.split("\r\n").filter((line) => line.startsWith("m=video"));
  check(mlines[0] === "m=video 9 UDP/TLS/RTP/SAVPF 102 127 96 97 103", `H.264 leads the camera's section (${mlines[0]})`);
  check(mlines[1] === "m=video 9 UDP/TLS/RTP/SAVPF 96 97 102 103 127", "the screen keeps VP8 first");
  check(cameraVideoSdp(tuned) === tuned, "a second pass changes nothing");
  const noH264 = sdp.replace(/H264/g, "VP9");
  check(cameraVideoSdp(noH264) === noH264, "without H.264 the camera's section is unchanged");
  const voiceOnly = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n";
  check(cameraVideoSdp(voiceOnly) === voiceOnly, "an sdp without video is unchanged");
}
{
  // The H.264 level each side declares for the camera: at least 4.0 (1080p30).
  check(h264Level("42e01f") === "42e028", "3.1 is raised to 4.0");
  check(h264Level("640C1F") === "640C28", "the profile part keeps its case");
  check(h264Level("640c34") === "640c34", "a higher level stays");
  check(h264Level("42e028") === "42e028", "4.0 stays");
  check(h264Level("42e0") === "42e0" && h264Level("zzzzzz") === "zzzzzz", "a malformed value is left alone");
  const sdp = [
    "v=0",
    "m=video 9 UDP/TLS/RTP/SAVPF 96 102 127 103",
    "a=rtpmap:96 VP8/90000",
    "a=rtpmap:102 H264/90000",
    "a=fmtp:102 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f",
    "a=rtpmap:127 H264/90000",
    "a=fmtp:127 profile-level-id=640c34;packetization-mode=1",
    "a=rtpmap:103 rtx/90000",
    "a=fmtp:103 apt=102",
    "m=video 9 UDP/TLS/RTP/SAVPF 96 102",
    "a=rtpmap:96 VP8/90000",
    "a=rtpmap:102 H264/90000",
    "a=fmtp:102 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f",
    "",
  ].join("\r\n");
  const tuned = cameraVideoSdp(sdp);
  const lines = tuned.split("\r\n");
  check(lines[1] === "m=video 9 UDP/TLS/RTP/SAVPF 102 127 96 103", `H.264 first (${lines[1]})`);
  check(lines[4] === "a=fmtp:102 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e028", `the camera's 3.1 becomes 4.0 (${lines[4]})`);
  check(lines[6] === "a=fmtp:127 profile-level-id=640c34;packetization-mode=1", "a higher level stays");
  check(lines[8] === "a=fmtp:103 apt=102", "rtx is untouched");
  check(lines[12].endsWith("profile-level-id=42e01f"), "the screen's section keeps its level");
  check(cameraVideoSdp(tuned) === tuned, "a second pass changes nothing");
  const lf = sdp.replace(/\r\n/g, "\n");
  check(cameraVideoSdp(lf) === tuned.replace(/\r\n/g, "\n"), "LF line endings are kept");
}

check(screenErrorText({ name: "NotAllowedError", message: "Permission denied" }) === null, "a closed picker says nothing");
check(screenErrorText({ name: "NotAllowedError", message: "Permission denied by system" })?.includes("screen recording") === true, "the system's refusal says what to do");
check(screenErrorText({ name: "NotReadableError", message: "" })?.includes("couldn’t be captured") === true, "a failed capture");
check(readSignal("media_state", { t: "media", mic: "no", camera: true, n: 4 }) === null, "flags are booleans");
{
  const withView = readSignal("media_state", { t: "media", mic: true, camera: true, screen: false, view: { w: 1179, h: 2556 }, n: 5 });
  check(withView?.t === "media" && withView.view?.w === 1179 && withView.view.h === 2556, "a media state carries their view");
  for (const bad of [{ w: 0, h: 10 }, { w: 1.5, h: 10 }, { w: 10_001, h: 10 }, { w: "1179", h: 2556 }, [1179, 2556], null, 7]) {
    const read = readSignal("media_state", { t: "media", mic: true, camera: false, view: bad, n: 6 });
    check(read?.t === "media" && read.view === undefined && read.camera === false, `a bad view is left out, the rest counts (${JSON.stringify(bad)})`);
  }
  check(JSON.stringify(readViewSize({ w: 10_000, h: 1 })) === JSON.stringify({ w: 10_000, h: 1 }), "the limits count");
}

const ek = btoa(String.fromCharCode(...new Uint8Array(32)));
const withKey = readSignal("sdp_offer", { t: "offer", sdp: "v=0", restart: false, ek, n: 1 });
check(withKey?.t === "offer" && withKey.ek === ek, "an offer carries its ephemeral key");
check(readSignal("sdp_answer", { t: "answer", sdp: "v=0", ek: "%%%", n: 1 }) === null, "a bad ephemeral key");
check(readSignal("sdp_offer", { t: "offer", sdp: "v=0", ek: btoa("short"), n: 1 }) === null, "a short ephemeral key");
const described = "v=0\r\na=candidate:1 1 udp 1 10.0.0.1 9 typ host\r\na=fingerprint:sha-256 AA:BB:CC\r\n";
check(
  sdpWithoutCandidates(described) === "v=0\r\na=fingerprint:sha-256 AA:BB:CC\r\n",
  "candidate lines leave the session description",
);
check(sdpFingerprint(described) === "aa:bb:cc", "the fingerprint is read in lower case");
check(sdpFingerprint("v=0\r\n") === null, "a description without one");
check(fingerprintsMatch("AA:BB:CC", "aa bb cc"), "the certificate fingerprint matches ignoring case and separators");
check(!fingerprintsMatch("AA:BB:CC", "aa:bb:cd"), "a different certificate does not");
check(!fingerprintsMatch("", "aa"), "an empty fingerprint does not match");

const seen = new SeenSignals();
check(seen.first("DEV-A", 1) && seen.first("dev-a", 2), "new numbers count");
check(!seen.first("dev-a", 1), "a number seen before is dropped, whatever the id's case");
check(seen.first("dev-b", 1), "numbers are per device");
check(seen.first("dev-a", 0 + 7) && !seen.first("dev-a", 7), "out of order is fine, twice is not");
check(seen.newerMedia("DEV-A", 4) && seen.newerMedia("dev-a", 9), "newer media states count");
check(!seen.newerMedia("dev-a", 6), "an older media state (the server's copy, late) changes nothing");
check(!seen.newerMedia("dev-a", 9), "nor the same one again");
check(seen.newerMedia("dev-b", 2), "media states are ordered per device");

/* --- restarts, link state, ids --- */
const gate = new RestartGate();
check(gate.waitMs(50_000) === 0, "the first restart goes at once");
gate.mark(50_000);
check(gate.waitMs(50_000) === RESTART_GAP_MS, "then 10 s pass");
check(gate.waitMs(56_000) === 4_000, "counting down");
check(gate.waitMs(60_000) === 0, "and it may go again");

check(linkState("connected", "checking") === "connected", "connectionState wins");
check(linkState(undefined, "completed") === "connected", "ICE alone: completed");
check(linkState(undefined, "checking") === "connecting", "ICE alone: checking");
check(linkState(undefined, "disconnected") === "disconnected", "ICE alone: disconnected");
check(linkState("bogus", "failed") === "failed", "an unknown state falls back to ICE");
check(linkState(undefined, undefined) === "new", "nothing yet");
check(sameId("ABC", "abc") && !sameId("abc", null) && !sameId("", ""), "ids");


/* --- the screen's resolution and frame rate (screenQuality.ts) --- */
check(DEFAULT_SCREEN_QUALITY.resolution === "1080p" && DEFAULT_SCREEN_QUALITY.frameRate === 30, "1080p at 30 fps, as before the choice");
check(parseScreenQuality(null) === DEFAULT_SCREEN_QUALITY, "nothing stored: the default");
check(parseScreenQuality("{nonsense") === DEFAULT_SCREEN_QUALITY, "unreadable: the default");
{
  const kept = parseScreenQuality(JSON.stringify({ resolution: "source", frameRate: 60 }));
  check(kept.resolution === "source" && kept.frameRate === 60, "a stored choice reads back");
  const half = parseScreenQuality(JSON.stringify({ resolution: "4k", frameRate: 15 }));
  check(half.resolution === "1080p" && half.frameRate === 15, "an unknown part falls back alone");
  const odd = parseScreenQuality(JSON.stringify({ resolution: "720p", frameRate: 24 }));
  check(odd.resolution === "720p" && odd.frameRate === 30, "a frame rate not offered is the default");
}
{
  const hd = screenVideoConstraints({ resolution: "720p", frameRate: 15 });
  check(
    JSON.stringify(hd) === JSON.stringify({ width: { max: 1280 }, height: { max: 720 }, frameRate: { ideal: 15, max: 15 } }),
    "720p: inside 1280×720, at 15 fps",
  );
  const full = screenVideoConstraints({ resolution: "1080p", frameRate: 30 });
  check(JSON.stringify(full.width) === JSON.stringify({ max: 1920 }) && JSON.stringify(full.height) === JSON.stringify({ max: 1080 }), "1080p: inside 1920×1080");
  const source = screenVideoConstraints({ resolution: "source", frameRate: 60 });
  check(source.width === undefined && source.height === undefined, "source: no size limit, so applyConstraints lifts an earlier one");
  check(JSON.stringify(source.frameRate) === JSON.stringify({ ideal: 60, max: 60 }), "source at 60 fps");
}
check(screenBitrate(DEFAULT_SCREEN_QUALITY) === 2_500_000, "1080p at 30 fps keeps its 2.5 Mbps");
check(
  screenBitrate({ resolution: "720p", frameRate: 15 }) < screenBitrate({ resolution: "1080p", frameRate: 15 }) &&
    screenBitrate({ resolution: "1080p", frameRate: 60 }) < screenBitrate({ resolution: "source", frameRate: 60 }) &&
    screenBitrate({ resolution: "source", frameRate: 30 }) < screenBitrate({ resolution: "source", frameRate: 60 }),
  "more pixels or more frames: more bits",
);
check(screenMotion({ resolution: "1080p", frameRate: 30 }).hint === "detail", "up to 30 fps: sharp text");
check(screenMotion({ resolution: "1080p", frameRate: 30 }).degradation === "maintain-resolution", "and frames give way first");
check(screenMotion({ resolution: "720p", frameRate: 60 }).hint === "motion", "60 fps: encoded for motion");
check(screenMotion({ resolution: "720p", frameRate: 60 }).degradation === "balanced", "and gives up some of each");
check(screenQualityText({ resolution: "source", frameRate: 60 }) === "Source · 60 fps", "said as it is picked");
check(JSON.stringify(SCREEN_CAPTURE_CEILING) === JSON.stringify({ frameRate: { max: 60 } }), "the capture opens at the most any choice uses");
check(screenScaleDown({ width: 3840, height: 2160 }, { resolution: "1080p", frameRate: 30 }) === 2, "4K into 1080p: halved");
check(screenScaleDown({ width: 2560, height: 1600 }, { resolution: "720p", frameRate: 30 }) === 1600 / 720, "16:10 into 720p: by its height, the tighter side");
check(screenScaleDown({ width: 1280, height: 720 }, { resolution: "1080p", frameRate: 30 }) === 1, "never enlarged");
check(screenScaleDown({ width: 3840, height: 2160 }, { resolution: "source", frameRate: 30 }) === 1, "source: as it is");
check(screenScaleDown({}, { resolution: "720p", frameRate: 30 }) === 1, "no size known: left alone");
console.log("calls logic selftest ok");
