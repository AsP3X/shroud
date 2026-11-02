import { ChevronLeft, MessageCircle, Mic, Paperclip, Search, Settings, ShieldCheck, Users } from "lucide-react";
import { Avatar } from "../Avatar";
import { BrandMark } from "../BrandMark";

/* One account on every device: the same thread on a phone and in this browser, drawn small. */

const MESSAGES: { out?: boolean; text: string; time: string }[] = [
  { text: "Did you get the tickets?", time: "17:41" },
  { out: true, text: "Yes, both. Sent them to your phone too", time: "17:44" },
  { text: "Perfect. Train’s at 18:40, platform 4", time: "17:52" },
  { text: "I’ll be at the kiosk by the entrance", time: "17:52" },
  { out: true, text: "On my way", time: "17:53" },
];

const ROWS = [
  { name: "Mara Lindqvist", seed: "welcome-mara-5", preview: "You: On my way", time: "17:53", active: true, online: true },
  { name: "Jonas Reinholt", seed: "welcome-jonas-18", preview: "Photo", time: "16:05" },
  { name: "Priya Natarajan", seed: "welcome-priya-16", preview: "Can you resend the PDF?", time: "14:38" },
  { name: "Elif Demir", seed: "welcome-elif-11", preview: "ok, see you there", time: "Yest." },
];

function Head({ back = false }: { back?: boolean }) {
  return (
    <div className="lp-mhead">
      {back ? <ChevronLeft size={16} className="lp-back" /> : null}
      <Avatar name="Mara Lindqvist" seed="welcome-mara-5" size="xs" online />
      <div>
        <b>
          Mara Lindqvist <ShieldCheck size={11} />
        </b>
        <span>online</span>
      </div>
    </div>
  );
}

function Thread() {
  return (
    <>
      <div className="lp-mmsgs">
        {MESSAGES.map((message) => (
          <div key={message.text} className={`lp-mbubble ${message.out ? "out" : "in"}`}>
            {message.text}
            <span className="lp-t">{message.time}</span>
          </div>
        ))}
      </div>
      <div className="lp-mcompose">
        <Paperclip size={16} />
        <span className="lp-f">Message</span>
        <Mic size={16} />
      </div>
    </>
  );
}

export function Devices() {
  return (
    <div
      className="lp-devices"
      role="img"
      aria-label="The same conversation open on a phone and in this browser, ending on the same message."
    >
      <div className="lp-phone" aria-hidden="true">
        <div className="lp-screen">
          <Head back />
          <Thread />
        </div>
      </div>
      <div className="lp-browser" aria-hidden="true">
        <div className="lp-brail">
          <BrandMark size={22} />
          <MessageCircle size={18} className="on" />
          <Users size={18} />
          <Settings size={18} />
        </div>
        <div className="lp-blist">
          <div className="lp-btitle">Chats</div>
          <div className="lp-bsearch">
            <Search size={11} />
            Search
          </div>
          {ROWS.map((row) => (
            <div key={row.name} className={`lp-brow${row.active ? " active" : ""}`}>
              <Avatar name={row.name} seed={row.seed} size="xs" online={row.online} />
              <div>
                <b>{row.name}</b>
                <span>{row.preview}</span>
              </div>
              <small>{row.time}</small>
            </div>
          ))}
        </div>
        <div className="lp-bthread">
          <Head />
          <Thread />
        </div>
      </div>
    </div>
  );
}
