/**
 * Our camera, framed (docs/calls.md, "Framing and Center Stage"): a track that carries the camera
 * cut to the other side's view and, with Center Stage on, following the faces in it. The work
 * happens in two workers (framingWorker.ts, faceWorker.ts); here the camera's frames are handed
 * to them and their output becomes a track again.
 *
 * Browsers with `MediaStreamTrackProcessor` and `MediaStreamTrackGenerator` on the page (Chrome,
 * Edge) frame; elsewhere `frameCamera` gives null and the camera goes out as it comes. A framed
 * track that shows no frame within 5 s of the camera running, or stops while the camera still
 * runs, gives up (`onStall`), and the caller sends the camera itself again.
 */
import type { Size } from "./framing";
import type { FramingIn, FramingOut } from "./framingWorker";

type TrackProcessor = { readonly readable: ReadableStream<VideoFrame> };
type TrackGenerator = MediaStreamTrack & { readonly writable: WritableStream<VideoFrame> };
type ProcessorConstructor = new (init: { track: MediaStreamTrack }) => TrackProcessor;
type GeneratorConstructor = new (init: { kind: "video" }) => TrackGenerator;

function constructors(): { Processor: ProcessorConstructor; Generator: GeneratorConstructor } | null {
  const scope = globalThis as unknown as { MediaStreamTrackProcessor?: ProcessorConstructor; MediaStreamTrackGenerator?: GeneratorConstructor };
  const Processor = scope.MediaStreamTrackProcessor;
  const Generator = scope.MediaStreamTrackGenerator;
  if (typeof Processor !== "function" || typeof Generator !== "function" || typeof Worker !== "function") return null;
  if (typeof OffscreenCanvas !== "function" || typeof VideoFrame !== "function") return null;
  return { Processor, Generator };
}

/** Whether this browser can frame the camera (and so open it at up to 4K). */
export function framingSupported(): boolean {
  return constructors() !== null;
}

export type FramedCamera = {
  /** What goes out, and what our own picture shows. */
  readonly track: MediaStreamTrack;
  /** The output's size, once the first frame came through. */
  size(): Size | null;
  /** Whether Center Stage can follow faces here (false once the face detector failed to load). */
  canFollow(): boolean;
  setView(view: Size | null): void;
  setFollow(on: boolean): void;
  /** Stops the output and both workers; the camera itself is the caller's to stop. */
  stop(): void;
};

/** How long the camera may run without a framed frame coming out before the camera goes out itself. */
const STALL_MS = 5_000;
const STALL_TICK_MS = 250;

export function frameCamera(
  camera: MediaStreamTrack,
  options: {
    view: Size | null;
    follow: boolean;
    /** The output's size changed (the encoder's shrink depends on it). */
    onSize: () => void;
    /** Whether Center Stage can follow faces changed. */
    onFollowChange: () => void;
    /** No frame came out: send the camera itself instead. */
    onStall: () => void;
  },
): FramedCamera | null {
  const api = constructors();
  if (!api) return null;
  let processor: TrackProcessor;
  let generator: TrackGenerator;
  let framing: Worker;
  let faces: Worker;
  try {
    processor = new api.Processor({ track: camera });
    generator = new api.Generator({ kind: "video" });
    framing = new Worker(new URL("./framingWorker.ts", import.meta.url), { type: "module" });
    faces = new Worker(new URL("./faceWorker.ts", import.meta.url), { type: "module" });
  } catch {
    return null;
  }
  let size: Size | null = null;
  let follows = true;
  let stopped = false;
  const channel = new MessageChannel();
  faces.postMessage({ type: "port", port: channel.port2 }, [channel.port2]);

  // Only time the camera actually delivers counts: a camera the system paused, or one still
  // opening, is no reason to give up.
  let waited = 0;
  const stall = setInterval(() => {
    if (stopped || size !== null) {
      clearInterval(stall);
      return;
    }
    if (camera.readyState === "live" && !camera.muted) waited += STALL_TICK_MS;
    if (waited >= STALL_MS) {
      clearInterval(stall);
      options.onStall();
    }
  }, STALL_TICK_MS);
  generator.addEventListener("ended", () => {
    if (!stopped && camera.readyState === "live") options.onStall();
  });
  framing.onmessage = (event: MessageEvent<FramingOut>) => {
    if (stopped) return;
    const message = event.data;
    if (message.type === "size") {
      size = message.size;
      options.onSize();
    } else if (message.type === "faces-unavailable") {
      follows = false;
      options.onFollowChange();
    } else if (message.type === "failed" && camera.readyState === "live") {
      options.onStall();
    }
  };
  framing.onerror = () => {
    if (!stopped) options.onStall();
  };
  const start: FramingIn = {
    type: "start",
    readable: processor.readable,
    writable: generator.writable,
    faces: channel.port1,
    view: options.view,
    follow: options.follow,
  };
  try {
    framing.postMessage(start, [processor.readable, generator.writable, channel.port1] as unknown as Transferable[]);
  } catch {
    clearInterval(stall);
    framing.terminate();
    faces.terminate();
    generator.stop();
    return null;
  }
  return {
    track: generator,
    size: () => size,
    canFollow: () => follows,
    setView(view) {
      if (!stopped) framing.postMessage({ type: "view", view } satisfies FramingIn);
    },
    setFollow(on) {
      if (!stopped) framing.postMessage({ type: "follow", on } satisfies FramingIn);
    },
    stop() {
      if (stopped) return;
      stopped = true;
      clearInterval(stall);
      generator.stop();
      framing.terminate();
      faces.terminate();
    },
  };
}
