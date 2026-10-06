/*
 * Our camera's frames on their way out (docs/calls.md, "Framing and Center Stage"): each one is cut
 * to the output's shape where `Framer` says (framing.ts) and drawn at the output's fixed size, so
 * the encoder always gets one size however far the cut zooms. In a worker, so a call in a
 * background tab keeps its picture. Every fifth of a second, with Center Stage on, a copy of a
 * frame goes to the face worker (faceWorker.ts) and its faces steer the cut.
 */
import { Framer, outputSize, shapeChanged, type Size } from "./framing";
import type { FaceOut } from "./faceWorker";

export type FramingIn =
  | {
      type: "start";
      readable: ReadableStream<VideoFrame>;
      writable: WritableStream<VideoFrame>;
      faces: MessagePort;
      view: Size | null;
      follow: boolean;
    }
  | { type: "view"; view: Size | null }
  | { type: "follow"; on: boolean };

export type FramingOut =
  /** The output's size, first and after each change. */
  | { type: "size"; size: Size }
  /** The face detector cannot run in this browser: Center Stage is not offered. */
  | { type: "faces-unavailable" }
  /** Frames stopped going out while the camera still ran (a lost graphics context): send the camera itself. */
  | { type: "failed" }
  /** Where the faces are in the output (0…1), for our own small picture to centre on. */
  | { type: "focus"; x: number; y: number };

/** How often faces are looked for. */
const DETECT_MS = 200;
/** The focus is told at most this often, and only when it moved. */
const FOCUS_MS = 50;

const framer = new Framer();
let view: Size | null = null;
let follow = true;
let facesAvailable = true;
let capture: Size = { width: 0, height: 0 };
let output: Size | null = null;
let canvas: OffscreenCanvas | null = null;
let context: OffscreenCanvasRenderingContext2D | null = null;
let facePort: MessagePort | null = null;
let detecting = false;
let lastDetect = -Infinity;
let detectId = 0;
let lastFocus = { x: 0.5, y: 0.5 };
let lastFocusAt = -Infinity;

function tellFocus(now: number): void {
  if (now - lastFocusAt < FOCUS_MS) return;
  const focus = framer.focus();
  if (Math.abs(focus.x - lastFocus.x) < 0.002 && Math.abs(focus.y - lastFocus.y) < 0.002) return;
  lastFocus = focus;
  lastFocusAt = now;
  post({ type: "focus", x: focus.x, y: focus.y });
}

function post(message: FramingOut): void {
  self.postMessage(message);
}

/** The output for this frame's size and their view; a change starts the cut afresh. */
function shape(width: number, height: number): Size {
  const sizeChanged = width !== capture.width || height !== capture.height;
  if (sizeChanged || !output) {
    capture = { width, height };
    output = outputSize(capture, view);
    framer.configure(capture, output, follow && facesAvailable);
    post({ type: "size", size: output });
  }
  return output;
}

function lookForFaces(frame: VideoFrame, now: number): void {
  if (!follow || !facesAvailable || !facePort || detecting || now - lastDetect < DETECT_MS) return;
  detecting = true;
  lastDetect = now;
  const copy = frame.clone();
  facePort.postMessage({ type: "frame", frame: copy, id: ++detectId }, [copy]);
}

async function pump(readable: ReadableStream<VideoFrame>, writable: WritableStream<VideoFrame>): Promise<void> {
  const reader = readable.getReader();
  const writer = writable.getWriter();
  let frame: VideoFrame | null = null;
  try {
    for (;;) {
      const read = await reader.read();
      if (read.done || !read.value) break;
      frame = read.value;
      const now = performance.now();
      const out = shape(frame.displayWidth, frame.displayHeight);
      lookForFaces(frame, now);
      const cut = framer.next(now);
      tellFocus(now);
      const whole = cut.x === 0 && cut.y === 0 && cut.width === capture.width && cut.height === capture.height;
      if (whole && out.width === capture.width && out.height === capture.height) {
        // Nothing to cut or shrink: the frame goes out as it came.
        const passed = frame;
        frame = null;
        await writer.write(passed);
        continue;
      }
      if (!canvas || canvas.width !== out.width || canvas.height !== out.height) {
        canvas = new OffscreenCanvas(out.width, out.height);
        context = canvas.getContext("2d", { alpha: false });
      }
      if (!context) {
        const passed = frame;
        frame = null;
        await writer.write(passed);
        continue;
      }
      context.drawImage(frame, cut.x, cut.y, cut.width, cut.height, 0, 0, out.width, out.height);
      const next = new VideoFrame(canvas, { timestamp: frame.timestamp });
      frame.close();
      frame = null;
      await writer.write(next);
    }
  } catch {
    // The output was stopped, or drawing failed: the page sends the camera itself again.
    frame?.close();
    post({ type: "failed" });
  } finally {
    reader.releaseLock();
    await writer.close().catch(() => undefined);
  }
}

self.onmessage = (event: MessageEvent<FramingIn>) => {
  const message = event.data;
  switch (message.type) {
    case "start":
      view = message.view;
      follow = message.follow;
      facePort = message.faces;
      facePort.onmessage = (reply: MessageEvent<FaceOut>) => {
        detecting = false;
        if (reply.data.type === "unavailable") {
          facesAvailable = false;
          if (output) framer.configure(capture, output, false);
          post({ type: "faces-unavailable" });
          return;
        }
        if (reply.data.id === detectId) framer.faces(reply.data.faces, performance.now());
      };
      void pump(message.readable, message.writable);
      return;
    case "view": {
      if (!shapeChanged(view, message.view)) return;
      view = message.view;
      if (capture.width > 0) {
        output = outputSize(capture, view);
        framer.configure(capture, output, follow && facesAvailable);
        post({ type: "size", size: output });
      }
      return;
    }
    case "follow":
      follow = message.on;
      if (output) framer.configure(capture, output, follow && facesAvailable);
      return;
  }
};
