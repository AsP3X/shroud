import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from "react";
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
  X,
  ChevronLeft,
} from "lucide-react";
import {
  api,
  ApiError,
  type Contact,
  type ContactRequest,
  type Conversation,
  type Session,
  type WireMessage,
} from "../api/client";
import { initials } from "../config";
import { bytesToB64 } from "../crypto/bytes";
import { loadIdentity } from "../crypto/store";
import { parseInvite } from "../invite";
import {
  fetchLatest,
  ingestIncoming,
  loadHistory,
  peerIdForMessage,
  sendText,
  type ChatMessage,
} from "../messaging";
import { connectRealtime } from "../realtime";
import { clearSession } from "../session";

type Tab = "chats" | "contacts" | "settings";
type PeerRef = { id: string; username: string };

function mergeMessages(primary: ChatMessage[], extra: ChatMessage[]): ChatMessage[] {
  const byId = new Map<string, ChatMessage>();
  for (const m of primary) byId.set(m.id, m);
  for (const m of extra) if (!byId.has(m.id)) byId.set(m.id, m);
  return [...byId.values()].sort((a, b) => {
    const t = a.createdAt.localeCompare(b.createdAt);
    return t !== 0 ? t : a.id.localeCompare(b.id);
  });
}

export function AppShell({ session }: { session: Session }) {
  const navigate = useNavigate();
  const [tab, setTab] = useState<Tab>("chats");
  const [query, setQuery] = useState("");
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [contacts, setContacts] = useState<Contact[]>([]);
  const [incoming, setIncoming] = useState<ContactRequest[]>([]);
  const [selected, setSelected] = useState<PeerRef | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [invite, setInvite] = useState("");
  const [addBusy, setAddBusy] = useState(false);
  const [addError, setAddError] = useState<string | null>(null);
  const [thread, setThread] = useState<ChatMessage[]>([]);
  const [threadLoading, setThreadLoading] = useState(false);
  const [threadError, setThreadError] = useState<string | null>(null);
  const [draft, setDraft] = useState("");
  const [sending, setSending] = useState(false);
  const mobileShowThread = Boolean(selected) && tab !== "settings";
  const identity = loadIdentity(session.user.id);
  const alive = useRef(true);
  const selectedRef = useRef(selected);
  selectedRef.current = selected;
  const conversationsRef = useRef(conversations);
  conversationsRef.current = conversations;
  const threadRef = useRef(thread);
  threadRef.current = thread;

  const refresh = useCallback(async (): Promise<Conversation[]> => {
    const [conv, roster, requests] = await Promise.allSettled([
      api.conversations(session.token),
      api.contacts(session.token),
      api.contactRequests(session.token, "incoming"),
    ]);
    if (!alive.current) return conversationsRef.current;
    const authFail = [conv, roster, requests].find(
      (r) => r.status === "rejected" && r.reason instanceof ApiError && r.reason.isAuthFailure,
    );
    if (authFail && authFail.status === "rejected") {
      throw authFail.reason;
    }
    const errors: string[] = [];
    const nextConv = conv.status === "fulfilled" ? conv.value.conversations : null;
    if (nextConv) setConversations(nextConv);
    else errors.push(conv.status === "rejected" && conv.reason instanceof ApiError ? conv.reason.message : "chats");
    if (roster.status === "fulfilled") setContacts(roster.value.contacts);
    else errors.push(roster.status === "rejected" && roster.reason instanceof ApiError ? roster.reason.message : "contacts");
    if (requests.status === "fulfilled") setIncoming(requests.value.requests);
    else errors.push(requests.status === "rejected" && requests.reason instanceof ApiError ? requests.reason.message : "requests");
    setError(errors.length === 3 ? errors[0] : null);
    return nextConv ?? conversationsRef.current;
  }, [session.token]);

  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    const run = () =>
      refresh().catch((err: unknown) => {
        if (cancelled) return;
        if (err instanceof ApiError && err.isAuthFailure) {
          clearSession();
          navigate("/", { replace: true });
          return;
        }
        setError(err instanceof ApiError ? err.message : "Could not load chats and contacts.");
      });
    run();
    const tick = window.setInterval(run, 20_000);
    return () => {
      cancelled = true;
      window.clearInterval(tick);
    };
  }, [refresh, navigate]);

  useEffect(() => {
    const material = loadIdentity(session.user.id);
    if (!selected || !material) {
      setThread([]);
      setThreadError(material ? null : "Unlock this browser with your encryption phrase to read chats.");
      return;
    }
    let cancelled = false;
    setThreadLoading(true);
    setThreadError(null);
    loadHistory(session.token, session.user.id, selected.id, material)
      .then((msgs) => {
        if (!cancelled) {
          setThread((prev) => mergeMessages(msgs, prev));
        }
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setThreadError(err instanceof ApiError ? err.message : "Could not load messages.");
      })
      .finally(() => {
        if (!cancelled) setThreadLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [selected, session.token, session.user.id]);

  useEffect(() => {
    const stop = connectRealtime({
      token: session.token,
      onFatalAuth: () => {
        clearSession();
        navigate("/", { replace: true });
      },
      onEvent: (event) => {
        if (event.type === "auth.ok") return;
        if (event.type === "message.new") {
          const dto = event.raw.message as WireMessage | undefined;
          if (!dto?.id) {
            void refresh();
            return;
          }
          void (async () => {
            try {
              const convs = await refresh();
              const material = loadIdentity(session.user.id);
              if (!material || !alive.current) return;
              const peer = peerIdForMessage(dto, session.user.id, convs);
              const msg = await ingestIncoming(
                dto,
                session.user.id,
                peer,
                session.token,
                material,
              );
              const open = selectedRef.current;
              if (open && open.id.toLowerCase() === peer) {
                setThread((prev) => mergeMessages(prev, [msg]));
              }
            } catch {
              /* roster refresh already ran; next poll/WS event retries */
            }
          })();
          return;
        }
        if (
          event.type.startsWith("contact.") ||
          event.type === "conversation.deleted" ||
          event.type === "message.deleted"
        ) {
          void refresh();
          const open = selectedRef.current;
          if (event.type === "message.deleted" && open) {
            const id = String(event.raw.message_id ?? "");
            if (id) {
              setThread((prev) =>
                prev.map((m) => (m.id === id ? { ...m, text: "Message deleted", deleted: true } : m)),
              );
            }
          }
        }
      },
    });
    return stop;
  }, [session.token, session.user.id, navigate, refresh]);

  useEffect(() => {
    if (!selected) return;
    let cancelled = false;
    const tick = window.setInterval(() => {
      if (cancelled || document.hidden) return;
      const material = loadIdentity(session.user.id);
      const open = selectedRef.current;
      if (!material || !open) return;
      const known = new Set(threadRef.current.map((m) => m.id));
      fetchLatest(session.token, session.user.id, open.id, material, known)
        .then((extra) => {
          if (!cancelled && extra.length) setThread((prev) => mergeMessages(prev, extra));
        })
        .catch(() => {
          /* keep current thread */
        });
    }, 3000);
    return () => {
      cancelled = true;
      window.clearInterval(tick);
    };
  }, [selected, session.token, session.user.id]);

  const filteredChats = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return conversations;
    return conversations.filter((c) => c.peer.username.toLowerCase().includes(q));
  }, [conversations, query]);

  const filteredContacts = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return contacts;
    return contacts.filter((c) => c.username.toLowerCase().includes(q));
  }, [contacts, query]);

  async function logout() {
    try {
      await api.logout(session.token);
    } catch {
      /* still wipe locally */
    }
    clearSession();
    navigate("/", { replace: true });
  }

  async function sendInvite() {
    setAddError(null);
    const parsed = parseInvite(invite);
    if (!parsed) {
      setAddError("Paste a share code, username, or shroud.corespace.de/u/… link.");
      return;
    }
    setAddBusy(true);
    try {
      const user = await api.lookupUser(session.token, parsed);
      if (user.id === session.user.id) {
        setAddError("That’s you.");
        return;
      }
      await api.createContactRequest(session.token, user.id);
      setAdding(false);
      setInvite("");
      await refresh();
    } catch (err) {
      setAddError(err instanceof ApiError ? err.message : "Could not send request.");
    } finally {
      setAddBusy(false);
    }
  }

  async function submitMessage(event: FormEvent) {
    event.preventDefault();
    const text = draft.trim();
    if (!text || !selected || !identity || sending) return;
    setSending(true);
    try {
      const msg = await sendText({
        token: session.token,
        me: session.user.id,
        peerUserId: selected.id,
        text,
        material: identity,
      });
      setThread((prev) => [...prev, msg]);
      setDraft("");
      await refresh();
    } catch (err) {
      setThreadError(err instanceof ApiError ? err.message : "Could not send.");
    } finally {
      setSending(false);
    }
  }

  async function respond(id: string, accept: boolean) {
    try {
      if (accept) await api.acceptRequest(session.token, id);
      else await api.rejectRequest(session.token, id);
      await refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not update request.");
    }
  }

  const shareLink = `${window.location.origin}/u/${session.user.share_code}`;

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
                <button className="icon-btn" aria-label="Add" onClick={() => setAdding(true)}>
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
              {tab === "contacts" && incoming.length > 0 ? (
                <>
                  <p className="list-label">Pending</p>
                  {incoming.map((req) => {
                    const name = req.user?.username ?? "Unknown";
                    return (
                      <div key={req.id} className="chat-row request-row">
                        <div className="avatar">{initials(name)}</div>
                        <div className="row-copy">
                          <strong>{name}</strong>
                          <span>Wants to connect</span>
                        </div>
                        <div className="request-actions">
                          <button type="button" className="mini-btn" onClick={() => respond(req.id, true)}>
                            Accept
                          </button>
                          <button type="button" className="mini-btn ghost" onClick={() => respond(req.id, false)}>
                            Ignore
                          </button>
                        </div>
                      </div>
                    );
                  })}
                </>
              ) : null}
              {tab === "chats"
                ? filteredChats.length === 0 && !error && (
                    <p className="lede" style={{ padding: 16, textAlign: "left" }}>
                      {contacts.length > 0
                        ? "No chats yet. Open a contact to start one."
                        : "No chats yet. Add a contact with an invite link or share code."}
                    </p>
                  )
                : filteredContacts.length === 0 && incoming.length === 0 && !error && (
                    <p className="lede" style={{ padding: 16, textAlign: "left" }}>
                      No contacts yet. Add someone with their share code.
                    </p>
                  )}
              {tab === "chats"
                ? filteredChats.map((c) => (
                    <button
                      key={c.id}
                      className={selected?.id === c.peer.id ? "chat-row active" : "chat-row"}
                      onClick={() => setSelected({ id: c.peer.id, username: c.peer.username })}
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
                  ))
                : filteredContacts.map((c) => (
                    <button
                      key={c.user_id}
                      className={selected?.id === c.user_id ? "chat-row active" : "chat-row"}
                      onClick={() => setSelected({ id: c.user_id, username: c.username })}
                    >
                      <div className="avatar">{initials(c.username)}</div>
                      <div className="row-copy">
                        <strong>{c.username}</strong>
                        <span>Contact</span>
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
              <h3>Your share code</h3>
              <p className="mono-key">{session.user.share_code}</p>
              <p>{shareLink}</p>
            </div>
            {identity ? (
              <div className="warn">
                <h3>This device’s identity key</h3>
                <p className="mono-key">{bytesToB64(identity.agreementPublic)}</p>
                <p>
                  Registration ID {identity.registrationId}. Same X25519 identity as iOS, derived
                  from your 12-word phrase. The phrase itself is never stored here.
                </p>
              </div>
            ) : (
              <div className="warn">
                <h3>No identity keys on this browser</h3>
                <p>Log out and unlock with your 12-word encryption phrase to restore them.</p>
              </div>
            )}
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
                  aria-label="Back"
                  onClick={() => setSelected(null)}
                >
                  <ChevronLeft size={20} />
                </button>
                <div className="avatar">{initials(selected.username)}</div>
                <div>
                  <strong>{selected.username}</strong>
                  <em>encrypted</em>
                </div>
              </div>
              <button className="icon-btn" aria-label="Contact info">
                <Info size={18} />
              </button>
            </header>
            <div className="messages">
              {threadLoading && thread.length === 0 ? (
                <div className="empty-thread">Decrypting history…</div>
              ) : threadError && thread.length === 0 ? (
                <div className="empty-thread">{threadError}</div>
              ) : thread.length === 0 ? (
                <div className="empty-thread">No messages yet. Say hello.</div>
              ) : (
                thread.map((m) => (
                  <div key={m.id} className={m.isMine ? "bubble out" : "bubble in"}>
                    {m.text}
                    <time>
                      {new Date(m.createdAt).toLocaleTimeString([], {
                        hour: "2-digit",
                        minute: "2-digit",
                      })}
                    </time>
                  </div>
                ))
              )}
            </div>
            <form className="compose" onSubmit={submitMessage}>
              <button className="icon-btn" type="button" aria-label="Attach">
                <Paperclip size={18} />
              </button>
              <button className="icon-btn" type="button" aria-label="Photo">
                <Image size={18} />
              </button>
              <input
                className="compose-field"
                placeholder={identity ? "Message" : "Unlock keys to send"}
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
                disabled={!identity || sending}
              />
              <button className="icon-btn" type="button" aria-label="Voice">
                <Mic size={18} />
              </button>
              <button
                className="send"
                type="submit"
                aria-label="Send"
                disabled={!identity || sending || !draft.trim()}
              >
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
        <button
          className={tab === "chats" ? "tab active" : "tab"}
          onClick={() => {
            setTab("chats");
            setSelected(null);
          }}
        >
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
        <div
          className="modal-scrim"
          onClick={() => {
            setAdding(false);
            setAddError(null);
          }}
        >
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <header>
              <h2>Add contact</h2>
              <button
                className="icon-btn"
                type="button"
                onClick={() => setAdding(false)}
                aria-label="Close"
              >
                <X size={18} />
              </button>
            </header>
            <p>Paste an invite link, share code, or username.</p>
            <input
              className="field"
              placeholder="shroud.corespace.de/u/… or share code"
              value={invite}
              onChange={(e) => setInvite(e.target.value)}
            />
            {addError ? <p className="err">{addError}</p> : null}
            <button className="btn btn-primary" type="button" disabled={addBusy} onClick={sendInvite}>
              {addBusy ? "Sending…" : "Send request"}
            </button>
          </div>
        </div>
      ) : null}
    </div>
  );
}
