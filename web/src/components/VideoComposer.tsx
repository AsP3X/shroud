import {
  useEffect,
  useRef,
  useState,
  type FormEvent,
  type KeyboardEvent,
  type PointerEvent,
} from "react";
import { createPortal } from "react-dom";
import {
  AlertTriangle,
  ChevronLeft,
  Play,
  Plus,
  RotateCcw,
  Send,
  Volume2,
  VolumeX,
  X,
} from "lucide-react";
import { formatBytes } from "../format";
import { MAX_PHOTOS_PER_SEND } from "../media/prepareImage";
import {
  inspectVideo,
  VIDEO_ACCEPT,
  VideoCanceledError,
  videoFilmstrip,
  videoFiles,
  type VideoSendDraft,
} from "../media/prepareVideo";
import {
  clockLabel,
  effectiveTrim,
  MIN_CLIP_SECONDS,
  planVideo,
  VideoTooLongError,
  type VideoProbe,
  type VideoTrim,
} from "../media/videoPlan";

const MAX_CAPTION = 1024;
const STRIP_TILES = 14;

type Entry = {
  file: File;
  probe: VideoProbe | null;
  posterUrl: string | null;
  poster: Blob | null;
  tiles: (string | null)[];
  error: string | null;
  trim: VideoTrim | null;
  mute: boolean;
};

function emptyEntry(file: File): Entry {
  return {
    file,
    probe: null,
    posterUrl: null,
    poster: null,
    tiles: [],
    error: null,
    trim: null,
    mute: false,
  };
}

/**
 * Telegram-style send sheet for clips: looping preview, filmstrip trim, mute,
 * caption. Encoding waits until Send so the chat bubble can land immediately.
 */
