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
import { TypingLabel } from "../components/Typing";
import { DeviceWipeDialog, type WipeReason } from "../components/DeviceWipeDialog";
import { LogoutDialog } from "../components/LogoutDialog";
import { Modal } from "../components/Modal";
import { ProfileSheet } from "../components/ProfileSheet";
import { MyQrSheet } from "../components/qr/MyQrSheet";
import { Rail, TabBar, type Tab } from "../components/Rail";
import { SettingsPane } from "../components/SettingsPane";
import { Thread } from "../components/Thread";
import { listTimestamp, presenceLabel, type Presence } from "../format";
import { loadIdentity } from "../crypto/store";
import { parseInvite, shareUrl } from "../invite";
import {
  applyAnnotations,
  fetchLatest,
  hydratePreviews,
  ingestIncoming,
  ensureVoiceLoaded,
  loadHistory,
  peerIdForMessage,
  forgetMessageLocally,
  isUnsent,
  previewLine,
  replyRefFor,
  rewritePreview,
  tombstone,
  sendImage,
  sendText,
  sendVideo,
  sendVoice,
  shareTranscript,
  type ChatMessage,
} from "../messaging";
import { saveMediaBlob } from "../crypto/mediaCache";
import { redactPreviewsFor } from "../crypto/plaintextCache";
import { adoptImage, ensureImage, forgetImages, rekeyImage, releaseImage } from "../media/images";
import type { PreparedImage } from "../media/prepareImage";
import { encodeVideo, resetVideoWorker, VideoCanceledError, type VideoSendDraft } from "../media/prepareVideo";
import { setTransfer } from "../media/transfers";
import { adoptPoster, adoptVideo, ensureVideo, forgetVideos, rekeyVideo, releaseVideo } from "../media/videos";
import { VideoTooLongError } from "../media/videoPlan";
import type { VoiceTake } from "../voice/recorder";
import { stopVoice } from "../voice/playback";
import {
  isTranscriptionReady,
  raceTimeout,
  TRANSCRIBE_TIMEOUT_MS,
} from "../voice/transcriber";
import { rekeyTranscriptView, setTranscribing } from "../voice/transcriptView";
import { connectRealtime, type Realtime } from "../realtime";
import {
  createRecordingSender,
  createTypingSender,
  TYPING_EXPIRE_MS,
  type PeerActivity,
} from "../typing";
import { setLocked } from "../session";

type PeerRef = { id: string; username: string };

