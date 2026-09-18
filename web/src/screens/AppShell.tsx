import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { QrCode, Shield } from "lucide-react";
import {
  api,
  ApiError,
  type Contact,
  type ContactRequest,
  type Conversation,
  type Session,
  type WireMessage,
} from "../api/client";
import { Avatar } from "../components/Avatar";
import { ChatList, type ListEntry } from "../components/ChatList";
import { Modal } from "../components/Modal";
import { MyQrSheet } from "../components/qr/MyQrSheet";
import { Rail, TabBar, type Tab } from "../components/Rail";
import { SettingsPane } from "../components/SettingsPane";
import { Thread } from "../components/Thread";
import { listTimestamp, presenceLabel, type Presence } from "../format";
import { loadIdentity } from "../crypto/store";
import { parseInvite, shareUrl } from "../invite";
import {
  fetchLatest,
  hydratePreviews,
  ingestIncoming,
  ensureVoiceLoaded,
  loadHistory,
  peerIdForMessage,
  previewLine,
  sendText,
  sendVoice,
  type ChatMessage,
} from "../messaging";
import { saveMediaBlob } from "../crypto/mediaCache";
import type { VoiceTake } from "../voice/recorder";
import { stopVoice } from "../voice/playback";
import { connectRealtime } from "../realtime";
import { clearSession, setLocked } from "../session";

type PeerRef = { id: string; username: string };