export function VideoComposer({
  files,
  peerName,
  onFilesChange,
  onClose,
  onSend,
}: {
  files: File[];
  peerName: string;
  onFilesChange: (files: File[]) => void;
  onClose: () => void;
  onSend: (drafts: VideoSendDraft[], caption: string) => void;
}) {
  const [entries, setEntries] = useState<Entry[]>(() => files.map(emptyEntry));
  const [caption, setCaption] = useState("");
  const [selection, setSelection] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [playhead, setPlayhead] = useState(0);
  const [banner, setBanner] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const player = useRef<HTMLVideoElement>(null);
  const picker = useRef<HTMLInputElement>(null);
  const previewUrl = useRef<string | null>(null);
  const alive = useRef(true);
  const started = useRef(new Set<File>());
  const aborts = useRef(new Map<File, AbortController>());
  const tileUrls = useRef(new Set<string>());
  const bannerTimer = useRef<number | null>(null);
  const handedOff = useRef(false);
  const current = useRef({ files, entries, selection });
  current.current = { files, entries, selection };

  useEffect(() => {
    alive.current = true;
    const overflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      alive.current = false;
      for (const ctl of aborts.current.values()) ctl.abort();
      aborts.current.clear();
      document.body.style.overflow = overflow;
      if (previewUrl.current) URL.revokeObjectURL(previewUrl.current);
      for (const url of tileUrls.current) URL.revokeObjectURL(url);
      for (const entry of current.current.entries) {
        if (entry.posterUrl) URL.revokeObjectURL(entry.posterUrl);
      }
      if (bannerTimer.current) window.clearTimeout(bannerTimer.current);
    };
  }, []);

  useEffect(() => {
    function onKeyDown(event: globalThis.KeyboardEvent) {
      if (event.key === "Escape") onClose();
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  /* Keep the entry list aligned with `files`, then inspect each new clip once. */
  useEffect(() => {
    setEntries((prev) => {
      const keep = new Set(files);
      for (const entry of prev) {
        if (!keep.has(entry.file) && entry.posterUrl) URL.revokeObjectURL(entry.posterUrl);
      }
      const byFile = new Map(prev.map((item) => [item.file, item]));
      return files.map((file) => byFile.get(file) ?? emptyEntry(file));
    });
    setSelection((index) => Math.min(index, Math.max(0, files.length - 1)));

    for (const [file, ctl] of aborts.current) {
      if (files.includes(file)) continue;
      ctl.abort();
      aborts.current.delete(file);
      started.current.delete(file);
    }

    for (const file of files) {
      if (started.current.has(file)) continue;
      started.current.add(file);
      const ctl = new AbortController();
      aborts.current.set(file, ctl);
      void (async () => {
        try {
          const inspected = await inspectVideo(file, ctl.signal);
          if (!alive.current || !current.current.files.includes(file)) return;
          const posterUrl = inspected.poster ? URL.createObjectURL(inspected.poster) : null;
          setEntries((prev) =>
            prev.map((entry) =>
              entry.file === file
                ? {
                    ...entry,
                    probe: inspected.probe,
                    poster: inspected.poster,
                    posterUrl,
                    trim: { start: 0, end: inspected.probe.duration },
                  }
                : entry,
            ),
          );
          const tiles: (string | null)[] = Array.from({ length: STRIP_TILES }, () => null);
          try {
            await videoFilmstrip(
              file,
              STRIP_TILES,
              (index, tile) => {
                if (!alive.current) return;
                const url = URL.createObjectURL(tile);
                tileUrls.current.add(url);
                tiles[index] = url;
                setEntries((prev) =>
                  prev.map((item) => (item.file === file ? { ...item, tiles: [...tiles] } : item)),
                );
              },
              ctl.signal,
            );
          } catch (err) {
            if (err instanceof VideoCanceledError) return;
            /* trim still works without tiles */
          }
        } catch (err) {
          if (!alive.current || err instanceof VideoCanceledError) return;
          setEntries((prev) =>
            prev.map((entry) =>
              entry.file === file
                ? {
                    ...entry,
                    error: err instanceof Error ? err.message : "Couldn’t read this video.",
                  }
                : entry,
            ),
          );
        }
      })();
    }
  }, [files]);

  const entry = entries[selection] ?? null;
  const probe = entry?.probe ?? null;
  const trim = entry?.trim ?? (probe ? { start: 0, end: probe.duration } : { start: 0, end: 1 });
  const kept = probe ? (effectiveTrim(trim, probe.duration) ?? { start: 0, end: probe.duration }) : trim;

  useEffect(() => {
    const node = player.current;
    const file = entry?.file;
    if (!node || !file) return;
    if (previewUrl.current) URL.revokeObjectURL(previewUrl.current);
    const url = URL.createObjectURL(file);
    previewUrl.current = url;
    node.src = url;
    node.currentTime = kept.start;
    setPlayhead(kept.start);
    setPlaying(false);
    return () => {
      node.pause();
      node.removeAttribute("src");
      node.load();
    };
    // Re-arm only when the shown clip changes; trim seeks are handled below.
  }, [entry?.file]);

  useEffect(() => {
    const node = player.current;
    if (node) node.muted = Boolean(entry?.mute);
  }, [entry?.mute, entry?.file]);

  function flash(text: string) {
    setBanner(text);
    if (bannerTimer.current) window.clearTimeout(bannerTimer.current);
    bannerTimer.current = window.setTimeout(() => setBanner((now) => (now === text ? null : now)), 1400);
  }

  function patch(partial: Partial<Entry>) {
    if (!entry) return;
    setEntries((prev) => prev.map((item) => (item.file === entry.file ? { ...item, ...partial } : item)));
  }

  function setTrim(next: VideoTrim, seekTo?: number) {
    patch({ trim: next });
    const node = player.current;
    const at = seekTo ?? next.start;
    if (node) {
      node.pause();
      node.currentTime = at;
    }
    setPlayhead(at);
    setPlaying(false);
  }

  function toggleMute() {
    if (!probe?.audioCodec) return;
    const next = !entry?.mute;
    patch({ mute: next });
    flash(next ? "Sound will be removed" : "Sound will be kept");
  }

  function resetTrim() {
    if (!probe) return;
    setTrim({ start: 0, end: probe.duration });
  }

  function togglePlay() {
    const node = player.current;
    if (!node || !probe) return;
    if (node.paused) {
      if (node.currentTime < kept.start || node.currentTime >= kept.end - 0.05) {
        node.currentTime = kept.start;
      }
      void node.play();
    } else {
      node.pause();
    }
  }

  function remove(index: number) {
    const next = files.filter((_, i) => i !== index);
    if (next.length === 0) onClose();
    else onFilesChange(next);
  }

  function add(picked: File[]) {
    const room = MAX_PHOTOS_PER_SEND - files.length;
    if (room > 0 && picked.length) onFilesChange([...files, ...picked.slice(0, room)]);
  }

  function submit(event?: FormEvent) {
    event?.preventDefault();
    if (handedOff.current) return;
    const drafts: VideoSendDraft[] = [];
    for (const item of entries) {
      if (!item.probe || item.error) continue;
      drafts.push({
        file: item.file,
        trim: item.trim,
        mute: item.mute,
        poster: item.poster,
        probe: item.probe,
      });
    }
    if (drafts.length === 0) return;
    handedOff.current = true;
    setSending(true);
    onSend(drafts, caption.trim());
  }

  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key !== "Enter" || event.shiftKey || event.nativeEvent.isComposing) return;
    event.preventDefault();
    submit();
  }

  let estimate: { bytes: number; duration: number } | null = null;
  let tooLong: string | null = null;
  if (probe) {
    try {
      const plan = planVideo(probe, { trim: entry?.trim, mute: Boolean(entry?.mute) });
      estimate = { bytes: plan.estimatedBytes, duration: plan.duration };
    } catch (err) {
      tooLong = err instanceof VideoTooLongError ? err.message : "This video is too long to send.";
    }
  }

  const readyCount = entries.filter((item) => item.probe && !item.error).length;
  const inspecting = entries.some((item) => !item.probe && !item.error);
  const anyTooLong = entries.some((item) => {
    if (!item.probe || item.error) return false;
    try {
      planVideo(item.probe, { trim: item.trim, mute: item.mute });
      return false;
    } catch {
      return true;
    }
  });
  const canSend = readyCount > 0 && !inspecting && !tooLong && !anyTooLong && !sending;
  const canAdd = files.length < MAX_PHOTOS_PER_SEND;
  const title = files.length === 1 ? "Send video" : `Send ${files.length} videos`;

  return createPortal(
    <div className="vcompose" role="dialog" aria-modal="true" aria-label={title}>
      <header className="vcompose-bar">
        <span className="vcompose-to">
          To <strong>{peerName}</strong>
        </span>
        {canAdd ? (
          <button className="vcompose-add" type="button" onClick={() => picker.current?.click()}>
            <Plus size={14} />
            Add
          </button>
        ) : null}
      </header>

      {files.length > 1 ? (
        <ul className="vcompose-strip" aria-label="Videos to send">
          {entries.map((item, index) => (
            <li key={index}>
              <button
                type="button"
                className={index === selection ? "vcompose-thumb is-current" : "vcompose-thumb"}
                onClick={() => setSelection(index)}
              >
                {item.posterUrl ? <img src={item.posterUrl} alt="" draggable={false} /> : <span className="attach-placeholder skeleton" />}
                <span>{item.probe ? clockLabel(item.probe.duration) : "…"}</span>
              </button>
              <button
                className="attach-remove"
                type="button"
                aria-label="Remove video"
                onClick={() => remove(index)}
              >
                <X size={12} />
              </button>
            </li>
          ))}
        </ul>
      ) : null}

      <div className="vcompose-stage" onClick={togglePlay}>
        {entry?.error ? (
          <span className="viewer-error">
            <AlertTriangle size={22} aria-hidden="true" />
            {entry.error}
          </span>
        ) : (
          <>
            <video
              ref={player}
              className="vcompose-video"
              playsInline
              preload="auto"
              onPlay={() => setPlaying(true)}
              onPause={() => setPlaying(false)}
              onTimeUpdate={(event) => {
                const node = event.currentTarget;
                setPlayhead(node.currentTime);
                if (node.currentTime >= kept.end - 0.04) {
                  node.currentTime = kept.start;
                }
              }}
              onClick={(event) => {
                event.stopPropagation();
                togglePlay();
              }}
            />
            {entry?.mute ? (
              <span className="vcompose-muted">
                <VolumeX size={12} />
                MUTED
              </span>
            ) : null}
            {!playing && probe ? (
              <span className="video-disc" aria-hidden="true">
                <Play size={22} fill="currentColor" />
              </span>
            ) : null}
          </>
        )}
      </div>

      <div className="vcompose-foot">
        {probe ? (
          <TrimStrip
            tiles={entry?.tiles ?? []}
            duration={probe.duration}
            trim={trim}
            playhead={playhead}
            onChange={(next, seekTo) => setTrim(next, seekTo)}
          />
        ) : (
          <div className="vtrim skeleton" aria-hidden="true" />
        )}
        <div className="vcompose-meta">
          <span>
            {estimate
              ? `${clockLabel(estimate.duration)}  ·  ≈${formatBytes(estimate.bytes)}`
              : inspecting
                ? "Reading…"
                : tooLong ?? " "}
          </span>
          {probe && kept.end - kept.start < probe.duration - 0.05 ? <em>TRIMMED</em> : null}
        </div>
        {tooLong ? (
          <p className="err" role="status">
            {tooLong}
          </p>
        ) : null}

        <form className="vcompose-send" onSubmit={submit}>
          <button className="vcompose-tool" type="button" aria-label="Back" onClick={onClose}>
            <ChevronLeft size={18} />
          </button>
          <button
            className={`vcompose-tool${entry?.mute ? " is-on" : ""}`}
            type="button"
            aria-label={entry?.mute ? "Sound off" : "Sound on"}
            disabled={!probe?.audioCodec}
            onClick={toggleMute}
          >
            {entry?.mute ? <VolumeX size={17} /> : <Volume2 size={17} />}
          </button>
          <button
            className="vcompose-tool"
            type="button"
            aria-label="Reset trim"
            disabled={!probe || kept.end - kept.start >= probe.duration - 0.05}
            onClick={resetTrim}
          >
            <RotateCcw size={16} />
          </button>
          <div className="compose-grow" data-value={`${caption} `}>
            <textarea
              className="compose-field"
              rows={1}
              placeholder="Add a caption…"
              aria-label={`Caption for ${peerName}`}
              maxLength={MAX_CAPTION}
              value={caption}
              onChange={(event) => setCaption(event.target.value)}
              onKeyDown={onKeyDown}
              enterKeyHint="send"
            />
          </div>
          {files.length > 1 ? <span className="vcompose-count">{files.length}</span> : null}
          <button
            className={sending ? "send is-busy" : "send"}
            type="submit"
            aria-label={files.length === 1 ? "Send video" : `Send ${files.length} videos`}
            disabled={!canSend}
          >
            {sending ? <span className="send-spin" aria-hidden="true" /> : <Send size={16} />}
          </button>
        </form>
      </div>

      {banner ? <p className="vcompose-banner">{banner}</p> : null}

      <input
        ref={picker}
        type="file"
        accept={VIDEO_ACCEPT}
        multiple
        hidden
        onChange={(event) => {
          add(videoFiles(event.target.files));
          event.target.value = "";
        }}
      />
    </div>,
    document.body,
  );
}