/** Pure (it runs inside `setThread` updaters, which React may call twice). */
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
  const sorted = [...byId.values()].sort((a, b) => {
    const t = a.createdAt.localeCompare(b.createdAt);
    return t !== 0 ? t : a.id.localeCompare(b.id);
  });
  // A transcript shared by the peer lands here as an annotation for a voice note.
  return applyAnnotations(sorted);
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
  /** Account menu on the rail: the profile sheet, and the logout confirmation. */
  const [showProfile, setShowProfile] = useState(false);
  const [confirmLogout, setConfirmLogout] = useState(false);
  /** Clearing this browser: after "Log Out", or because the server ended the session. */
  const [wipe, setWipe] = useState<WipeReason | null>(null);
  const endSession = useCallback(() => setWipe((current) => current ?? "ended"), []);
  const [showInfo, setShowInfo] = useState(false);
  const [invite, setInvite] = useState("");
  const [addBusy, setAddBusy] = useState(false);
  const [addError, setAddError] = useState<string | null>(null);
  const [thread, setThread] = useState<ChatMessage[]>([]);
  const [threadLoading, setThreadLoading] = useState(false);
  const [threadError, setThreadError] = useState<string | null>(null);
  const [draft, setDraft] = useState("");
  /** Message being answered in the open chat; cleared when it is sent or the chat changes. */
  const [replyTo, setReplyTo] = useState<ChatMessage | null>(null);
  const [sendingPeer, setSendingPeer] = useState<string | null>(null);
  const [presenceByUser, setPresenceByUser] = useState<Record<string, Presence>>({});
  /** Peers typing to us right now, by lowercased user id. */
  const [typingPeers, setTypingPeers] = useState<ReadonlySet<string>>(() => new Set());
  /** Peers recording a voice note to us right now, by lowercased user id. */
  const [recordingPeers, setRecordingPeers] = useState<ReadonlySet<string>>(() => new Set());
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
  /** Optimistic ids the reader deleted while the send was still in flight. */
  const droppedSends = useRef(new Set<string>());
  /** Server id for an optimistic bubble, once the send has been accepted. */
  const confirmedSends = useRef(new Map<string, string>());

  function discardMessage(messageId: string) {
    releaseImage(messageId);
    releaseVideo(messageId);
    forgetMessageLocally(messageId);
  }

  /**
   * The send finished after the reader had already removed the bubble. Unsend it, so the
   * server's copy can't pop back into the thread on the next load.
   */
  async function consumeDroppedSend(localId: string, msg: ChatMessage, peerId: string): Promise<boolean> {
    if (!droppedSends.current.delete(localId)) return false;
    discardMessage(localId);
    const still = selectedRef.current?.id.toLowerCase() === peerId.toLowerCase();
    try {
      await api.deleteMessage(session.token, msg.id, "everyone");
    } catch (err) {
      // The server still has the message. Keep its decrypted copy so the bubble we
      // put back can still draw, and so a later reload is not stuck unable to cache it.
      if (still) {
        setThread((prev) => mergeMessages(prev.filter((m) => m.id !== localId), [msg]));
        setThreadError(err instanceof ApiError ? err.message : "Could not delete the message.");
      }
      setPreviewRev((n) => n + 1);
      return true;
    }
    discardMessage(msg.id);
    const gone = tombstone(msg);
    if (still) {
      const apply = (list: ChatMessage[]) =>
        mergeMessages(
          list.filter((m) => m.id !== localId && m.id.toLowerCase() !== msg.id.toLowerCase()),
          [gone],
        );
      rewritePreview(session.user.id, peerId, apply(threadRef.current));
      setThread(apply);
      setReplyTo((current) => (current?.id === localId || current?.id === msg.id ? null : current));
    } else {
      redactPreviewsFor(session.user.id, msg.id);
    }
    setPreviewRev((n) => n + 1);
    void refresh();
    return true;
  }
  const lastPresenceSweep = useRef(0);
  const draftRef = useRef(draft);
  draftRef.current = draft;
  const realtime = useRef<Realtime | null>(null);
  const typingTimers = useRef(new Map<string, number>());
  const recordingTimers = useRef(new Map<string, number>());
  const typingSender = useMemo(
    () =>
      createTypingSender((peerId, typing) =>
        realtime.current?.send({ type: "typing", peer_user_id: peerId, is_typing: typing }),
      ),
    [],
  );
  const recordingSender = useMemo(
    () =>
      createRecordingSender((peerId, recording) =>
        realtime.current?.send({
          type: "recording",
          peer_user_id: peerId,
          is_recording: recording,
        }),
      ),
    [],
  );

  const markFlag = useCallback(
    (
      peerId: string,
      on: boolean,
      timers: Map<string, number>,
      setPeers: (update: (prev: ReadonlySet<string>) => ReadonlySet<string>) => void,
    ) => {
      const id = peerId.toLowerCase();
      if (!id) return;
      window.clearTimeout(timers.get(id));
      timers.delete(id);
      const apply = (value: boolean) =>
        setPeers((prev) => {
          if (prev.has(id) === value) return prev;
          const next = new Set(prev);
          if (value) next.add(id);
          else next.delete(id);
          return next;
        });
      if (on) {
        timers.set(
          id,
          window.setTimeout(() => {
            timers.delete(id);
            apply(false);
          }, TYPING_EXPIRE_MS),
        );
      }
      apply(on);
    },
    [],
  );

  /** A peer started or stopped typing; "started" lapses on its own unless refreshed (typing.ts). */
  const markTyping = useCallback(
    (peerId: string, typing: boolean) => {
      if (typing) markFlag(peerId, false, recordingTimers.current, setRecordingPeers);
      markFlag(peerId, typing, typingTimers.current, setTypingPeers);
    },
    [markFlag],
  );

  /** A peer started or stopped recording a voice note to us. */
  const markRecording = useCallback(
    (peerId: string, recording: boolean) => {
      if (recording) markFlag(peerId, false, typingTimers.current, setTypingPeers);
      markFlag(peerId, recording, recordingTimers.current, setRecordingPeers);
    },
    [markFlag],
  );

  useEffect(() => {
    const typing = typingTimers.current;
    const recording = recordingTimers.current;
    return () => {
      for (const timer of typing.values()) window.clearTimeout(timer);
      for (const timer of recording.values()) window.clearTimeout(timer);
      typing.clear();
      recording.clear();
    };
  }, []);

  /* Leaving a chat (or the page) ends our typing there. A live recording keeps
     signalling until the take itself is cancelled — hiding the tab is not that. */
  useEffect(() => {
    const hide = () => {
      if (document.hidden) typingSender.stop();
    };
    document.addEventListener("visibilitychange", hide);
    return () => {
      document.removeEventListener("visibilitychange", hide);
      typingSender.stop();
      recordingSender.stop();
    };
  }, [selected?.id, typingSender, recordingSender]);

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
          endSession();
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
  }, [refresh, endSession]);

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
    setReplyTo(null);
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
    const connection = connectRealtime({
      token: session.token,
      onFatalAuth: endSession,
      onEvent: (event) => {
        if (event.type === "auth.ok") return;
        if (event.type === "typing") {
          markTyping(String(event.raw.user_id ?? ""), event.raw.is_typing !== false);
          return;
        }
        if (event.type === "recording") {
          markRecording(String(event.raw.user_id ?? ""), event.raw.is_recording !== false);
          return;
        }
        if (event.type === "presence.update") {
          const id = String(event.raw.user_id ?? "").toLowerCase();
          if (!id) return;
          if (!event.raw.online) {
            markTyping(id, false);
            markRecording(id, false);
          }
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
              // Their message is what the typing/recording was for: cleared in the same
              // update that adds it, so it lands where the activity bubble was.
              markTyping(dto.sender_user_id, false);
              markRecording(dto.sender_user_id, false);
              const open = selectedRef.current;
              setPreviewRev((n) => n + 1);
              if (open && open.id.toLowerCase() === peer.toLowerCase()) {
                setThread((prev) => mergeMessages(prev, [msg]));
              }
            } catch {
              /* roster refresh already ran; next poll/WS event retries */
              markTyping(dto.sender_user_id, false);
              markRecording(dto.sender_user_id, false);
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
          if (event.type === "message.deleted") {
            const id = String(event.raw.message_id ?? "");
            // Unsent by its author: nothing of it may linger here, open chat or not.
            if (id) {
              discardMessage(id);
              const key = id.toLowerCase();
              if (open && threadRef.current.some((m) => m.id.toLowerCase() === key)) {
                const next = threadRef.current.map((m) => (m.id.toLowerCase() === key ? tombstone(m) : m));
                rewritePreview(session.user.id, open.id, next);
                setThread((prev) => prev.map((m) => (m.id.toLowerCase() === key ? tombstone(m) : m)));
              }
              redactPreviewsFor(session.user.id, id);
              setPreviewRev((n) => n + 1);
            }
          }
        }
      },
    });
    realtime.current = connection;
    return () => {
      realtime.current = null;
      connection.close();
    };
  }, [session.token, session.user.id, navigate, refresh, markTyping, markRecording]);

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
          // A shared transcript may have filled in the chat preview.
          setPreviewRev((n) => n + 1);
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
        activity: activityFor(c.peer.id, typingPeers, recordingPeers),
      }))
      .filter(
        (entry) =>
          !q ||
          entry.username.toLowerCase().includes(q) ||
          entry.subtitle.toLowerCase().includes(q),
      );
  }, [conversations, presenceByUser, previewRev, query, session.user.id, typingPeers, recordingPeers]);

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
          activity: activityFor(c.user_id, typingPeers, recordingPeers),
        };
      });
  }, [contacts, presenceByUser, query, typingPeers, recordingPeers]);

  const lockNow = useCallback(() => {
    setLocked(true);
    navigate("/unlock", { replace: true });
  }, [navigate]);

  const loadVoice = useCallback(
    (message: ChatMessage) => ensureVoiceLoaded(message, session.token),
    [session.token],
  );

  const loadImage = useCallback(
    (message: ChatMessage) => ensureImage(message, session.token),
    [session.token],
  );

  const loadVideo = useCallback(
    (message: ChatMessage) => ensureVideo(message, session.token),
    [session.token],
  );

  useEffect(() => () => stopVoice(), []);
  // Decrypted photos and videos live only as long as the unlocked shell does.
  useEffect(
    () => () => {
      forgetImages();
      forgetVideos();
      resetVideoWorker();
    },
    [],
  );

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
    typingSender.stop();
    recordingSender.stop();
    draftRef.current = "";
    const peerId = selected.id;
    const reference = replyTo ? replyRefFor(replyTo) : null;
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
      replyTo: reference,
    };
    setThread((prev) => [...prev, optimistic]);
    setDraft("");
    setReplyTo(null);
    setSendingPeer(peerId);
    setThreadError(null);
    try {
      const msg = await sendText({
        token: session.token,
        me: session.user.id,
        peerUserId: peerId,
        text,
        material: identity,
        replyTo: reference,
      });
      confirmedSends.current.set(localId, msg.id);
      if (await consumeDroppedSend(localId, msg, peerId)) return;
      if (selectedRef.current?.id.toLowerCase() !== peerId.toLowerCase()) return;
      setThread((prev) =>
        droppedSends.current.has(localId)
          ? prev.filter((m) => m.id !== localId)
          : mergeMessages(prev.filter((m) => m.id !== localId), [msg]),
      );
      setPreviewRev((n) => n + 1);
      await refresh();
    } catch (err) {
      if (droppedSends.current.delete(localId)) {
        discardMessage(localId);
        return;
      }
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
    typingSender.stop();
    recordingSender.stop();
    const peerId = selected.id;
    const material = identity;
    const reference = replyTo ? replyRefFor(replyTo) : null;
    setReplyTo(null);
    const localId = `pending:${crypto.randomUUID()}`;
    const pending = take.pendingTranscript ?? Promise.resolve(null);
    /** Follows the bubble from its optimistic id to the server's. */
    let noteId = localId;
    // The bubble's transcript button laps a progress ring until the text lands.
    if (!take.transcript && take.pendingTranscript) setTranscribing(localId, true);
    const optimistic: ChatMessage = {
      id: localId,
      senderUserId: session.user.id,
      text: take.transcript || "Voice message",
      createdAt: new Date().toISOString(),
      isMine: true,
      deleted: false,
      failed: false,
      kind: "voice",
      voiceDurationMs: take.durationMs,
      voiceWaveform: take.waveform,
      mime: take.mime,
      transcript: take.transcript,
      pending: true,
      replyTo: reference,
    };
    await saveMediaBlob(localId, take.data);
    setThread((prev) => [...prev, optimistic]);
    setSendingPeer(peerId);
    setThreadError(null);
    try {
      // Same rule as iOS: wait for Whisper only when the model is already on
      // disk. A first-time download must not hold the note.
      let sealed = take.transcript;
      if (!sealed && isTranscriptionReady()) {
        sealed = await raceTimeout(pending, TRANSCRIBE_TIMEOUT_MS, null);
        if (sealed && selectedRef.current?.id.toLowerCase() === peerId.toLowerCase()) {
          const text = sealed;
          setThread((prev) =>
            prev.map((m) => (m.id === localId ? { ...m, transcript: text, text } : m)),
          );
        }
      }
      if (sealed) setTranscribing(localId, false);
      const msg = await sendVoice({
        token: session.token,
        me: session.user.id,
        peerUserId: peerId,
        material,
        take: { ...take, transcript: sealed },
        replyTo: reference,
      });
      rekeyTranscriptView(localId, msg.id);
      confirmedSends.current.set(localId, msg.id);
      if (await consumeDroppedSend(localId, msg, peerId)) {
        setTranscribing(msg.id, false);
        return;
      }
      noteId = msg.id;
      if (selectedRef.current?.id.toLowerCase() === peerId.toLowerCase()) {
        setThread((prev) =>
          droppedSends.current.has(localId)
            ? prev.filter((m) => m.id !== localId)
            : mergeMessages(prev.filter((m) => m.id !== localId), [msg]),
        );
        setPreviewRev((n) => n + 1);
      }
      if (!msg.transcript) {
        void pending.then(async (text) => {
          if (!text) {
            setTranscribing(msg.id, false);
            return;
          }
          try {
            await shareTranscript({
              token: session.token,
              me: session.user.id,
              peerUserId: peerId,
              messageId: msg.id,
              transcript: text,
              material,
            });
          } catch (err) {
            console.warn("Could not share voice transcript:", err);
          }
          setTranscribing(msg.id, false);
          if (!alive.current) return;
          if (selectedRef.current?.id.toLowerCase() === peerId.toLowerCase()) {
            setThread((prev) => applyAnnotations(prev));
          }
          setPreviewRev((n) => n + 1);
        });
      }
    } catch (err) {
      setTranscribing(noteId, false);
      if (droppedSends.current.delete(localId)) {
        discardMessage(localId);
        return;
      }
      if (selectedRef.current?.id.toLowerCase() !== peerId.toLowerCase()) return;
      setThread((prev) =>
        prev.map((m) => (m.id === localId ? { ...m, pending: false, failed: true } : m)),
      );
      setThreadError(err instanceof ApiError ? err.message : "Could not send the voice message.");
      return;
    } finally {
      setSendingPeer((current) => (current === peerId ? null : current));
    }
    try {
      await refresh();
    } catch (err) {
      if (!alive.current) return;
      setThreadError(err instanceof ApiError ? err.message : "Could not refresh chats.");
    }
  }

  /** Photos from the send sheet: each its own message, in order, the caption on the first. */
  async function submitImages(images: PreparedImage[], caption: string) {
    if (!selected || !identity || images.length === 0) return;
    typingSender.stop();
    recordingSender.stop();
    const peerId = selected.id;
    const material = identity;
    const start = Date.now();
    const reference = replyTo ? replyRefFor(replyTo) : null;
    setReplyTo(null);
    const drafts = images.map((image, index) => {
      const clientId = crypto.randomUUID();
      const localId = `pending:${clientId}`;
      const text = index === 0 ? caption.trim() : "";
      // The bubble shows the photo from the bytes we already have, before any upload.
      adoptImage(localId, image.bytes, image.mime);
      const optimistic: ChatMessage = {
        id: localId,
        senderUserId: session.user.id,
        text: text || "Photo",
        caption: text || null,
        createdAt: new Date(start + index).toISOString(),
        isMine: true,
        deleted: false,
        failed: false,
        kind: "image",
        mime: image.mime,
        imageWidth: image.width,
        imageHeight: image.height,
        mediaBytes: image.bytes.byteLength,
        pending: true,
        replyTo: index === 0 ? reference : null,
      };
      return { image, clientId, localId, caption: text, optimistic };
    });
    setThread((prev) => [...prev, ...drafts.map((draft) => draft.optimistic)]);
    setSendingPeer(peerId);
    setThreadError(null);
    for (const draft of drafts) {
      try {
        const msg = await sendImage({
          token: session.token,
          me: session.user.id,
          peerUserId: peerId,
          material,
          image: draft.image,
          caption: draft.caption,
          clientMessageId: draft.clientId,
          replyTo: draft.optimistic.replyTo ?? null,
          onProgress: (loaded, total) => setTransfer(draft.localId, { direction: "up", loaded, total }),
        });
        rekeyImage(draft.localId, msg.id);
        confirmedSends.current.set(draft.localId, msg.id);
        if (await consumeDroppedSend(draft.localId, msg, peerId)) continue;
        if (selectedRef.current?.id.toLowerCase() === peerId.toLowerCase()) {
          setThread((prev) =>
            droppedSends.current.has(draft.localId)
              ? prev.filter((m) => m.id !== draft.localId)
              : mergeMessages(prev.filter((m) => m.id !== draft.localId), [msg]),
          );
          setPreviewRev((n) => n + 1);
        }
      } catch (err) {
        if (droppedSends.current.delete(draft.localId)) {
          discardMessage(draft.localId);
          continue;
        }
        if (selectedRef.current?.id.toLowerCase() === peerId.toLowerCase()) {
          setThread((prev) =>
            prev.map((m) => (m.id === draft.localId ? { ...m, pending: false, failed: true } : m)),
          );
          setThreadError(
            err instanceof ApiError || err instanceof Error ? err.message : "Could not send the photo.",
          );
        }
      } finally {
        setTransfer(draft.localId, null);
      }
    }
    setSendingPeer((current) => (current === peerId ? null : current));
    try {
      await refresh();
    } catch (err) {
      if (!alive.current) return;
      setThreadError(err instanceof ApiError ? err.message : "Could not refresh chats.");
    }
  }

  /** Clips from the send sheet: the bubble lands first, then compress → upload. */
  async function submitVideos(drafts: VideoSendDraft[], caption: string) {
    if (!selected || !identity || drafts.length === 0) return;
    typingSender.stop();
    recordingSender.stop();
    const peerId = selected.id;
    const material = identity;
    const start = Date.now();
    const reference = replyTo ? replyRefFor(replyTo) : null;
    setReplyTo(null);
    const jobs = drafts.map((draft, index) => {
      const clientId = crypto.randomUUID();
      const localId = `pending:${clientId}`;
      const text = index === 0 ? caption.trim() : "";
      const kept = Math.max(0.1, (draft.trim?.end ?? draft.probe.duration) - (draft.trim?.start ?? 0));
      if (draft.poster) adoptPoster(localId, draft.poster);
      const optimistic: ChatMessage = {
        id: localId,
        senderUserId: session.user.id,
        text: text || "Video",
        caption: text || null,
        createdAt: new Date(start + index).toISOString(),
        isMine: true,
        deleted: false,
        failed: false,
        kind: "video",
        mime: "video/mp4",
        imageWidth: draft.probe.width,
        imageHeight: draft.probe.height,
        mediaBytes: draft.probe.bytes,
        videoDurationMs: Math.round(kept * 1000),
        pending: true,
        replyTo: index === 0 ? reference : null,
      };
      return { draft, clientId, localId, caption: text, optimistic };
    });
    setThread((prev) => [...prev, ...jobs.map((job) => job.optimistic)]);
    setSendingPeer(peerId);
    setThreadError(null);
    for (const job of jobs) {
      try {
        setTransfer(job.localId, { direction: "up", phase: "preparing", loaded: 0, total: 1000 });
        const encoded = await encodeVideo(job.draft.file, {
          trim: job.draft.trim,
          mute: job.draft.mute,
          onProgress: (value) =>
            setTransfer(job.localId, {
              direction: "up",
              phase: "preparing",
              loaded: Math.round(value * 1000),
              total: 1000,
            }),
        });
        adoptVideo(job.localId, encoded.bytes, encoded.poster);
        if (selectedRef.current?.id.toLowerCase() === peerId.toLowerCase()) {
          setThread((prev) =>
            prev.map((m) =>
              m.id === job.localId
                ? {
                    ...m,
                    imageWidth: encoded.width,
                    imageHeight: encoded.height,
                    mediaBytes: encoded.bytes.byteLength,
                    videoDurationMs: encoded.durationMs,
                  }
                : m,
            ),
          );
        }
        const msg = await sendVideo({
          token: session.token,
          me: session.user.id,
          peerUserId: peerId,
          material,
          video: encoded,
          caption: job.caption,
          clientMessageId: job.clientId,
          replyTo: job.optimistic.replyTo ?? null,
          onProgress: (loaded, total) =>
            setTransfer(job.localId, { direction: "up", phase: "transferring", loaded, total }),
          onUploaded: () =>
            setTransfer(job.localId, { direction: "up", phase: "finishing", loaded: 0, total: null }),
        });
        rekeyVideo(job.localId, msg.id);
        confirmedSends.current.set(job.localId, msg.id);
        if (await consumeDroppedSend(job.localId, msg, peerId)) continue;
        if (selectedRef.current?.id.toLowerCase() === peerId.toLowerCase()) {
          setThread((prev) =>
            droppedSends.current.has(job.localId)
              ? prev.filter((m) => m.id !== job.localId)
              : mergeMessages(prev.filter((m) => m.id !== job.localId), [msg]),
          );
          setPreviewRev((n) => n + 1);
        }
      } catch (err) {
        if (droppedSends.current.delete(job.localId)) {
          discardMessage(job.localId);
          continue;
        }
        if (err instanceof VideoCanceledError) {
          setThread((prev) => prev.filter((m) => m.id !== job.localId));
        } else if (selectedRef.current?.id.toLowerCase() === peerId.toLowerCase()) {
          setThread((prev) =>
            prev.map((m) => (m.id === job.localId ? { ...m, pending: false, failed: true } : m)),
          );
          setThreadError(
            err instanceof VideoTooLongError || err instanceof ApiError || err instanceof Error
              ? err.message
              : "Could not send the video.",
          );
        }
      } finally {
        setTransfer(job.localId, null);
      }
    }
    setSendingPeer((current) => (current === peerId ? null : current));
    try {
      await refresh();
    } catch (err) {
      if (!alive.current) return;
      setThreadError(err instanceof ApiError ? err.message : "Could not refresh chats.");
    }
  }

  /**
   * Deletes one message here and — unless it never reached the server — there too.
   *
   * The server goes first, as on iOS: "for me" is stored as a hide row, and applying it locally
   * before the round-trip would let the next poll bring the bubble straight back. A message that
   * is still unsent only exists in this tab, so it just goes. A message that failed to
   * decrypt still has a server id — `failed` is not the same as unsent.
   */
  async function deleteMessage(message: ChatMessage, scope: "me" | "everyone") {
    if (!selected) return;
    const peerId = selected.id;
    const localOnly = isUnsent(message);
    const serverId = confirmedSends.current.get(message.id);
    if (message.pending || localOnly) droppedSends.current.add(message.id);
    // The send was accepted after the dialog opened on the optimistic bubble.
    // Unsend that server row, or the next load puts the message back.
    if (serverId) {
      try {
        await api.deleteMessage(session.token, serverId, "everyone");
      } catch (err) {
        droppedSends.current.delete(message.id);
        if (selectedRef.current?.id.toLowerCase() === peerId.toLowerCase()) {
          setThreadError(err instanceof ApiError ? err.message : "Could not delete the message.");
        }
        return;
      }
      discardMessage(message.id);
      discardMessage(serverId);
      const serverKey = serverId.toLowerCase();
      const apply = (list: ChatMessage[]) => {
        const server = list.find((m) => m.id.toLowerCase() === serverKey);
        const rest = list.filter((m) => m.id !== message.id && m.id.toLowerCase() !== serverKey);
        return server ? mergeMessages(rest, [tombstone(server)]) : rest;
      };
      rewritePreview(session.user.id, peerId, apply(threadRef.current));
      if (selectedRef.current?.id.toLowerCase() === peerId.toLowerCase()) {
        setThread(apply);
        setThreadError(null);
        setReplyTo((current) =>
          current?.id === message.id || current?.id === serverId ? null : current,
        );
      }
      setPreviewRev((n) => n + 1);
      return;
    }
    if (!localOnly) {
      try {
        await api.deleteMessage(session.token, message.id, scope);
      } catch (err) {
        if (selectedRef.current?.id.toLowerCase() === peerId.toLowerCase()) {
          setThreadError(err instanceof ApiError ? err.message : "Could not delete the message.");
        }
        return;
      }
    }
    const key = message.id.toLowerCase();
    const apply = (list: ChatMessage[]) =>
      scope === "everyone" && !localOnly
        ? list.map((m) => (m.id.toLowerCase() === key ? tombstone(m) : m))
        : list.filter((m) => m.id.toLowerCase() !== key);
    discardMessage(message.id);
    rewritePreview(session.user.id, peerId, apply(threadRef.current));
    if (selectedRef.current?.id.toLowerCase() === peerId.toLowerCase()) {
      setThread(apply);
      setThreadError(null);
      // Deleting the message being answered ends that reply.
      setReplyTo((current) => (current?.id === message.id ? null : current));
    }
    setPreviewRev((n) => n + 1);
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
  const selectedActivity = selected
    ? activityFor(selected.id, typingPeers, recordingPeers)
    : undefined;
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
        onProfile={() => setShowProfile(true)}
        onLogout={() => setConfirmLogout(true)}
      />

      <div className="shell-body">
        {tab === "settings" ? (
          <SettingsPane
            session={session}
            identity={identity}
            shareLink={shareLink}
            onLogout={() => setWipe("logout")}
            onSessionEnded={endSession}
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
                activity={selectedActivity ?? null}
                messages={thread}
                loading={threadLoading}
                error={threadError}
                canSend={Boolean(identity)}
                sending={sendingPeer?.toLowerCase() === selected.id.toLowerCase()}
                draft={draft}
                onDraftChange={(value) => {
                  setDraft(value);
                  typingSender.input(selected.id, value.trim().length > 0);
                }}
                onSend={() => void submitMessage()}
                onSendVoice={(take) => void submitVoice(take)}
                onRecordingChange={(recording) => {
                  if (recording) {
                    typingSender.stop();
                    recordingSender.set(selected.id, true);
                  } else {
                    recordingSender.stop();
                  }
                }}
                onSendImages={(images, caption) => void submitImages(images, caption)}
                onSendVideos={(drafts, caption) => void submitVideos(drafts, caption)}
                onBack={() => setSelected(null)}
                onShowInfo={() => setShowInfo(true)}
                onLoadVoice={loadVoice}
                onLoadImage={loadImage}
                onLoadVideo={loadVideo}
                replyTo={replyTo}
                onReply={(message) => setReplyTo(message)}
                onCancelReply={() => setReplyTo(null)}
                onDelete={(message, scope) => void deleteMessage(message, scope)}
                myId={session.user.id}
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

      {showProfile ? (
        <ProfileSheet
          session={session}
          shareLink={shareLink}
          onShowQr={() => {
            setShowProfile(false);
            setShowQr(true);
          }}
          onClose={() => setShowProfile(false)}
        />
      ) : null}

      {confirmLogout ? (
        <LogoutDialog
          onCancel={() => setConfirmLogout(false)}
          onConfirm={() => {
            setConfirmLogout(false);
            setWipe("logout");
          }}
        />
      ) : null}

      {wipe ? <DeviceWipeDialog session={session} reason={wipe} continued={wipe === "logout"} /> : null}

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
            {selectedActivity ? (
              <TypingLabel word={selectedActivity} />
            ) : (
              <span className={selectedPresence?.online ? "online" : undefined}>
                {presenceLabel(selectedPresence) || "presence unknown"}
              </span>
            )}
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

function activityFor(
  id: string,
  typingPeers: ReadonlySet<string>,
  recordingPeers: ReadonlySet<string>,
): PeerActivity | undefined {
  const key = id.toLowerCase();
  if (recordingPeers.has(key)) return "recording";
  if (typingPeers.has(key)) return "typing";
  return undefined;
}
