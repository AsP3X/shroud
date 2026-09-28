import {
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type CSSProperties,
  type MouseEvent as ReactMouseEvent,
  type PointerEvent as ReactPointerEvent,
  type ReactNode,
  type RefObject,
} from "react";
import { createPortal } from "react-dom";
import {
  Expand,
  Maximize2,
  Mic,
  MicOff,
  Minimize2,
  Phone,
  PhoneOff,
  ScreenShare,
  ScreenShareOff,
  ShieldCheck,
  Shrink,
  SwitchCamera,
  Video,
  VideoOff,
  Volume2,
  X,
  ZoomIn,
  ZoomOut,
} from "lucide-react";
import { incomingStaysInBanner, statusLine, videoLayout, type CallView } from "../calls/logic";
import {
  acceptCall,
  acceptChangedCallKey,
  confirmCallSafety,
  declineCall,
  dismissCall,
  hangUpCall,
  resumeCallAudio,
  setCallMinimized,
  switchCallCamera,
  toggleCallCamera,
  toggleCallMute,
  toggleCallScreen,
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
 * One side's picture in a <video>. `shown` turns true only with the first frame presented after
 * the picture is wanted (a camera was switched on), so a camera coming on never shows black, nor
 * the last picture from before it went off (the element is hidden until then). Once not wanted,
 * the element keeps its last frame, so the picture can close or fade rather than blink away.
 * The stream is attached only when it (or `revision`, its tracks) changes: a camera switched back
 * on while the picture is still closing goes on from its last frame, not from black.
 */
function usePicture(stream: MediaStream | null, wanted: boolean, revision: unknown) {
  const ref = useRef<HTMLVideoElement>(null);
  const [shown, setShown] = useState(false);
  useEffect(() => {
    const element = ref.current;
    if (!element) return;
    element.srcObject = null;
    element.srcObject = stream;
    if (stream) void element.play().catch(() => undefined);
  }, [stream, revision]);
  useEffect(() => {
    setShown(false);
    const element = ref.current;
    if (!element || !stream || !wanted) return;
    let current = true;
    const show = () => {
      if (current) setShown(true);
    };
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

/**
 * How their picture comes and goes: it opens out of their face as a growing circle, and closes
 * back into it. "opening" and "closing" are the circle on its way; at rest there is no clip.
 */
type RevealStage = "face" | "opening" | "picture" | "closing";

/* Both ways the circle moves from the first frame and slows evenly: into the corners when
   opening, onto the face when closing. The face's own fade in index.css (`call-face-open`,
   `call-face-close`) is timed against these; iOS uses the same (CallVideoContainer). */
const OPEN_MS = 420;
const CLOSE_MS = 380;
const EASING = "cubic-bezier(0.33, 1, 0.68, 1)";

function reduceMotion(): boolean {
  return window.matchMedia?.("(prefers-reduced-motion: reduce)").matches === true;
}

/**
 * The face's circle in the picture's coordinates, from layout offsets: neither the face's own
 * swell nor the name's rise animation may move the centre. No face on screen (our picture fills
 * it while the call is placed): the middle of the screen, from a point.
 */
function faceCircle(picture: HTMLElement, face: HTMLElement | null) {
  const width = picture.offsetWidth;
  const height = picture.offsetHeight;
  let x = width / 2;
  let y = height / 2;
  let r = 0;
  const root = picture.offsetParent;
  if (face && face.offsetWidth > 0 && root) {
    let cx = face.offsetWidth / 2;
    let cy = face.offsetHeight / 2;
    let node: Element | null = face;
    while (node instanceof HTMLElement && node !== root) {
      cx += node.offsetLeft;
      cy += node.offsetTop;
      node = node.offsetParent;
    }
    if (node === root) {
      x = cx - picture.offsetLeft;
      y = cy - picture.offsetTop;
      // A hair inside the face's edge, so no picture shows around it once it is back.
      r = (face.offsetWidth / 2) * 0.96;
    }
  }
  const full = Math.hypot(Math.max(x, width - x), Math.max(y, height - y)) + 2;
  return { x, y, r, full, onFace: r > 0 };
}

function circle(r: number, x: number, y: number): string {
  return `circle(${r.toFixed(1)}px at ${x.toFixed(1)}px ${y.toFixed(1)}px)`;
}

/** The circle's radius right now (an animation may be halfway); null when nothing clips. */
function clipRadius(picture: HTMLElement): number | null {
  const match = /circle\(\s*([\d.]+)px/.exec(getComputedStyle(picture).clipPath);
  return match ? Number(match[1]) : null;
}

/**
 * Runs the circle on their picture when `open` changes. Opening starts from the face as it is
 * on screen; closing first brings the face back (the caller shows it for "closing") and then
 * shrinks onto it. Turned around halfway, the circle goes back from where it is. `onFace` says
 * whether the opening came out of the face (else from the middle, the face not being shown).
 */
function useFaceReveal(
  picture: RefObject<HTMLVideoElement | null>,
  face: RefObject<HTMLDivElement | null>,
  open: boolean,
): { stage: RevealStage; onFace: boolean } {
  const [stage, setStage] = useState<RevealStage>(open ? "picture" : "face");
  const [onFace, setOnFace] = useState(true);
  const running = useRef<Animation | null>(null);
  // Bumped whenever a circle is superseded, so a finish from the one before cannot
  // put the picture back up after the camera has already turned off.
  const generation = useRef(0);
  const openRef = useRef(open);
  openRef.current = open;

  const run = (element: HTMLVideoElement, from: number, to: number, x: number, y: number, opening: boolean) => {
    running.current?.cancel();
    const id = ++generation.current;
    const animation = element.animate([{ clipPath: circle(from, x, y) }, { clipPath: circle(to, x, y) }], {
      duration: opening ? OPEN_MS : CLOSE_MS,
      easing: EASING,
      fill: "forwards",
    });
    running.current = animation;
    animation.onfinish = () => {
      if (generation.current !== id || openRef.current !== opening) return;
      setStage(opening ? "picture" : "face");
    };
  };

  // Their picture came or went.
  useLayoutEffect(() => {
    const element = picture.current;
    if (!element) return;
    if (open && (stage === "face" || stage === "closing")) {
      const at = faceCircle(element, face.current);
      setOnFace(at.onFace);
      if (reduceMotion()) {
        generation.current += 1;
        running.current?.cancel();
        running.current = null;
        setStage("picture");
        return;
      }
      const from = stage === "closing" ? (clipRadius(element) ?? at.r) : at.r;
      setStage("opening");
      run(element, from, at.full, at.x, at.y, true);
    } else if (!open && (stage === "picture" || stage === "opening")) {
      // Drop the opening's finish before the close starts. Otherwise that finish
      // can land in between and show the whole picture after the camera is off.
      generation.current += 1;
      if (running.current) running.current.onfinish = null;
      if (reduceMotion()) {
        running.current?.cancel();
        running.current = null;
        setStage("face");
        return;
      }
      setStage("closing");
    }
    // Only a change of `open` starts anything; `stage` is read as it stands then.
  }, [open]);

  useLayoutEffect(() => {
    const element = picture.current;
    if (!element) return;
    if (stage === "closing") {
      // The face is back in the layout now: that is where the circle goes.
      const at = faceCircle(element, face.current);
      run(element, clipRadius(element) ?? at.full, at.r, at.x, at.y, false);
    } else if (stage === "picture" || stage === "face") {
      // At rest nothing clips: all of the picture shows, or none (hidden).
      running.current?.cancel();
      running.current = null;
    }
  }, [stage]);

  useEffect(() => () => running.current?.cancel(), []);
  return { stage, onFace };
}

/** How long the call's controls stay up over a shared screen once the pointer and keys are still. */
const IDLE_MS = 3_000;

/**
 * True once nothing has moved in the call for `IDLE_MS` while `active` (their screen is up): the
 * controls step aside so all of it shows. Any pointer movement, touch, key or focus brings them
 * back, and so does a new `wake` (a notice to read). They stay up while the pointer rests on one
 * of them or the keyboard is in them. Going idle takes focus off a button the mouse left it on,
 * so the key that wakes the controls cannot press a button nobody sees.
 */
function useIdle(root: RefObject<HTMLElement | null>, active: boolean, wake: unknown): boolean {
  const [idle, setIdle] = useState(false);
  useEffect(() => {
    const element = root.current;
    setIdle(false);
    if (!active || !element) return;
    let timer = 0;
    const busy = () => {
      if (element.querySelector(".call-bar:hover, .call-top-actions:hover, .call-e2e:hover, .call-who:hover")) return true;
      const focused = document.activeElement;
      return focused instanceof HTMLElement && focused !== element && element.contains(focused) && focused.matches(":focus-visible");
    };
    const arm = () => {
      window.clearTimeout(timer);
      timer = window.setTimeout(function rest() {
        if (busy()) {
          timer = window.setTimeout(rest, IDLE_MS);
          return;
        }
        const focused = document.activeElement;
        if (focused instanceof HTMLElement && focused !== element && element.contains(focused)) {
          element.focus({ preventScroll: true });
        }
        setIdle(true);
      }, IDLE_MS);
    };
    const wake = () => {
      setIdle(false);
      arm();
    };
    const events = ["pointermove", "pointerdown", "keydown", "focusin", "wheel"] as const;
    for (const name of events) element.addEventListener(name, wake, { passive: true });
    arm();
    return () => {
      window.clearTimeout(timer);
      for (const name of events) element.removeEventListener(name, wake);
    };
  }, [root, active, wake]);
  return idle && active;
}

/**
 * Their shared screen. All of it shows, letterboxed on black ("fit"), or at full size (a pixel of
 * theirs to a point of ours) to read small text, panned by dragging, scrolling, swiping or the
 * arrow keys. Double-clicking switches
 * between the two, keeping the point clicked under the pointer.
 */
function SharedScreen({
  picture,
  shown,
  actual,
  onZoom,
}: {
  picture: RefObject<HTMLVideoElement | null>;
  shown: boolean;
  actual: boolean;
  onZoom: () => void;
}) {
  const box = useRef<HTMLDivElement>(null);
  /** Where a double-click landed, as a fraction of the picture, and where in the box. */
  const aim = useRef<{ fx: number; fy: number; x: number; y: number } | null>(null);
  const drag = useRef<{ id: number; x: number; y: number; left: number; top: number } | null>(null);

  useLayoutEffect(() => {
    const element = box.current;
    const at = aim.current;
    aim.current = null;
    if (!element || !actual) return;
    if (at) {
      element.scrollLeft = at.fx * element.scrollWidth - at.x;
      element.scrollTop = at.fy * element.scrollHeight - at.y;
    } else {
      element.scrollLeft = (element.scrollWidth - element.clientWidth) / 2;
      element.scrollTop = (element.scrollHeight - element.clientHeight) / 2;
    }
  }, [actual]);

  const onDoubleClick = (event: ReactMouseEvent<HTMLDivElement>) => {
    const element = box.current;
    const video = picture.current;
    if (!element || !video) return;
    const rect = element.getBoundingClientRect();
    const x = event.clientX - rect.left;
    const y = event.clientY - rect.top;
    if (!actual && video.videoWidth > 0 && video.videoHeight > 0) {
      // The picture as it is letterboxed now: where in it the click fell.
      const scale = Math.min(rect.width / video.videoWidth, rect.height / video.videoHeight);
      const width = video.videoWidth * scale;
      const height = video.videoHeight * scale;
      const clamp = (value: number) => Math.min(1, Math.max(0, value));
      aim.current = {
        fx: clamp((x - (rect.width - width) / 2) / width),
        fy: clamp((y - (rect.height - height) / 2) / height),
        x,
        y,
      };
    }
    onZoom();
  };

  /* A mouse drags the picture around; touch scrolls it natively. */
  const onPointerDown = (event: ReactPointerEvent<HTMLDivElement>) => {
    const element = box.current;
    if (!actual || !element || event.pointerType === "touch" || event.button !== 0) return;
    element.setPointerCapture(event.pointerId);
    drag.current = { id: event.pointerId, x: event.clientX, y: event.clientY, left: element.scrollLeft, top: element.scrollTop };
  };
  const onPointerMove = (event: ReactPointerEvent<HTMLDivElement>) => {
    const element = box.current;
    const from = drag.current;
    if (!element || !from || from.id !== event.pointerId) return;
    element.scrollLeft = from.left - (event.clientX - from.x);
    element.scrollTop = from.top - (event.clientY - from.y);
  };
  const onPointerUp = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (drag.current?.id === event.pointerId) drag.current = null;
  };

  return (
    <div
      ref={box}
      className={`call-screen${shown ? " is-shown" : ""}${actual ? " is-actual" : ""}`}
      // At full size the arrow keys pan it.
      tabIndex={actual ? 0 : undefined}
      aria-label={actual ? "Their screen at full size" : undefined}
      onDoubleClick={onDoubleClick}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={onPointerUp}
    >
      <video ref={picture} autoPlay playsInline muted aria-hidden="true" />
    </div>
  );
}

/** Follows the browser's fullscreen, and switches the call screen in and out of it. */
function useFullscreen(root: RefObject<HTMLElement | null>): { on: boolean; toggle: (() => void) | null } {
  const [on, setOn] = useState(false);
  useEffect(() => {
    const update = () => setOn(document.fullscreenElement !== null && document.fullscreenElement === root.current);
    update();
    document.addEventListener("fullscreenchange", update);
    return () => document.removeEventListener("fullscreenchange", update);
  }, [root]);
  if (!document.fullscreenEnabled) return { on: false, toggle: null };
  return {
    on,
    toggle: () => {
      if (document.fullscreenElement) void document.exitFullscreen().catch(() => undefined);
      else void root.current?.requestFullscreen().catch(() => undefined);
    },
  };
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
  unavailable = false,
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
  /** Looks off but still takes the press, which says why it cannot be used (a notice). */
  unavailable?: boolean;
}) {
  return (
    <button
      type="button"
      className={`call-ctl call-ctl-${tone}${on ? " is-on" : ""}${pending ? " is-pending" : ""}${unavailable ? " is-unavailable" : ""}`}
      onClick={onClick}
      disabled={disabled}
      aria-disabled={unavailable || undefined}
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
  /* Their shared screen fills the stage once its first frame is in; their camera then moves into
     a tile beside ours. Their screen goes first: over it, the camera would cover what they show. */
  const screen = usePicture(
    view.remoteScreenStream,
    live && view.remoteScreen && view.remoteScreenStream !== null,
    view.remoteScreenStream?.getVideoTracks().length ?? 0,
  );
  const screenUp = screen.shown;
  /* Voice or video is whatever the cameras say right now: either side can switch its own on or
     off mid-call, and the screen follows, back to the face when both are off. */
  const theirCamera = live && view.remoteVideo && view.remoteCamera && view.remoteStream !== null;
  const remote = usePicture(view.remoteStream, theirCamera && !screenUp, view.remoteVideo);
  const peerTile = usePicture(view.remoteStream, theirCamera && screenUp, view.remoteVideo);
  const self = usePicture(view.localStream, live && view.cameraOn && view.localStream !== null, null);
  const ownScreen = usePicture(view.screenStream, live && view.screenOn && view.screenStream !== null, null);
  const layout = videoLayout(view, self.shown, remote.shown);
  /* Their picture opens out of their face as a growing circle, and closes back into it. The name
     moves up into the pill only once the circle has opened (it came from the face), and comes
     back under the face as the circle starts closing onto it. */
  const face = useRef<HTMLDivElement>(null);
  const reveal = useFaceReveal(remote.ref, face, layout === "theirs");
  const theirVideo = reveal.stage === "picture" || (reveal.stage === "opening" && !reveal.onFace);
  /* While the call is placed, our picture fills the screen (as FaceTime does); after that it sits
     in the corner, over their picture or their face. */
  const selfFull = layout === "mine";
  const overPicture = theirVideo || selfFull || screenUp;
  /* Over their screen: fit or pixel for pixel, fullscreen, and the controls out of the way. */
  const [actual, setActual] = useState(false);
  useEffect(() => {
    if (!screenUp) setActual(false);
  }, [screenUp]);
  /* The controls step aside only while nothing of ours needs seeing: not while their sound waits
     for a click, and a notice wakes them. Our own "sharing" pill stays through it. */
  const idle = useIdle(root, screenUp && !view.audioBlocked, view.notice);
  const fullscreen = useFullscreen(root);
  /* Fullscreen was for their screen: it ends with it. */
  const leaveFullscreen = !screenUp && fullscreen.on;
  useEffect(() => {
    if (leaveFullscreen) void document.exitFullscreen().catch(() => undefined);
  }, [leaveFullscreen]);
  /* The tiles down the right side, top to bottom: their camera beside their screen, our camera,
     our own screen while it goes out. Each knows its place, so the others close up around it. */
  let slots = 0;
  const peerSlot = peerTile.shown ? slots++ : 0;
  const selfSlot = self.shown && !selfFull ? slots++ : 0;
  const ownScreenSlot = ownScreen.shown ? slots++ : 0;
  const slot = (index: number) => ({ "--slot": index }) as CSSProperties;
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
  if (live && screenUp) {
    chips.push(
      <span className="call-chip" key="their-screen">
        <ScreenShare size={13} aria-hidden="true" />
        {name}’s screen
      </span>,
    );
  }
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
    remote.shown || self.shown || screenUp ? "is-video" : "is-voice",
    theirVideo ? "has-video" : "",
    screenUp ? "has-screen" : "",
    selfFull ? "self-full" : "",
    ringing ? "is-ringing" : "",
    idle ? "is-idle" : "",
    view.reconnecting && view.phase === "active" ? "is-reconnecting" : "",
  ]
    .filter(Boolean)
    .join(" ");
  const videoLabel = view.cameraOn ? "Turn video off" : "Turn video on";
  const shareLabel = view.screenOn ? "Stop sharing your screen" : "Share your screen";
  /* Why Share cannot be used yet: said on hover, and in a notice when it is pressed anyway. */
  const shareBlocked = !view.canShare && !view.screenOn;
  const shareWhy = view.phase === "active" ? "Screen sharing isn’t available in this call" : "You can share once the call has connected";

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
      {/* Both pictures stay in place for the whole call. Theirs opens out of their face as a circle
          when their camera comes on and closes back into it (useFaceReveal); ours grows out of
          the corner. */}
      <video
        ref={remote.ref}
        className={`call-remote${reveal.stage !== "face" ? " is-shown" : ""}`}
        autoPlay
        playsInline
        muted
        aria-hidden="true"
      />
      <SharedScreen picture={screen.ref} shown={screenUp} actual={actual} onZoom={() => setActual((on) => !on)} />
      <div className={`call-tile call-peer-tile${peerTile.shown ? " is-shown" : ""}`} style={slot(peerSlot)} aria-hidden="true">
        <video ref={peerTile.ref} autoPlay playsInline muted />
      </div>
      <div
        className={`call-self${selfFull ? " full" : ""}${self.shown ? " is-shown" : ""}`}
        style={selfFull ? undefined : slot(selfSlot)}
        aria-hidden="true"
      >
        <video ref={self.ref} className={view.mirrorSelf ? "mirrored" : undefined} autoPlay playsInline muted />
      </div>
      <div className={`call-tile call-own-screen${ownScreen.shown ? " is-shown" : ""}`} style={slot(ownScreenSlot)} aria-hidden="true">
        <video ref={ownScreen.ref} autoPlay playsInline muted />
        <span className="call-tile-label">Your screen</span>
      </div>
      <div className="call-shade call-shade-top" aria-hidden="true" />
      <div className="call-shade call-shade-bottom" aria-hidden="true" />

      <header className="call-top">
        <div className="call-top-start">
          {/* We share a screen: in the top-left corner for as long as it lasts, through idle too,
              with Stop right there. What we share, they see. */}
          {live && view.screenOn ? (
            <div className="call-sharing" role="status">
              <span className="call-sharing-dot" aria-hidden="true" />
              <span className="call-sharing-label">Sharing screen</span>
              {view.screenSound ? (
                <Volume2 size={13} className="call-sharing-sound" aria-label="with its sound" />
              ) : null}
              <button
                type="button"
                className="call-sharing-stop"
                aria-label="Stop sharing your screen"
                title="Stop sharing your screen"
                onClick={toggleCallScreen}
              >
                Stop
              </button>
            </div>
          ) : null}
          <span className="call-e2e">
            <ShieldCheck size={13} aria-hidden="true" />
            End-to-end encrypted
          </span>
        </div>
        <div className="call-top-actions">
          {live && screenUp ? (
            <button
              type="button"
              className={`call-icon-btn${actual ? " is-on" : ""}`}
              aria-label={actual ? "Fit their screen to the window" : "Show their screen at actual size"}
              aria-pressed={actual}
              title={actual ? "Fit to window (double-click)" : "Actual size (double-click)"}
              onClick={() => setActual((on) => !on)}
            >
              {actual ? <ZoomOut size={18} /> : <ZoomIn size={18} />}
            </button>
          ) : null}
          {fullscreen.toggle && (fullscreen.on || (live && screenUp)) ? (
            <button
              type="button"
              className="call-icon-btn"
              aria-label={fullscreen.on ? "Exit full screen" : "Full screen"}
              title={fullscreen.on ? "Exit full screen" : "Full screen"}
              onClick={fullscreen.toggle}
            >
              {fullscreen.on ? <Shrink size={18} /> : <Expand size={18} />}
            </button>
          ) : null}
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
        </div>
      </header>

      {/* The person: the avatar at the exact centre of the screen, name and clock hanging below it
          (in a pill at the top instead once a picture fills the screen). */}
      <section className="call-stage">
        {/* A new key when the picture takes over the screen or gives it back: the name rises into
            its new place (the pill at the top, or under the face) instead of jumping there. */}
        <div
          className={`call-who${reveal.stage === "opening" && reveal.onFace ? " is-leaving" : ""}`}
          key={overPicture ? "over-picture" : "under-face"}
        >
          <div
            ref={face}
            className={[
              "call-avatar",
              view.phase === "incoming" ? "ringing" : "",
              reveal.stage === "opening" && reveal.onFace ? "is-opening" : "",
              reveal.stage === "closing" ? "is-closing" : "",
            ]
              .filter(Boolean)
              .join(" ")}
          >
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
            {view.keyChanged ? (
              <button type="button" className="call-safety" onClick={acceptChangedCallKey}>
                Trust new key
              </button>
            ) : view.safety && !view.safety.verified && view.phase !== "ended" ? (
              <button type="button" className="call-safety" onClick={confirmCallSafety}>
                <span>Safety number not compared</span>
                <span className="call-safety-num">{view.safety.number}</span>
              </button>
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
            {/* Next to the camera, never instead of it. A browser without a screen picker (a
                phone's) has no button: it can still see theirs. */}
            {view.shareSupported ? (
              <Control
                label="Share"
                ariaLabel={shareBlocked ? `${shareLabel}. ${shareWhy}` : shareLabel}
                title={shareBlocked ? shareWhy : shareLabel}
                on={view.screenOn}
                pending={view.screenPending}
                unavailable={shareBlocked}
                iconKey={view.screenOn ? "share-on" : "share"}
                icon={view.screenOn ? <ScreenShareOff size={24} /> : <ScreenShare size={24} />}
                onClick={toggleCallScreen}
              />
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
          {/* Still sharing with the call tucked away: one click stops it. */}
          {view.screenOn ? (
            <button
              type="button"
              className="call-pill-btn is-sharing"
              aria-label="Stop sharing your screen"
              title="Stop sharing your screen"
              onClick={toggleCallScreen}
            >
              <ScreenShareOff size={17} />
            </button>
          ) : null}
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