function TrimStrip({
  tiles,
  duration,
  trim,
  playhead,
  onChange,
}: {
  tiles: (string | null)[];
  duration: number;
  trim: VideoTrim;
  playhead: number;
  onChange: (trim: VideoTrim, seekTo: number) => void;
}) {
  const track = useRef<HTMLDivElement>(null);
  const dragging = useRef<"start" | "end" | null>(null);

  function xToTime(clientX: number): number {
    const node = track.current;
    if (!node || !(duration > 0)) return 0;
    const rect = node.getBoundingClientRect();
    const ratio = (clientX - rect.left) / Math.max(1, rect.width);
    return Math.min(duration, Math.max(0, ratio * duration));
  }

  function move(which: "start" | "end", clientX: number) {
    const raw = xToTime(clientX);
    const minimum = Math.min(MIN_CLIP_SECONDS, duration);
    if (which === "start") {
      const start = Math.max(0, Math.min(raw, trim.end - minimum));
      onChange({ start, end: trim.end }, start);
    } else {
      const end = Math.min(duration, Math.max(raw, trim.start + minimum));
      onChange({ start: trim.start, end }, end);
    }
  }

  function onHandleDown(which: "start" | "end", event: PointerEvent<HTMLButtonElement>) {
    event.preventDefault();
    event.currentTarget.setPointerCapture(event.pointerId);
    dragging.current = which;
    move(which, event.clientX);
  }

  function onHandleMove(event: PointerEvent<HTMLButtonElement>) {
    if (!dragging.current) return;
    move(dragging.current, event.clientX);
  }

  function onHandleUp() {
    dragging.current = null;
  }

  const startPct = duration > 0 ? (trim.start / duration) * 100 : 0;
  const endPct = duration > 0 ? (trim.end / duration) * 100 : 100;
  const headPct = duration > 0 ? (playhead / duration) * 100 : 0;

  return (
    <div className="vtrim" aria-label="Trim video">
      <div ref={track} className="vtrim-track">
        <div className="vtrim-film">
          {(tiles.length ? tiles : Array.from({ length: STRIP_TILES }, () => null)).map((src, index) =>
            src ? <img key={index} src={src} alt="" draggable={false} /> : <span key={index} className="vtrim-gap" />,
          )}
        </div>
        <span className="vtrim-dim" style={{ left: 0, width: `${startPct}%` }} />
        <span className="vtrim-dim" style={{ left: `${endPct}%`, right: 0 }} />
        <span className="vtrim-keep" style={{ left: `${startPct}%`, width: `${Math.max(0, endPct - startPct)}%` }} />
        {playhead >= trim.start && playhead <= trim.end ? (
          <span className="vtrim-playhead" style={{ left: `${headPct}%` }} />
        ) : null}
        <button
          type="button"
          className="vtrim-handle"
          style={{ left: `${startPct}%` }}
          aria-label="Trim start"
          onPointerDown={(event) => onHandleDown("start", event)}
          onPointerMove={onHandleMove}
          onPointerUp={onHandleUp}
          onPointerCancel={onHandleUp}
        />
        <button
          type="button"
          className="vtrim-handle"
          style={{ left: `${endPct}%` }}
          aria-label="Trim end"
          onPointerDown={(event) => onHandleDown("end", event)}
          onPointerMove={onHandleMove}
          onPointerUp={onHandleUp}
          onPointerCancel={onHandleUp}
        />
      </div>
    </div>
  );
}
