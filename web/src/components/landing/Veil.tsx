import {
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent,
  type PointerEvent,
} from "react";
import { Check, CheckCheck, ChevronsLeftRight, Mic, Paperclip, Phone, Play, ShieldCheck, Video } from "lucide-react";
import { Avatar } from "../Avatar";

/*
 * The welcome page's hero: a chat as you see it, with a veil over its right part. Under the
 * veil is the same chat as the server holds it: every message keeps its exact shape, with only
 * a lock, the envelope's size and its time inside. The visitor drags the veil.
 *
 * The server's view is a copy of the visitor's, built from the rendered DOM, so the envelopes
 * always match whatever the thread shows.
 */

/** Where the veil comes to rest, as a percentage of the stage's width. */
const REST = 54;

type Message = { out?: boolean; text?: string; voice?: string; time: string; size: string };

const MESSAGES: Message[] = [
  { text: "Did you get the tickets?", time: "17:41", size: "0.3 KB" },
  { out: true, text: "Yes, both. Sent them to your phone too", time: "17:44", size: "0.4 KB" },
  { voice: "0:42", time: "17:52", size: "41 KB" },
  { text: "Perfect. Train’s at 18:40, platform 4", time: "17:52", size: "0.3 KB" },
  { text: "I’ll be at the kiosk by the entrance", time: "17:52", size: "0.3 KB" },
  { out: true, text: "On my way", time: "17:53", size: "0.2 KB" },
];

export const WAVE = [6, 12, 18, 10, 16, 20, 8, 14, 18, 9, 15, 7, 12, 19, 11, 6, 14, 9, 17, 8, 13, 5];

const LOCK =
  '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect width="18" height="11" x="3" y="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>';

/** The waveform of a voice note, as the app draws it. */
export function Wave({ bars = WAVE, className = "lp-bars" }: { bars?: readonly number[]; className?: string }) {
  return (
    <span className={className} aria-hidden="true">
      {bars.map((height, index) => (
        <b key={index} style={{ height }} />
      ))}
    </span>
  );
}