function mergeMessages(primary: ChatMessage[], extra: ChatMessage[]): ChatMessage[] {
  const byId = new Map<string, ChatMessage>();
  for (const m of primary) byId.set(m.id, m);
  for (const m of extra) if (!byId.has(m.id)) byId.set(m.id, m);
  const confirmed = new Set(
    [...byId.values()]
      .filter((m) => m.isMine && !m.pending && !m.failed && m.kind === "text")
      .map((m) => m.text),
  );
  for (const [id, m] of [...byId]) {
    if (m.pending && m.kind === "text" && confirmed.has(m.text)) byId.delete(id);
  }
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
  const [loading, setLoading] = useState(true);
  const [adding, setAdding] = useState(false);
  const [showQr, setShowQr] = useState(false);
  const [showInfo, setShowInfo] = useState(false);
  const [invite, setInvite] = useState("");
  const [addBusy, setAddBusy] = useState(false);
  const [addError, setAddError] = useState<string | null>(null);
  const [thread, setThread] = useState<ChatMessage[]>([]);
  const [threadLoading, setThreadLoading] = useState(false);
  const [threadError, setThreadError] = useState<string | null>(null);
  const [draft, setDraft] = useState("");
  const [sendingPeer, setSendingPeer] = useState<string | null>(null);
  const [presenceByUser, setPresenceByUser] = useState<Record<string, Presence>>({});
  const [previewRev, setPreviewRev] = useState(0);
  const mobileShowThread = Boolean(selected) && tab !== "settings";
  const identity = loadIdentity(session.user.id);
  const alive = useRef(true);
  const selectedRef = useRef(selected);
  selectedRef.current = selected;
  const conversationsRef = useRef(conversations);
  conversationsRef.current = conversations;
  const threadRef = useRef(thread);
  threadRef.current = thread;
  const lastPresenceSweep = useRef(0);
  const draftRef = useRef(draft);
  draftRef.current = draft;

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
    setLoading(false);
    const convs = nextConv ?? conversationsRef.current;
    const rosterIds = [
      ...convs.map((c) => c.peer.id),
      ...(roster.status === "fulfilled" ? roster.value.contacts.map((c) => c.user_id) : []),
    ];
    const uniqueIds = [...new Set(rosterIds.map((id) => id.toLowerCase()))];
    const now = Date.now();
    if (now - lastPresenceSweep.current >= 30_000) {
      lastPresenceSweep.current = now;
      void Promise.all(
        uniqueIds.map(async (id) => {
          try {
            const p = await api.presence(session.token, id);
            return [id, { online: p.online, lastSeenAt: p.last_seen_at ?? null }] as const;
          } catch {
            return null;
          }
        }),
      ).then((rows) => {
        if (!alive.current) return;
        const updates: Record<string, Presence> = {};
        for (const row of rows) if (row) updates[row[0]] = row[1];
        if (Object.keys(updates).length) {
          setPresenceByUser((prev) => ({ ...prev, ...updates }));
        }
      });
    }
    const material = loadIdentity(session.user.id);
    if (material) {
      void hydratePreviews(
        session.token,
        session.user.id,
        convs.map((c) => ({ id: c.peer.id, lastMessageAt: c.last_message_at })),
        material,
      ).then(() => {
        if (alive.current) setPreviewRev((n) => n + 1);
      });
    }
    return convs;
  }, [session.token, session.user.id]);

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
        setLoading(false);
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
    const peerId = selected.id;
    let cancelled = false;
    setThread([]);
    setDraft("");
    setThreadLoading(true);
    setThreadError(null);
    loadHistory(session.token, session.user.id, peerId, material)
      .then((msgs) => {
        if (cancelled) return;
        if (selectedRef.current?.id.toLowerCase() !== peerId.toLowerCase()) return;
        setThread(msgs);
        setPreviewRev((n) => n + 1);
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
  }, [selected?.id, session.token, session.user.id]);

  useEffect(() => {
    const stop = connectRealtime({
      token: session.token,
      onFatalAuth: () => {
        clearSession();
        navigate("/", { replace: true });
      },
      onEvent: (event) => {
        if (event.type === "auth.ok") return;
        if (event.type === "presence.update") {
          const id = String(event.raw.user_id ?? "").toLowerCase();
          if (!id) return;
          setPresenceByUser((prev) => ({
            ...prev,
            [id]: {
              online: Boolean(event.raw.online),
              lastSeenAt: typeof event.raw.last_seen_at === "string" ? event.raw.last_seen_at : null,
            },
          }));
          return;
        }
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
              setPreviewRev((n) => n + 1);
              if (open && open.id.toLowerCase() === peer.toLowerCase()) {
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
      const peerId = open.id;
      fetchLatest(session.token, session.user.id, peerId, material, known)
        .then((extra) => {
          if (cancelled || extra.length === 0) return;
          if (selectedRef.current?.id.toLowerCase() !== peerId.toLowerCase()) return;
          setThread((prev) => mergeMessages(prev, extra));
        })
        .catch(() => {
          /* keep current thread */
        });
    }, 3000);
    return () => {
      cancelled = true;
      window.clearInterval(tick);
    };
  }, [selected?.id, session.token, session.user.id]);

  const chatEntries = useMemo<ListEntry[]>(() => {
    void previewRev;
    const q = query.trim().toLowerCase();
    return conversations
      .map((c) => ({
        id: c.peer.id,
        username: c.peer.username,
        subtitle: previewLine(session.user.id, c.peer.id),
        timestamp: listTimestamp(c.last_message_at),
        online: Boolean(presenceByUser[c.peer.id.toLowerCase()]?.online),
      }))
      .filter(
        (entry) =>
          !q ||
          entry.username.toLowerCase().includes(q) ||
          entry.subtitle.toLowerCase().includes(q),
      );
  }, [conversations, presenceByUser, previewRev, query, session.user.id]);

  const contactEntries = useMemo<ListEntry[]>(() => {
    const q = query.trim().toLowerCase();
    return contacts
      .filter((c) => !q || c.username.toLowerCase().includes(q))
      .map((c) => {
        const presence = presenceByUser[c.user_id.toLowerCase()];
        return {
          id: c.user_id,
          username: c.username,
          subtitle: presenceLabel(presence) || "Contact",
          online: Boolean(presence?.online),
        };
      });
  }, [contacts, presenceByUser, query]);

  const lockNow = useCallback(() => {
    setLocked(true);
    navigate("/unlock", { replace: true });
  }, [navigate]);

  const loadVoice = useCallback(
    (message: ChatMessage) => ensureVoiceLoaded(message, session.token),
    [session.token],
  );

  useEffect(() => () => stopVoice(), []);

  const logout = useCallback(async () => {
    try {
      await api.logout(session.token);
    } catch {
      /* still wipe locally */
    }
    clearSession();
    navigate("/", { replace: true });
  }, [session.token, navigate]);

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

  async function submitMessage() {
    const text = draftRef.current.trim();
    if (!text || !selected || !identity) return;
    draftRef.current = "";
    const peerId = selected.id;
    const localId = `pending:${crypto.randomUUID()}`;
    const optimistic: ChatMessage = {
      id: localId,
      senderUserId: session.user.id,
      text,
      createdAt: new Date().toISOString(),
      isMine: true,
      deleted: false,
      failed: false,
      kind: "text",
      pending: true,
    };
    setThread((prev) => [...prev, optimistic]);
    setDraft("");
    setSendingPeer(peerId);
    setThreadError(null);
    try {
      const msg = await sendText({
        token: session.token,
        me: session.user.id,
        peerUserId: peerId,
        text,
        material: identity,
      });
      if (selectedRef.current?.id.toLowerCase() !== peerId.toLowerCase()) return;
      setThread((prev) => mergeMessages(prev.filter((m) => m.id !== localId), [msg]));
      setPreviewRev((n) => n + 1);
      await refresh();
    } catch (err) {
      if (selectedRef.current?.id.toLowerCase() !== peerId.toLowerCase()) return;
      setThread((prev) =>
        prev.map((m) => (m.id === localId ? { ...m, pending: false, failed: true } : m)),
      );
      setThreadError(err instanceof ApiError ? err.message : "Could not send.");
    } finally {
      setSendingPeer((current) => (current === peerId ? null : current));
    }
  }

  async function submitVoice(take: VoiceTake) {
    if (!selected || !identity) return;
    const peerId = selected.id;
    const localId = `pending:${crypto.randomUUID()}`;
    const optimistic: ChatMessage = {
      id: localId,
      senderUserId: session.user.id,
      text: "Voice message",
      createdAt: new Date().toISOString(),
      isMine: true,
      deleted: false,
      failed: false,
      kind: "voice",
      voiceDurationMs: take.durationMs,
      voiceWaveform: take.waveform,
      mime: take.mime,
      pending: true,
    };
    await saveMediaBlob(localId, take.data);
    setThread((prev) => [...prev, optimistic]);
    setSendingPeer(peerId);
    setThreadError(null);
    try {
      const msg = await sendVoice({
        token: session.token,
        me: session.user.id,
        peerUserId: peerId,
        material: identity,
        take,
      });
      if (selectedRef.current?.id.toLowerCase() !== peerId.toLowerCase()) return;
      setThread((prev) => mergeMessages(prev.filter((m) => m.id !== localId), [msg]));
      setPreviewRev((n) => n + 1);
      await refresh();
    } catch (err) {
      if (selectedRef.current?.id.toLowerCase() !== peerId.toLowerCase()) return;
      setThread((prev) =>
        prev.map((m) => (m.id === localId ? { ...m, pending: false, failed: true } : m)),
      );
      setThreadError(err instanceof ApiError ? err.message : "Could not send the voice message.");
    } finally {
      setSendingPeer((current) => (current === peerId ? null : current));
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

  function openTab(next: Tab) {
    setQuery("");
    if (next === tab && selected) {
      setSelected(null);
      return;
    }
    setTab(next);
  }

  const shareLink = shareUrl(session.user.share_code);
  const selectedPresence = selected ? presenceByUser[selected.id.toLowerCase()] : undefined;
  const requests = incoming.map((request) => ({
    id: request.id,
    username: request.user?.username ?? "Unknown",
  }));

  return (
    <div className="shell">
      <Rail
        tab={tab}
        onSelect={openTab}
        requestCount={requests.length}
        user={{ id: session.user.id, username: session.user.username }}
      />

      <div className="shell-body">
        {tab === "settings" ? (
          <SettingsPane
            session={session}
            identity={identity}
            shareLink={shareLink}
            onLogout={logout}
            onLockNow={lockNow}
            onShowQr={() => setShowQr(true)}
            onCacheCleared={() => setPreviewRev((n) => n + 1)}
          />
        ) : (
          <>
            <ChatList
              className={mobileShowThread ? "hidden-mobile" : undefined}
              title={tab === "chats" ? "Chats" : "Contacts"}
              query={query}
              onQueryChange={setQuery}
              onAdd={() => setAdding(true)}
              addLabel="Add contact"
              onShowQr={tab === "contacts" ? () => setShowQr(true) : undefined}
              loading={loading}
              error={error}
              requests={tab === "contacts" ? requests : []}
              onRespond={respond}
              entries={tab === "chats" ? chatEntries : contactEntries}
              selectedId={selected?.id ?? null}
              onSelect={(entry) => setSelected({ id: entry.id, username: entry.username })}
              empty={
                tab === "chats"
                  ? {
                      title: "No chats yet",
                      body:
                        contacts.length > 0
                          ? "Open a contact to start your first conversation."
                          : "Add someone with their invite link or share code to get started.",
                    }
                  : {
                      title: "No contacts yet",
                      body: "Add someone with their share code and they’ll show up here.",
                    }
              }
            />
            {selected ? (
              <Thread
                key={selected.id}
                peer={selected}
                presence={presenceLabel(selectedPresence)}
                online={Boolean(selectedPresence?.online)}
                messages={thread}
                loading={threadLoading}
                error={threadError}
                canSend={Boolean(identity)}
                sending={sendingPeer?.toLowerCase() === selected.id.toLowerCase()}
                draft={draft}
                onDraftChange={setDraft}
                onSend={() => void submitMessage()}
                onSendVoice={(take) => void submitVoice(take)}
                onBack={() => setSelected(null)}
                onShowInfo={() => setShowInfo(true)}
                onLoadVoice={loadVoice}
              />
            ) : (
              <section className="thread thread-placeholder hidden-mobile">
                <div className="placeholder-card">
                  <Shield size={28} aria-hidden="true" />
                  <strong>Select a conversation</strong>
                  <p>Your messages are end-to-end encrypted, on this device and on theirs.</p>
                </div>
              </section>
            )}
          </>
        )}
      </div>

      <TabBar tab={tab} onSelect={openTab} requestCount={requests.length} />

      {adding ? (
        <Modal
          title="Add contact"
          onClose={() => {
            setAdding(false);
            setAddError(null);
          }}
        >
          <p>Paste an invite link, share code, or username.</p>
          <form
            className="modal-form"
            onSubmit={(event) => {
              event.preventDefault();
              void sendInvite();
            }}
          >
            <input
              className="field"
              placeholder="shroud.corespace.de/u/… or share code"
              aria-label="Invite link, share code, or username"
              value={invite}
              onChange={(event) => setInvite(event.target.value)}
            />
            {addError ? <p className="err">{addError}</p> : null}
            <button className="btn btn-primary" type="submit" disabled={addBusy}>
              {addBusy ? "Sending…" : "Send request"}
            </button>
          </form>
          <button
            type="button"
            className="qr-entry"
            onClick={() => {
              setAdding(false);
              setAddError(null);
              setShowQr(true);
            }}
          >
            <QrCode size={15} aria-hidden="true" />
            Show my QR code instead
          </button>
        </Modal>
      ) : null}

      {showQr ? <MyQrSheet session={session} onClose={() => setShowQr(false)} /> : null}

      {showInfo && selected ? (
        <Modal title="Contact info" onClose={() => setShowInfo(false)}>
          <div className="info-sheet">
            <Avatar
              name={selected.username}
              seed={selected.id}
              size="lg"
              online={Boolean(selectedPresence?.online)}
            />
            <strong>{selected.username}</strong>
            <span className={selectedPresence?.online ? "online" : undefined}>
              {presenceLabel(selectedPresence) || "presence unknown"}
            </span>
          </div>
          <div className="set-card">
            <div className="set-row">
              <div className="set-row-copy">
                <strong>User ID</strong>
                <code>{selected.id}</code>
              </div>
            </div>
            <div className="set-row">
              <div className="set-row-copy">
                <strong>Encryption</strong>
                <span>End-to-end encrypted with X25519 + Double Ratchet.</span>
              </div>
            </div>
          </div>
          <p className="set-note">Safety-number verification is coming to the web client.</p>
        </Modal>
      ) : null}
    </div>
  );
}
