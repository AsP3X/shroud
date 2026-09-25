import { useEffect, useRef, useState, type CSSProperties, type ReactNode } from "react";
import { createPortal } from "react-dom";
import {
  Maximize2,
  Mic,
  MicOff,
  Minimize2,
  Phone,
  PhoneOff,
  ShieldCheck,
  SwitchCamera,
  Video,
  VideoOff,
  Volume2,
  X,
} from "lucide-react";
import { statusLine, type CallView } from "../calls/logic";
import {
  acceptCall,
  declineCall,
  dismissCall,
  hangUpCall,
  resumeCallAudio,
  setCallMinimized,
  switchCallCamera,
  toggleCallCamera,
  toggleCallMute,
} from "../calls/service";
import { useCallView } from "../calls/store";
import { Avatar, avatarPalette } from "./Avatar";

/**
 * The call screen: full-screen and always dark (like the media viewers) while a call rings, is
 * connected, runs, or has just ended; a pill (a bar on phones) once minimized, so the chats stay
 * usable during a call. Escape minimizes and never hangs up.
 *
 * Both variants share one layout, as on iOS: the top bar, the person in the middle (their picture
 * behind everything once a video call shows one), and a floating dock of controls centred at the
 * bottom.
 */
export function CallOverlay() {
  const view = useCallView();
  if (!view) return null;
  if (view.minimized && view.phase !== "incoming") return <CallPill view={view} />;
  return createPortal(<CallScreen view={view} />, document.body);
}

/** Re-renders every half second while `ticking`, for the call timer. */
function useNow(ticking: boolean): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!ticking) return;
    setNow(Date.now());
    const timer = window.setInterval(() => setNow(Date.now()), 500);
    return () => window.clearInterval(timer);
  }, [ticking]);
  return now;
}

/** The line under the name; its own component, so the timer re-renders nothing else. */
function StatusText({ view }: { view: CallView }) {
  const now = useNow(view.phase === "active" && !view.reconnecting);
  return <>{statusLine(view, now)}</>;
}

/** Changes worth announcing; the running timer is not one. */
function statusLive(view: CallView): "polite" | "off" {
  return view.phase === "active" && !view.reconnecting ? "off" : "polite";
}

/** A new key per kind of status, so the text cross-fades when the kind changes, not every second. */
function statusKey(view: CallView): string {
  if (view.phase === "outgoing") return view.dialing ? "calling" : "ringing";
  if (view.phase === "active") return view.reconnecting ? "reconnecting" : "clock";
  return view.phase;
}

/** Attaches a stream to a <video>; `revision` re-attaches it when tracks arrive. */
function useStream(stream: MediaStream | null, revision: unknown) {
  const ref = useRef<HTMLVideoElement>(null);
  useEffect(() => {
    const element = ref.current;
    if (!element) return;
    element.srcObject = null;
    element.srcObject = stream;
    if (stream) void element.play().catch(() => undefined);
  }, [stream, revision]);
  return ref;
}

function Control({
  label,
  ariaLabel,
  icon,
  iconKey,
  onClick,
  tone = "plain",
  on = false,
  disabled = false,
}: {
  label: string;
  /** Spoken name when the visible label does not say the action ("Camera"). */
  ariaLabel?: string;
  icon: ReactNode;
  /** Changes when the glyph does (mic to mic-off): the new one pops in. */
  iconKey?: string;
  onClick: () => void;
  tone?: "plain" | "end" | "accept";
  /** A switch that is on (muted, camera off): the disc turns light. */
  on?: boolean;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      className={`call-ctl call-ctl-${tone}${on ? " is-on" : ""}`}
      onClick={onClick}
      disabled={disabled}
      aria-label={ariaLabel}
      aria-pressed={tone === "plain" && iconKey !== undefined ? on : undefined}
    >
      <span className="call-ctl-disc" aria-hidden="true">
        <span className="call-ctl-glyph" key={iconKey ?? label}>
          {icon}
        </span>
      </span>
      <span className="call-ctl-label">{label}</span>
    </button>
  );
}

/** Live transcription is a later feature: until it lands, the slot has no lines and stays hidden. */
const NO_CAPTIONS: readonly string[] = [];

/**
 * The live transcription slot, between the person and the dock: the last few lines of what is
 * being said, the newest at the bottom, older ones fading out at the top. With no lines it
 * takes no space: nothing is transcribed yet, and an empty slot would push the controls up.
 */
