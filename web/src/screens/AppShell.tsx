import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Shield,
  MessageCircle,
  Users,
  Settings,
  SquarePen,
  Search,
  Paperclip,
  Image,
  Mic,
  Send,
  Info,
  Plus,
  X,
  ChevronLeft,
} from "lucide-react";
import { api, ApiError, type Conversation, type Session } from "../api/client";
import { initials } from "../config";
import { clearSession } from "../session";

type Tab = "chats" | "contacts" | "settings";

export function AppShell({ session }: { session: Session }) {
  const navigate = useNavigate();
  const [tab, setTab] = useState<Tab>("chats");
  const [query, setQuery] = useState("");
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [selected, setSelected] = useState<Conversation | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [invite, setInvite] = useState("");
  const mobileShowThread = Boolean(selected) && tab === "chats";

  useEffect(() => {
    let cancelled = false;
    api
      .conversations(session.token)
      .then((res) => {
        if (!cancelled) setConversations(res.conversations);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        if (err instanceof ApiError && err.isAuthFailure) {
          clearSession();
          navigate("/", { replace: true });
          return;
        }
        setError(err instanceof ApiError ? err.message : "Could not load chats.");
      });
    return () => {
      cancelled = true;
    };
  }, [session.token, navigate]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return conversations;
    return conversations.filter((c) => c.peer.username.toLowerCase().includes(q));
  }, [conversations, query]);

  async function logout() {
    try {
      await api.logout(session.token);
    } catch {
      /* still wipe locally */
    }
    clearSession();
    navigate("/", { replace: true });
  }

  return (
    <div className="shell">
      <nav className="rail" aria-label="Main">
        <div className="rail-top">
          <div className="rail-mark">
            <Shield size={18} />
          </div>
          <div style={{ height: 12 }} />
          <button
            className={tab === "chats" ? "rail-btn active" : "rail-btn"}
            onClick={() => setTab("chats")}
            aria-label="Chats"
          >
            <MessageCircle size={20} />
          </button>
          <button
            className={tab === "contacts" ? "rail-btn active" : "rail-btn"}
            onClick={() => setTab("contacts")}
            aria-label="Contacts"
          >
            <Users size={20} />
          </button>
          <button
            className={tab === "settings" ? "rail-btn active" : "rail-btn"}
            onClick={() => setTab("settings")}
            aria-label="Settings"
          >
            <Settings size={20} />
          </button>
        </div>
        <div className="rail-me" title={session.user.username}>
          {initials(session.user.username)}
        </div>
      </nav>

      <div className="shell-body">
        {tab !== "settings" ? (
          <section className={`pane-list ${mobileShowThread ? "hidden-mobile" : ""}`}>
            <header className="pane-head">
              <div className="pane-title-row">
                <h1>{tab === "chats" ? "Chats" : "Contacts"}</h1>
                <button className="icon-btn" aria-label="New chat" onClick={() => setAdding(true)}>
                  <SquarePen size={18} />
                </button>
              </div>
              <label className="search">
                <Search size={14} />
                <input
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                  placeholder="Search"
                />
              </label>
            </header>
            <div className="rows">
              {error ? <p className="err" style={{ padding: 12 }}>{error}</p> : null}
              {filtered.length === 0 && !error ? (
                <p className="lede" style={{ padding: 16, textAlign: "left" }}>
                  No chats yet. Add a contact with an invite link or share code.
                </p>
              ) : null}
              {filtered.map((c) => (
                <button
                  key={c.id}
                  className={selected?.id === c.id ? "chat-row active" : "chat-row"}
                  onClick={() => setSelected(c)}
                >
                  <div className="avatar">{initials(c.peer.username)}</div>
                  <div className="row-copy">
                    <strong>{c.peer.username}</strong>
                    <span>Encrypted conversation</span>
                  </div>
                  <div className="row-meta">
                    <time>
                      {c.last_message_at
                        ? new Date(c.last_message_at).toLocaleTimeString([], {
                            hour: "2-digit",
                            minute: "2-digit",
                          })
                        : ""}
                    </time>
                  </div>
                </button>
              ))}
            </div>
          </section>
        ) : (
          <section className="pane-list hidden-mobile">
            <header className="pane-head">
              <div className="pane-title-row">
                <h1>Settings</h1>
              </div>
            </header>
            <div className="rows">
              <div className="chat-row active">
                <div className="row-copy">
                  <strong>Devices</strong>
                  <span>This browser is a linked device</span>
                </div>
              </div>
            </div>
          </section>
        )}

        {tab === "settings" ? (
          <main className="settings-main">
            <h2>Linked devices</h2>
            <p className="lede">
              This browser is a first-class device. Revoke any session you do not recognize.
              Maximum 5.
            </p>
            <div className="card">
              <div className="device-row">
                <div>
                  <strong>This browser · {session.device.name ?? "Web"}</strong>
                  <span className="now">Active now · {session.user.username}</span>
                </div>
              </div>
            </div>
            <div className="warn">
              <h3>Safety numbers</h3>
              <p>
                Open a contact to compare their safety number in person. If a key changes, sending
                is blocked until you verify.
              </p>
            </div>
            <div style={{ marginTop: 24, maxWidth: 560 }}>
              <button className="btn btn-danger" type="button" onClick={logout}>
                Log out
              </button>
            </div>
          </main>
        ) : selected ? (
          <section className="thread">
            <header className="thread-head">
              <div className="peer">
                <button
                  className="icon-btn back-mobile"
                  type="button"
                  aria-label="Back to chats"
                  onClick={() => setSelected(null)}
                >
                  <ChevronLeft size={20} />
                </button>
                <div className="avatar">{initials(selected.peer.username)}</div>
                <div>
                  <strong>{selected.peer.username}</strong>
                  <em>encrypted</em>
                </div>
              </div>
              <button className="icon-btn" aria-label="Contact info">
                <Info size={18} />
              </button>
            </header>
            <div className="messages">
              <div className="empty-thread">
                Message plaintext lives only on your devices. Double Ratchet send/receive for the
                web client is the next slice — this thread will decrypt here the same way as iOS.
              </div>
            </div>
            <form
              className="compose"
              onSubmit={(e) => {
                e.preventDefault();
              }}
            >
              <button className="icon-btn" type="button" aria-label="Attach">
                <Paperclip size={18} />
              </button>
              <button className="icon-btn" type="button" aria-label="Photo">
                <Image size={18} />
              </button>
              <input className="compose-field" placeholder="Message" disabled />
              <button className="icon-btn" type="button" aria-label="Voice">
                <Mic size={18} />
              </button>
              <button className="send" type="submit" aria-label="Send" disabled>
                <Send size={16} />
              </button>
            </form>
          </section>
        ) : (
          <section className="thread hidden-mobile">
            <div className="empty-thread">Select a chat, or add a contact to start one.</div>
          </section>
        )}
      </div>

      <nav className="tab-bar">
        <button className={tab === "chats" ? "tab active" : "tab"} onClick={() => { setTab("chats"); setSelected(null); }}>
          <MessageCircle size={22} />
          Chats
        </button>
        <button className={tab === "contacts" ? "tab active" : "tab"} onClick={() => setTab("contacts")}>
          <Users size={22} />
          Contacts
        </button>
        <button className={tab === "settings" ? "tab active" : "tab"} onClick={() => setTab("settings")}>
          <Settings size={22} />
          Settings
        </button>
      </nav>

      {adding ? (
        <div className="modal-scrim" onClick={() => setAdding(false)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <header>
              <h2>Add contact</h2>
              <button className="icon-btn" type="button" onClick={() => setAdding(false)} aria-label="Close">
                <X size={18} />
              </button>
            </header>
            <p>Paste an invite link or share code. You can also scan their QR with the camera.</p>
            <input
              className="field"
              placeholder="shroud://u/… or share code"
              value={invite}
              onChange={(e) => setInvite(e.target.value)}
            />
            <button className="btn btn-primary" type="button">
              Send request
            </button>
            <button className="btn btn-secondary" type="button">
              <Plus size={16} style={{ marginRight: 8 }} />
              Scan QR with camera
            </button>
          </div>
        </div>
      ) : null}
    </div>
  );
}
