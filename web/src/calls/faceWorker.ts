/*
 * Faces for Center Stage, in a worker of their own so a detection (about 40 ms) never holds up the
 * frames going out (framingWorker.ts). It takes a camera frame now and then, finds the faces in it
 * with YuNet on the ONNX runtime, and answers with their boxes in the frame's pixels. Frames and
 * faces stay in this browser (docs/calls.md, "Framing and Center Stage").
 *
 * The ONNX runtime's files come from this origin, as for Whisper (whisper-worker.ts): one thread,
 * no proxy, nothing fetched elsewhere.
 */
import wasmUrl from "onnxruntime-web/ort-wasm-simd-threaded.asyncify.wasm?url";
import wasmFactoryUrl from "onnxruntime-web/ort-wasm-simd-threaded.asyncify.mjs?url";
import modelUrl from "./models/face_detection_yunet_2023mar.onnx?url";
import { decodeYunet, facesInPicture, yunetInput, yunetScale, YUNET_SIZE, type Face } from "./faceDetect";

type OrtModule = typeof import("onnxruntime-web");
type Session = Awaited<ReturnType<OrtModule["InferenceSession"]["create"]>>;

/** From the framing worker: a frame to look at (it is closed here), or its port. */
type FaceIn = { type: "port"; port: MessagePort } | { type: "frame"; frame: VideoFrame; id: number };
export type FaceOut = { type: "faces"; id: number; faces: Face[] } | { type: "unavailable" };

let session: Promise<{ ort: OrtModule; session: Session } | null> | null = null;
let canvas: OffscreenCanvas | null = null;

function load(): Promise<{ ort: OrtModule; session: Session } | null> {
  session ??= (async () => {
    try {
      const ort = await import("onnxruntime-web");
      ort.env.wasm.wasmPaths = { wasm: wasmUrl, mjs: wasmFactoryUrl };
      ort.env.wasm.numThreads = 1;
      ort.env.wasm.proxy = false;
      return { ort, session: await ort.InferenceSession.create(modelUrl, { executionProviders: ["wasm"] }) };
    } catch {
      return null;
    }
  })();
  return session;
}

async function detect(frame: VideoFrame): Promise<Face[] | null> {
  const width = frame.displayWidth;
  const height = frame.displayHeight;
  const loaded = await load();
  if (!loaded || width <= 0 || height <= 0) {
    frame.close();
    return loaded ? [] : null;
  }
  canvas ??= new OffscreenCanvas(YUNET_SIZE, YUNET_SIZE);
  const context = canvas.getContext("2d", { willReadFrequently: true });
  if (!context) {
    frame.close();
    return [];
  }
  const scale = yunetScale(width, height);
  context.fillStyle = "#000";
  context.fillRect(0, 0, YUNET_SIZE, YUNET_SIZE);
  context.drawImage(frame, 0, 0, width * scale, height * scale);
  frame.close();
  const pixels = context.getImageData(0, 0, YUNET_SIZE, YUNET_SIZE).data;
  const input = new loaded.ort.Tensor("float32", yunetInput(pixels), [1, 3, YUNET_SIZE, YUNET_SIZE]);
  const result = await loaded.session.run({ [loaded.session.inputNames[0]]: input });
  const outputs: Record<string, ArrayLike<number>> = {};
  for (const [name, tensor] of Object.entries(result)) outputs[name] = tensor.data as ArrayLike<number>;
  return facesInPicture(decodeYunet(outputs), scale);
}

function listen(port: MessagePort): void {
  port.onmessage = (event: MessageEvent<FaceIn>) => {
    const message = event.data;
    if (message.type !== "frame") return;
    void detect(message.frame).then(
      (faces) => port.postMessage(faces ? ({ type: "faces", id: message.id, faces } satisfies FaceOut) : ({ type: "unavailable" } satisfies FaceOut)),
      () => port.postMessage({ type: "faces", id: message.id, faces: [] } satisfies FaceOut),
    );
  };
}

self.onmessage = (event: MessageEvent<FaceIn>) => {
  if (event.data.type === "port") listen(event.data.port);
};
