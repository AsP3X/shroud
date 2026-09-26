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
import { incomingStaysInBanner, statusLine, videoLayout, type CallView } from "../calls/logic";
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
import { SpeakingIndicator } from "./SpeakingIndicator";

/**
 * The call screen: full-screen and always dark (like the media viewers) while a call rings out, is
 * connected, runs, or has just ended; a pill (a bar on phones) once minimized, so the chats stay
 * usable during a call. A ring coming in is a banner at the top first, as on the phone, and opens
 * the full screen from there. Escape minimizes and never hangs up.
 *
 * Both variants share one layout, as on iOS: the top bar, the person in the middle (their picture
 * behind everything once a video call shows one), and a floating dock of controls centred at the
 * bottom.
 */
export function CallOverlay() {
  const view = useCallView();
  /* Set once the call is on the full screen: the callee opened the banner, or it connected.
     A later ending then stays on that screen. A ring that never got there ends on the banner. */
  const [openedKey, setOpenedKey] = useState<number | null>(null);
  useEffect(() => {
    if (!view) return;
    if (view.phase === "outgoing" || view.phase === "connecting" || view.phase === "active") {
      setOpenedKey(view.key);
    }
  }, [view]);
  if (!view) return null;
  if (incomingStaysInBanner(view, openedKey)) {
    return createPortal(<CallBanner view={view} onExpand={() => setOpenedKey(view.key)} />, document.body);
  }
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

/**
 * One side's picture in a <video>. The stream goes on when the picture is wanted (a camera was
 * switched on), and `shown` turns true only with the first frame after that, so a camera coming
 * on never flashes black, nor the last picture from before it went off. Once not wanted, the
 * element keeps its last frame, so the picture fades out rather than blinking away.
 * `revision` re-attaches the stream when its tracks change.
 */
function usePicture(stream: MediaStream | null, wanted: boolean, revision: unknown) {
  const ref = useRef<HTMLVideoElement>(null);
  const [shown, setShown] = useState(false);
  useEffect(() => {
    setShown(false);
    const element = ref.current;
    if (!element || !stream || !wanted) return;
    let current = true;
    const show = () => {
      if (current) setShown(true);
    };
    element.srcObject = null;
    element.srcObject = stream;
    void element.play().catch(() => undefined);
    let frame: number | null = null;
    let timer: number | null = null;
    if (typeof element.requestVideoFrameCallback === "function") {
      frame = element.requestVideoFrameCallback(show);
    } else {
      // No frame callbacks: once the picture has a size, or after a moment.
      element.addEventListener("resize", show, { once: true });
      timer = window.setTimeout(show, 500);
    }
    return () => {
      current = false;
      if (frame !== null) element.cancelVideoFrameCallback(frame);
      if (timer !== null) window.clearTimeout(timer);
      element.removeEventListener("resize", show);
    };
  }, [stream, wanted, revision]);
  return { ref, shown: shown && wanted };
}

function Control({
  label,
  ariaLabel,
  title,
  icon,
  iconKey,
  onClick,
  tone = "plain",
  on = false,
  pending = false,
  disabled = false,
}: {
  label: string;
  /** Spoken name when the visible label does not say the action ("Video"). */
  ariaLabel?: string;
  /** Why it is disabled, on hover. */
  title?: string;
  icon: ReactNode;
  /** Changes when the glyph does (mic to mic-off): the new one pops in. */
  iconKey?: string;
  onClick: () => void;
  tone?: "plain" | "end" | "accept";
  /** A switch that is on (muted, video on): the disc turns light. */
  on?: boolean;
  /** Turning on takes a moment (a camera opening): the disc breathes until it is. */
  pending?: boolean;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      className={`call-ctl call-ctl-${tone}${on ? " is-on" : ""}${pending ? " is-pending" : ""}`}
      onClick={onClick}
      disabled={disabled}
      title={title}
      aria-label={ariaLabel}
      aria-busy={pending || undefined}
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
  const live = view.phase === "outgoing" || view.phase === "connecting" || view.phase === "active";
  const ringing = view.phase === "outgoing" || view.phase === "connecting";
  const clock = view.phase === "active" && !view.reconnecting;
  /* Voice or video is whatever the cameras say right now: either side can switch its own on or
     off mid-call, and the screen follows, back to the face when both are off. */
  const remote = usePicture(
    view.remoteStream,
    live && view.remoteVideo && view.remoteCamera && view.remoteStream !== null,
    view.remoteVideo,
  );
  const self = usePicture(view.localStream, live && view.cameraOn && view.localStream !== null, null);
  const layout = videoLayout(view, self.shown, remote.shown);
  const theirVideo = layout === "theirs";
  /* While the call is placed, our picture fills the screen (as FaceTime does); after that it sits
     in the corner, over their picture or their face. */
  const selfFull = layout === "mine";
  const name = view.peer.username;
  /* The backdrop carries a faint wash of the peer's avatar colour, so each call looks like its person. */
  const tint = { "--call-tint": avatarPalette(view.peer.id)[0] } as CSSProperties;

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

  const classes = [
    "call",
    `call-${view.phase}`,
    remote.shown || self.shown ? "is-video" : "is-voice",
    theirVideo ? "has-video" : "",
    selfFull ? "self-full" : "",
    ringing ? "is-ringing" : "",
    view.reconnecting && view.phase === "active" ? "is-reconnecting" : "",
  ]
    .filter(Boolean)
    .join(" ");
  const videoLabel = view.cameraOn ? "Turn video off" : "Turn video on";

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
      {/* Both pictures stay in place for the whole call, so a camera switched on mid-call fades
          in (and out again) over the face instead of rebuilding the screen. */}
      <video
        ref={remote.ref}
        className={`call-remote${theirVideo ? " is-shown" : ""}`}
        autoPlay
        playsInline
        muted
        aria-hidden="true"
      />
      <div
        className={`call-self${selfFull ? " full" : ""}${self.shown ? " is-shown" : ""}`}
        aria-hidden="true"
      >
        <video ref={self.ref} className={view.mirrorSelf ? "mirrored" : undefined} autoPlay playsInline muted />
      </div>
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
        {/* A new key when the picture takes over the screen or gives it back: the name rises into
            its new place (the pill at the top, or under the face) instead of jumping there. */}
        <div className="call-who" key={theirVideo || selfFull ? "over-picture" : "under-face"}>
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
            {/* "You're speaking": only while the call runs with an open mic; muting hides it. */}
            <SpeakingIndicator stream={view.localStream} active={view.phase === "active" && view.micOn} />
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
              icon={view.modality === "video" ? <Video size={28} /> : <Phone size={28} />}
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
            {/* Every call has it: video on makes a voice call a video call, off makes it voice again. */}
            <Control
              label="Video"
              ariaLabel={videoLabel}
              title={view.canVideo || view.cameraOn ? videoLabel : "Video isn’t available in this call"}
              on={view.cameraOn}
              pending={view.cameraPending}
              disabled={!view.canVideo && !view.cameraOn}
              iconKey={view.cameraOn ? "cam" : "cam-off"}
              icon={view.cameraOn ? <Video size={24} /> : <VideoOff size={24} />}
              onClick={toggleCallCamera}
            />
            {view.cameraOn && view.canSwitchCamera ? (
              <Control label="Flip" icon={<SwitchCamera size={24} />} onClick={switchCallCamera} />
            ) : null}
            <Control label="End" tone="end" icon={<PhoneOff size={26} />} onClick={hangUpCall} />
          </div>
        ) : null}
      </footer>
    </div>
  );
}