export function Veil() {
  const veil = useRef<HTMLDivElement>(null);
  const stage = useRef<HTMLDivElement>(null);
  const you = useRef<HTMLDivElement>(null);
  const edge = useRef<HTMLDivElement>(null);
  const [cut, setCutState] = useState(100);
  const cutRef = useRef(100);
  const dragging = useRef(false);

  const setCut = (value: number) => {
    const next = Math.min(100, Math.max(0, value));
    cutRef.current = next;
    setCutState(next);
  };

  /* The server's copy of the picture. Rebuilt when the window or the fonts change the
     bubbles' sizes, since each envelope is sized to its message. */
  useLayoutEffect(() => {
    const box = stage.current;
    const source = you.current;
    const line = edge.current;
    if (!box || !source || !line) return;
    let copy: HTMLElement | null = null;
    let gone = false;
    const build = () => {
      if (gone) return;
      copy?.remove();
      copy = source.cloneNode(true) as HTMLElement;
      copy.classList.replace("you", "server");
      copy.setAttribute("aria-hidden", "true");
      const head = copy.querySelector(".lp-head");
      head?.querySelector(".avatar")?.replaceChildren();
      const name = head?.querySelector(".lp-peer strong");
      if (name) name.textContent = "Account 7c2e91f4";
      const sub = head?.querySelector(".lp-peer span");
      if (sub) sub.textContent = "2 devices";
      head?.querySelector(".lp-acts")?.remove();
      const originals = source.querySelectorAll<HTMLElement>(".lp-bubble");
      copy.querySelectorAll<HTMLElement>(".lp-bubble").forEach((bubble, index) => {
        const original = originals[index];
        bubble.style.width = `${original.offsetWidth}px`;
        bubble.style.height = `${original.offsetHeight}px`;
        bubble.style.maxWidth = "none";
        bubble.classList.add("lp-sealed");
        bubble.innerHTML = `<span class="lp-what">${LOCK}${original.dataset.size ?? ""}</span><span class="lp-meta">${original.dataset.time ?? ""}</span>`;
      });
      const compose = copy.querySelector(".lp-compose");
      if (compose) {
        const kept = document.createElement("div");
        kept.className = "lp-kept";
        kept.textContent = "Delivered, then forgotten.";
        compose.replaceWith(kept);
      }
      box.insertBefore(copy, line);
    };
    build();
    window.addEventListener("resize", build);
    if (document.fonts?.ready) document.fonts.ready.then(build).catch(() => undefined);
    return () => {
      gone = true;
      window.removeEventListener("resize", build);
      copy?.remove();
    };
  }, []);

  /* Draw the veil over the chat once, when the page is on screen. Without motion, or in a
     tab the browser is not showing, it is simply there. */
  useEffect(() => {
    const el = veil.current;
    if (!el) return;
    const still = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (still || document.visibilityState !== "visible") {
      setCut(REST);
      return;
    }
    el.classList.add("drop");
    let second = 0;
    const first = requestAnimationFrame(() => {
      second = requestAnimationFrame(() => setCut(REST));
    });
    const settle = setTimeout(() => el.classList.remove("drop"), 1800);
    return () => {
      cancelAnimationFrame(first);
      cancelAnimationFrame(second);
      clearTimeout(settle);
    };
  }, []);

  const cutFrom = (clientX: number) => {
    const rect = stage.current?.getBoundingClientRect();
    if (!rect || rect.width === 0) return cutRef.current;
    return ((clientX - rect.left) / rect.width) * 100;
  };
  const onPointerDown = (event: PointerEvent<HTMLDivElement>) => {
    dragging.current = true;
    veil.current?.classList.remove("drop");
    stage.current?.setPointerCapture(event.pointerId);
    setCut(cutFrom(event.clientX));
  };
  const onPointerMove = (event: PointerEvent<HTMLDivElement>) => {
    if (dragging.current) setCut(cutFrom(event.clientX));
  };
  const onPointerEnd = () => {
    dragging.current = false;
  };
  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    const step = event.shiftKey ? 10 : 4;
    const current = cutRef.current;
    if (event.key === "ArrowLeft") setCut(current - step);
    else if (event.key === "ArrowRight") setCut(current + step);
    else if (event.key === "Home") setCut(0);
    else if (event.key === "End") setCut(100);
    else return;
    event.preventDefault();
  };

  return (
    <div className="lp-veil" ref={veil} style={{ "--cut": `${cut}%` } as CSSProperties}>
      <div className="lp-veil-labels" aria-hidden="true">
        <span>On your devices</span>
        <span>On the server</span>
      </div>
      <div
        className="lp-stage"
        ref={stage}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerEnd}
        onPointerCancel={onPointerEnd}
      >
        <div
          className="lp-view you"
          ref={you}
          role="img"
          aria-label="A chat with Mara Lindqvist. Behind the veil, the same chat as the server holds it: sealed envelopes with only a size and a time."
        >
          <div className="lp-head">
            <Avatar name="Mara Lindqvist" seed="welcome-mara-5" online />
            <div className="lp-peer">
              <strong>
                <span>Mara Lindqvist</span>
                <ShieldCheck size={14} aria-hidden="true" />
              </strong>
              <span>online</span>
            </div>
            <div className="lp-acts" aria-hidden="true">
              <span>
                <Phone size={20} />
              </span>
              <span>
                <Video size={20} />
              </span>
            </div>
          </div>
          <div className="lp-msgs">
            <span className="lp-day">Today</span>
            {MESSAGES.map((message) => (
              <div
                key={message.time + (message.text ?? message.voice)}
                className={`lp-bubble ${message.out ? "out" : "in"}`}
                data-size={message.size}
                data-time={message.time}
              >
                {message.voice ? (
                  <span className="lp-voice">
                    <span className="lp-play">
                      <Play size={13} fill="currentColor" />
                    </span>
                    <Wave />
                    <span className="lp-len">{message.voice}</span>
                  </span>
                ) : (
                  message.text
                )}
                <span className="lp-meta">
                  {message.time}
                  {message.out ? message.time === "17:44" ? <CheckCheck size={13} /> : <Check size={13} /> : null}
                </span>
              </div>
            ))}
          </div>
          <div className="lp-compose" aria-hidden="true">
            <span className="lp-ib">
              <Paperclip size={20} />
            </span>
            <span className="lp-field">Message</span>
            <span className="lp-ib">
              <Mic size={20} />
            </span>
          </div>
        </div>
        <div className="lp-edge" ref={edge} aria-hidden="false">
          <button
            className="lp-grip"
            type="button"
            role="slider"
            aria-label="How much of the chat the server sees"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={Math.round(100 - cut)}
            onKeyDown={onKeyDown}
          >
            <ChevronsLeftRight size={18} aria-hidden="true" />
          </button>
        </div>
      </div>
    </div>
  );
}