export function CallCaptions({ lines }: { lines: readonly string[] }) {
  const shown = lines.slice(-3);
  return (
    <div
      className={`call-captions${shown.length === 0 ? " is-empty" : ""}`}
      role="log"
      aria-label="Live transcription"
      aria-live="polite"
      aria-hidden={shown.length === 0}
    >
      {shown.map((line, index) => (
        <p key={`${lines.length - shown.length + index}`}>{line}</p>
      ))}
    </div>
  );
}

function CallScreen({ view }: { view: CallView }) {
  const root = useRef<HTMLDivElement>(null);
  const video = view.modality === "video";
  const live = view.phase === "outgoing" || view.phase === "connecting" || view.phase === "active";
  const ringing = view.phase === "outgoing" || view.phase === "connecting";
  const clock = view.phase === "active" && !view.reconnecting;
  const theirVideo = video && live && view.remoteVideo && view.remoteCamera && view.remoteStream !== null;
  const mine = video && live && view.hasCamera && view.localStream !== null;
  /* Before their picture arrives, ours fills the screen (as FaceTime does); then it moves to the corner. */
  const selfFull = mine && !theirVideo && view.cameraOn;
  const remote = useStream(theirVideo ? view.remoteStream : null, view.remoteVideo);
  const self = useStream(mine ? view.localStream : null, view.hasCamera);
  const name = view.peer.username;
  /* The backdrop glows in the peer's avatar colour, so each call looks like its person. */
  const [tintTop, tintBottom] = avatarPalette(view.peer.id);
  const tint = { "--call-tint": tintTop, "--call-tint-deep": tintBottom } as CSSProperties;

  /* Focus comes into the call (not onto a button: a stray Enter must not answer or hang up),
     and goes back where it was once the screen closes. */
  useEffect(() => {
    const before = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    root.current?.focus({ preventScroll: true });
    return () => {
      if (document.activeElement === document.body || document.activeElement === null) {
        before?.focus?.({ preventScroll: true });
      }
    };
  }, [view.key]);

  /* Escape minimizes; it never hangs up. Whatever the call covers does not hear it either. */
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      event.preventDefault();
      event.stopPropagation();
      if (live) setCallMinimized(true);
    };
    window.addEventListener("keydown", onKey, true);
    return () => window.removeEventListener("keydown", onKey, true);
  }, [live]);

  const chips: ReactNode[] = [];
  if (live && !view.remoteMic) {
    chips.push(
      <span className="call-chip" key="mic">
        <MicOff size={13} aria-hidden="true" />
        {name} is muted
      </span>,
    );
  }
  if (video && view.phase === "active" && !view.remoteCamera) {
    chips.push(
      <span className="call-chip" key="camera">
        <VideoOff size={13} aria-hidden="true" />
        Camera off
      </span>,
    );
  }

  const classes = [
    "call",
    `call-${view.phase}`,
    video ? "is-video" : "is-voice",
    theirVideo ? "has-video" : "",
    selfFull ? "self-full" : "",
    ringing ? "is-ringing" : "",
    view.reconnecting && view.phase === "active" ? "is-reconnecting" : "",
  ]
    .filter(Boolean)
    .join(" ");

  return (
    <div
      ref={root}
      className={classes}
      style={tint}
      role="dialog"
      aria-modal="true"
      aria-label={`Call with ${name}`}
      tabIndex={-1}
    >
      <div className="call-backdrop" aria-hidden="true" />
      {video ? (
        <video ref={remote} className="call-remote" autoPlay playsInline muted hidden={!theirVideo} />
      ) : null}
      {video ? (
        <div className={`call-self${selfFull ? " full" : ""}`} hidden={!mine}>
          <video
            ref={self}
            className={view.mirrorSelf ? "mirrored" : undefined}
            autoPlay
            playsInline
            muted
            hidden={!view.cameraOn}
          />
          {!view.cameraOn ? (
            <span className="call-self-off" aria-hidden="true">
              <VideoOff size={20} />
            </span>
          ) : null}
        </div>
      ) : null}
      <div className="call-shade call-shade-top" aria-hidden="true" />
      <div className="call-shade call-shade-bottom" aria-hidden="true" />

      <header className="call-top">
        <span className="call-e2e">
          <ShieldCheck size={13} aria-hidden="true" />
          End-to-end encrypted
        </span>
        {live ? (
          <button
            type="button"
            className="call-icon-btn"
            aria-label="Minimize call"
            title="Minimize (Esc)"
            onClick={() => setCallMinimized(true)}
          >
            <Minimize2 size={18} />
          </button>
        ) : view.phase === "ended" ? (
          <button type="button" className="call-icon-btn" aria-label="Close" title="Close" onClick={dismissCall}>
            <X size={18} />
          </button>
        ) : null}
      </header>

      {/* The person: the avatar at the exact centre of the screen, name and clock hanging below it
          (in a pill at the top instead once a picture fills the screen). */}
      <section className="call-stage">
        <div className="call-who">
          <div className={`call-avatar${view.phase === "incoming" ? " ringing" : ""}`}>
            <Avatar name={name} seed={view.peer.id} size="lg" />
          </div>
          <div className="call-id">
            <div className="call-id-main">
              <h2 className="call-name">{name}</h2>
              <p className={`call-status${clock ? " is-clock" : ""}`} aria-live={statusLive(view)}>
                <span className="call-status-text" key={statusKey(view)}>
                  <StatusText view={view} />
                </span>
              </p>
            </div>
            {chips.length > 0 ? <div className="call-chips">{chips}</div> : null}
            {view.notice ? (
              <p className="call-notice" role="status">
                {view.notice}
              </p>
            ) : null}
          </div>
        </div>
      </section>

      <footer className="call-dock">
        <CallCaptions lines={NO_CAPTIONS} />
        {view.audioBlocked && live ? (
          <button type="button" className="call-sound" onClick={resumeCallAudio}>
            <Volume2 size={16} aria-hidden="true" />
            Turn on sound
          </button>
        ) : null}
        {view.phase === "incoming" ? (
          <div className="call-bar call-bar-ring" role="group" aria-label="Incoming call">
            <Control label="Decline" tone="end" icon={<PhoneOff size={28} />} onClick={declineCall} />
            <Control
              label="Accept"
              tone="accept"
              icon={video ? <Video size={28} /> : <Phone size={28} />}
              onClick={acceptCall}
            />
          </div>
        ) : live ? (
          <div className="call-bar" role="group" aria-label="Call controls">
            <Control
              label={view.micOn ? "Mute" : "Unmute"}
              on={!view.micOn}
              iconKey={view.micOn ? "mic" : "mic-off"}
              icon={view.micOn ? <Mic size={24} /> : <MicOff size={24} />}
              onClick={toggleCallMute}
            />
            {video ? (
              <Control
                label="Camera"
                ariaLabel={view.cameraOn ? "Turn camera off" : "Turn camera on"}
                on={!view.cameraOn}
                disabled={!view.hasCamera}
                iconKey={view.cameraOn ? "cam" : "cam-off"}
                icon={view.cameraOn ? <Video size={24} /> : <VideoOff size={24} />}
                onClick={toggleCallCamera}
              />
            ) : null}
            {video && view.canSwitchCamera ? (
              <Control
                label="Flip"
                disabled={!view.cameraOn}
                icon={<SwitchCamera size={24} />}
                onClick={switchCallCamera}
              />
            ) : null}
            <Control label="End" tone="end" icon={<PhoneOff size={26} />} onClick={hangUpCall} />
          </div>
        ) : null}
      </footer>
    </div>
  );
}