/**
 * An incoming call as a banner at the top of the window: who is calling, decline and accept,
 * with the chats still usable underneath. The rest of it opens the full screen.
 */
function CallBanner({ view, onExpand }: { view: CallView; onExpand: () => void }) {
  const root = useRef<HTMLDivElement>(null);
  const name = view.peer.username;
  const video = view.modality === "video";
  const ended = view.phase === "ended";
  const kind = ended ? (view.endedText ?? "Call ended") : video ? "Shroud video call" : "Shroud voice call";
  const who = (
    <>
      <span className="call-banner-avatar" aria-hidden="true">
        <Avatar name={name} seed={view.peer.id} size="md" />
      </span>
      <span className="call-banner-copy">
        <strong className="call-banner-name">{name}</strong>
        <span className="call-banner-kind" aria-live="polite">
          {ended ? null : video ? <Video size={13} aria-hidden="true" /> : <Phone size={13} aria-hidden="true" />}
          {kind}
        </span>
      </span>
    </>
  );

  /* Focus lands on the banner itself, not on Accept: a stray Enter must not answer.
     It does not move again when the ring ends, so typing in the chat is left alone. */
  useEffect(() => {
    const before = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    root.current?.focus({ preventScroll: true });
    return () => {
      if (document.activeElement === document.body || document.activeElement === null) {
        before?.focus?.({ preventScroll: true });
      }
    };
  }, [view.key]);

  return (
    <div
      ref={root}
      className={`call-banner${ended ? " is-ended" : ""}`}
      role="dialog"
      aria-modal="false"
      aria-label={ended ? `${kind} from ${name}` : `Incoming ${video ? "video" : "voice"} call from ${name}`}
      tabIndex={-1}
    >
      {ended ? (
        <div className="call-banner-main">{who}</div>
      ) : (
        <button type="button" className="call-banner-main" onClick={onExpand} title="Show call">
          {who}
        </button>
      )}
      {ended ? null : (
        <div className="call-banner-actions">
          <button
            type="button"
            className="call-banner-btn decline"
            aria-label="Decline"
            title="Decline"
            onClick={declineCall}
          >
            <PhoneOff size={20} />
          </button>
          <button type="button" className="call-banner-btn accept" aria-label="Accept" title="Accept" onClick={acceptCall}>
            {video ? <Video size={20} /> : <Phone size={20} />}
          </button>
        </div>
      )}
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
          {/* Still on camera with the call tucked away: one click stops it. */}
          {view.cameraOn ? (
            <button
              type="button"
              className="call-pill-btn"
              aria-label="Turn video off"
              title="Turn video off"
              onClick={toggleCallCamera}
            >
              <Video size={17} />
            </button>
          ) : null}
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
