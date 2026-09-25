import { useEffect, useRef, useState, type ReactNode } from "react";
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
import { Avatar } from "./Avatar";

/**
 * The call screen: full-screen and always dark (like the media viewers) while a call rings, is
 * connected, runs, or has just ended; a pill (a bar on phones) once minimized, so the chats stay
 * usable during a call. Escape minimizes and never hangs up.
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
  icon,
  onClick,
  tone = "plain",
  on = false,
  disabled = false,
}: {
  label: string;
  icon: ReactNode;
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
    >
      <span className="call-ctl-disc" aria-hidden="true">
        {icon}
      </span>
      <span className="call-ctl-label">{label}</span>
    </button>
  );
}

function CallScreen({ view }: { view: CallView }) {
  const root = useRef<HTMLDivElement>(null);
  const video = view.modality === "video";
  const live = view.phase === "outgoing" || view.phase === "connecting" || view.phase === "active";
  const theirVideo = video && live && view.remoteVideo && view.remoteCamera && view.remoteStream !== null;
  const mine = video && live && view.hasCamera && view.localStream !== null;
  /* Before their picture arrives, ours fills the screen (as FaceTime does); then it moves to the corner. */
  const selfFull = mine && !theirVideo && view.cameraOn;
  const remote = useStream(theirVideo ? view.remoteStream : null, view.remoteVideo);
  const self = useStream(mine ? view.localStream : null, view.hasCamera);
  const name = view.peer.username;

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

  return (
    <div
      ref={root}
      className={`call call-${view.phase}${theirVideo ? " has-video" : ""}${selfFull ? " self-full" : ""}`}
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

      <div className="call-who">
        <div className={`call-avatar${view.phase === "incoming" ? " ringing" : ""}`}>
          <Avatar name={name} seed={view.peer.id} size="lg" />
        </div>
        <h2 className="call-name">{name}</h2>
        <p className="call-status" aria-live={statusLive(view)}>
          <StatusText view={view} />
        </p>
        {chips.length > 0 ? <div className="call-chips">{chips}</div> : null}
        {view.notice ? (
          <p className="call-notice" role="status">
            {view.notice}
          </p>
        ) : null}
      </div>

      {view.audioBlocked && live ? (
        <button type="button" className="call-sound" onClick={resumeCallAudio}>
          <Volume2 size={16} aria-hidden="true" />
          Turn on sound
        </button>
      ) : null}

      <div className="call-controls">
        {view.phase === "incoming" ? (
          <>
            <Control label="Decline" tone="end" icon={<PhoneOff size={26} />} onClick={declineCall} />
            <Control
              label="Accept"
              tone="accept"
              icon={video ? <Video size={26} /> : <Phone size={26} />}
              onClick={acceptCall}
            />
          </>
        ) : live ? (
          <>
            <Control
              label={view.micOn ? "Mute" : "Unmute"}
              on={!view.micOn}
              icon={view.micOn ? <Mic size={24} /> : <MicOff size={24} />}
              onClick={toggleCallMute}
            />
            {video ? (
              <Control
                label={view.cameraOn ? "Stop video" : "Start video"}
                on={!view.cameraOn}
                disabled={!view.hasCamera}
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
          </>
        ) : null}
      </div>
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
