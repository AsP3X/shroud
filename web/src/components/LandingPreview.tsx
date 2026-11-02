import { useLayoutEffect, useRef, useState, type CSSProperties } from "react";
import {
  Check,
  CheckCheck,
  Image,
  Info,
  MessageCircle,
  Mic,
  Paperclip,
  Phone,
  Plus,
  Search,
  Send,
  Settings,
  ShieldCheck,
  Users,
  Video,
} from "lucide-react";
import { Avatar } from "./Avatar";
import { BrandMark } from "./BrandMark";

/* The welcome page's picture of the client: the real rail, chat list and thread, drawn with
   their own classes so a change to the app's look reaches the welcome page unasked. The frame
   is laid out at desktop size and scaled to the room beside the headline. */

export const FRAME_WIDTH = 940;
export const FRAME_HEIGHT = 600;
/** Below this viewport width the page is one column and the frame is drawn at its own size. */
const ONE_COLUMN = 960;
const SMALLEST_SCALE = 0.62;
/** Room left between the frame and the viewport's right edge. */
const EDGE_INSET = 24;

/* The seeds pick distinct colours from the avatar palette; the app itself seeds by user id. */
const ROWS = [
  { name: "Mara Lindqvist", seed: "welcome-mara-5", preview: "I’ll be at the kiosk by the entrance", time: "17:52", online: true, active: true },
  { name: "Jonas Reinholt", seed: "welcome-jonas-18", preview: "Photo", time: "16:05", unread: 1 },
  { name: "Priya Natarajan", seed: "welcome-priya-16", preview: "Can you resend the PDF? The first one didn’t open", time: "14:38", unread: 2 },
  { name: "Tomasz Wierzbicki", seed: "welcome-tomasz-1", preview: "Voice message · 0:42", time: "Yesterday" },
  { name: "Elif Demir", seed: "welcome-elif-11", preview: "ok, see you there", time: "Yesterday" },
  { name: "Søren Kjær", seed: "welcome-soren-6", preview: "Thanks again for yesterday", time: "Mon" },
  { name: "Anneliese Bachmann-Hoffmeister", seed: "welcome-anneliese-16", preview: "You: Sent the invite to your new phone", time: "Sun" },
];

const MESSAGES: { out?: boolean; first?: boolean; text: string; time: string; read?: boolean }[] = [
  { first: true, text: "Did you get the tickets?", time: "17:41" },
  { out: true, first: true, text: "Yes, both. Sent them to your phone too", time: "17:44", read: true },
  { first: true, text: "Perfect. Train’s at 18:40, platform 4", time: "17:52" },
  { text: "I’ll be at the kiosk by the entrance", time: "17:52" },
  { out: true, first: true, text: "On my way", time: "17:53" },
];

/** Scales the frame so it runs from its column to just short of the viewport's edge. */
function useFrameScale() {
  const stage = useRef<HTMLDivElement>(null);
  const [scale, setScale] = useState<number | null>(null);
  useLayoutEffect(() => {
    const el = stage.current;
    if (!el) return;
    const fit = () => {
      if (window.innerWidth <= ONE_COLUMN) {
        setScale(null);
        return;
      }
      const room = window.innerWidth - el.getBoundingClientRect().left - EDGE_INSET;
      setScale(Math.min(1, Math.max(SMALLEST_SCALE, room / FRAME_WIDTH)));
    };
    fit();
    window.addEventListener("resize", fit);
    return () => window.removeEventListener("resize", fit);
  }, []);
  return { stage, scale };
}

export function LandingPreview() {
  const { stage, scale } = useFrameScale();
  const style = (scale === null ? undefined : { "--landing-scale": scale }) as CSSProperties | undefined;
  return (
    <div className="landing-stage" ref={stage} style={style}>
      <div
        className="landing-frame"
        role="img"
        aria-label="The Shroud web client: a chat list beside an open end-to-end encrypted conversation."
      >
        <div aria-hidden="true" className="landing-frame-inner">
          <div className="rail">
            <div className="rail-tabs">
              <span className="rail-mark">
                <BrandMark size={36} />
              </span>
              <span className="rail-btn active">
                <MessageCircle size={20} />
                <span className="rail-badge">3</span>
              </span>
              <span className="rail-btn">
                <Users size={20} />
              </span>
              <span className="rail-btn">
                <Settings size={20} />
              </span>
            </div>
            <span className="rail-me">
              <Avatar name="nv" seed="welcome-me-2" size="sm" />
            </span>
          </div>

          <div className="pane">
            <div className="pane-head">
              <div className="pane-title-row">
                <h1>Chats</h1>
                <div className="pane-actions">
                  <span className="icon-btn">
                    <Plus size={20} />
                  </span>
                </div>
              </div>
              <div className="search">
                <Search size={16} />
                <span>Search chats</span>
              </div>
            </div>
            <div className="rows">
              {ROWS.map((row) => (
                <div key={row.name} className={row.active ? "row row-button active" : "row row-button"}>
                  <Avatar name={row.name} seed={row.seed} online={row.online} />
                  <span className="row-copy">
                    <strong>{row.name}</strong>
                    <span>{row.preview}</span>
                  </span>
                  <span className="row-side">
                    <span className="row-time">{row.time}</span>
                    {row.unread ? (
                      <span className="row-badges">
                        <span className="row-unread">{row.unread}</span>
                      </span>
                    ) : null}
                  </span>
                </div>
              ))}
            </div>
          </div>

          <div className="thread">
            <div className="thread-head">
              <Avatar name="Mara Lindqvist" seed="welcome-mara-5" online />
              <div className="thread-peer">
                <strong>Mara Lindqvist</strong>
                <span className="online">online</span>
              </div>
              <span className="e2e-badge">
                <ShieldCheck size={13} />
                Encrypted
              </span>
              <span className="icon-btn">
                <Search size={20} />
              </span>
              <span className="icon-btn">
                <Phone size={20} />
              </span>
              <span className="icon-btn">
                <Video size={20} />
              </span>
              <span className="icon-btn">
                <Info size={20} />
              </span>
            </div>
            <div className="landing-messages">
              <div className="day-sep">
                <span>Today</span>
              </div>
              {MESSAGES.map((message) => (
                <div
                  key={message.text}
                  className={["bubble", message.out ? "out" : "in", message.first ? "first" : ""].join(" ").trim()}
                >
                  <p className="bubble-text">{message.text}</p>
                  <span className="bubble-meta">
                    {message.time}
                    {message.out ? message.read ? <CheckCheck size={13} /> : <Check size={13} /> : null}
                  </span>
                </div>
              ))}
            </div>
            <div className="compose-shell">
              <div className="compose">
                <span className="icon-btn compose-plus">
                  <Plus size={20} />
                </span>
                <span className="icon-btn compose-wide">
                  <Paperclip size={18} />
                </span>
                <span className="icon-btn compose-wide">
                  <Image size={18} />
                </span>
                <div className="compose-grow landing-compose">
                  <span>Message</span>
                </div>
                <span className="icon-btn compose-mic">
                  <Mic size={18} />
                </span>
                <span className="send landing-send">
                  <Send size={16} />
                </span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