function CallPill({ view }: { view: CallView }) {
  const restore = useRef<HTMLButtonElement>(null);
  const name = view.peer.username;
  const live = view.phase !== "ended";

  /* Minimized from the call screen, focus lands here rather than on the page's top. */
  useEffect(() => {
    if (document.activeElement === document.body || document.activeElement === null) {
      restore.current?.focus({ preventScroll: true });
    }
  }, []);

  return (
    <div className={`call-pill${live ? "" : " is-ended"}`} role="region" aria-label={`Call with ${name}`}>
      <button
        ref={restore}
        type="button"
        className="call-pill-main"
        aria-label={`Return to call with ${name}`}
        onClick={() => setCallMinimized(false)}
      >
        <span className="call-pill-dot" aria-hidden="true" />
        <span className="call-pill-copy">
          <strong>{name}</strong>
          <span className="call-pill-status" aria-live={statusLive(view)}>
            <StatusText view={view} />
          </span>
        </span>
        <Maximize2 size={15} className="call-pill-expand" aria-hidden="true" />
      </button>
      {live ? (
        <>
          <button
            type="button"
            className={`call-pill-btn${view.micOn ? "" : " is-on"}`}
            aria-label={view.micOn ? "Mute" : "Unmute"}
            aria-pressed={!view.micOn}
            title={view.micOn ? "Mute" : "Unmute"}
            onClick={toggleCallMute}
          >
            {view.micOn ? <Mic size={17} /> : <MicOff size={17} />}
          </button>
          <button
            type="button"
            className="call-pill-btn end"
            aria-label="End call"
            title="End call"
            onClick={hangUpCall}
          >
            <PhoneOff size={17} />
          </button>
        </>
      ) : null}
    </div>
  );
}
