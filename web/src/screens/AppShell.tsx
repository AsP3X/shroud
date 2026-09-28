import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Bell, BellOff, Phone, QrCode, Video, X } from "lucide-react";
import {
  api,
  ApiError,
  type CallModality,
  type ChatMute,
  type Contact,
  type ContactRequest,
  type Conversation,
  type Session,
  type WireMessage,
  type WireReaction,
} from "../api/client";
import { configureCalls, endCallForLock, handleCallEvent, startCall } from "../calls/service";
import { getCallView, useCallBusy } from "../calls/store";
import { Avatar } from "../components/Avatar";
import { BrandMark } from "../components/BrandMark";
import { CallOverlay } from "../components/CallOverlay";
import { ChatList, type ListEntry } from "../components/ChatList";
import { ChatMenu, muteSeconds, type ChatMenuAction } from "../components/ChatMenu";
import type { MenuAnchor } from "../components/ContextMenu";
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
import { acceptChangedPeerKey, isPeerKeyBlocked, onPeerKeyBlocked, PEER_KEY_CHANGED } from "../crypto/peerIdentity";
import { loadIdentity } from "../crypto/store";
import {
  clearFreshSignIn,
  currentDeviceLabel,
  isFreshSignIn,
  saveDeviceName,
  suggestedDeviceName,
} from "../deviceNaming";
import { DeviceNameDialog } from "../components/DeviceNameDialog";
import { parseInvite, shareUrl } from "../invite";
import {
  applyAnnotations,
  fetchLatest,
  hydratePreviews,
  ingestIncoming,
  ensureVoiceLoaded,
  loadHistoryPage,
  peerIdForMessage,
  peerIdentityForSending,
  peerIdentityPublic,
  sendFailureText,
  previewCopy,
  forgetDecryptedState,
  forgetMessageLocally,
  isUnsent,
  OLDER_PAGE_SIZE,
  previewLine,
  releaseVoice,
  replyRefFor,
  rewritePreview,
  tombstone,
  sendImage,
  sendLinkWithImage,
  sendText,
  sendVideo,
  sendVoice,
  shareTranscript,
  type ChatMessage,
  type HistoryCursor,
} from "../messaging";
import { saveMediaBlob } from "../crypto/mediaCache";
import { loadPreview, redactPreviewsFor, replacePreview } from "../crypto/plaintextCache";
import { adoptImage, ensureImage, forgetImages, rekeyImage, releaseImage } from "../media/images";
import type { PreparedImage } from "../media/prepareImage";
import { encodeVideo, resetVideoWorker, VideoCanceledError, type VideoSendDraft } from "../media/prepareVideo";
import { setTransfer } from "../media/transfers";
import { adoptPoster, adoptVideo, ensureVideo, forgetVideos, rekeyVideo, releaseVideo } from "../media/videos";
import { VideoTooLongError } from "../media/videoPlan";
import type { VoiceTake } from "../voice/recorder";
import { stopVoice } from "../voice/playback";
import { rekeyTranscriptView, setTranscribing } from "../voice/transcriptView";
import { connectRealtime, type Realtime } from "../realtime";
import {
  createRecordingSender,
  createTypingSender,
  TYPING_EXPIRE_MS,
  type PeerActivity,
} from "../typing";
import { lockNow as lockSession, saveShareCode } from "../session";
import { sendsTyping, setPrivacySettings, usePrivacySettings } from "../privacy";
import {
  applyReactionChanges,
  DEFAULT_REACTION_LIMIT,
  emojisOf,
  openReaction,
  rebasedReactions,
  saveReaction,
  toggledReactions,
  withMyReaction,
  type Reaction,
} from "../reactions";
import { useLinkPreviewComposer } from "../linkPreview/useLinkPreviewComposer";
import { announce, hideUnreadCount, showUnreadCount } from "../notifications/attention";
import { isMuted, muteLabel } from "../notifications/mute";
import { updateNotificationPrefs, useNotificationPrefs } from "../notifications/prefs";
import {
  closeChatNotifications,
  closeNotifications,
  enableNotifications,
  notificationPermission,
  onNotificationOpen,
  reactionTag,
  syncNotifications,
  type OpenRequest,
  type Permission,
} from "../notifications/push";

type PeerRef = { id: string; username: string };

/** Rounds a reaction save may lose to our other device writing first before its set stands. */
const MAX_REACTION_REBASES = 3;

/** Messages walked in behind the newest page before older ones wait for the reader to scroll up. */
const PREFETCH_MESSAGES = 300;
/** Pause between those background pages. */
const PREFETCH_PAUSE_MS = 600;

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
  /** The server has messages older than the thread holds (see `loadOlder`). */
  const [hasOlder, setHasOlder] = useState(false);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [draft, setDraft] = useState("");
  /** Message being answered in the open chat; cleared when it is sent or the chat changes. */
  const [replyTo, setReplyTo] = useState<ChatMessage | null>(null);
  const [sendingPeer, setSendingPeer] = useState<string | null>(null);
  const [presenceByUser, setPresenceByUser] = useState<Record<string, Presence>>({});
  const [newShareCode, setNewShareCode] = useState<string | null>(null);
  /** Peers typing to us right now, by lowercased user id. */
  const [typingPeers, setTypingPeers] = useState<ReadonlySet<string>>(() => new Set());
  /** Peers recording a voice note to us right now, by lowercased user id. */
  const [recordingPeers, setRecordingPeers] = useState<ReadonlySet<string>>(() => new Set());
  const [previewRev, setPreviewRev] = useState(0);
  const notificationPrefs = useNotificationPrefs();
  /** For the chat list's offer to turn notifications on. */
  const [permission, setPermission] = useState<Permission>(() => notificationPermission());
  /** The window has focus: only then is the open chat read (a visible, unfocused window may
   * sit behind another app's). */
  const [windowFocused, setWindowFocused] = useState(() => document.hasFocus());
  /** A chat's own menu: right-click on its row, or Notifications in its info. */
  const [chatMenu, setChatMenu] = useState<{
    peerId: string;
    username: string;
    anchor: MenuAnchor;
    trigger: HTMLElement | null;
  } | null>(null);
  /** A notification click whose chat is opened once the chats it names are loaded. */
  const [pendingOpen, setPendingOpen] = useState<OpenRequest | null>(null);
  const mobileShowThread = Boolean(selected) && tab !== "settings";
  const identity = loadIdentity(session.user.id);
  /** The tab is on screen (a background tab still polls and hears the socket). */
  const [pageVisible, setPageVisible] = useState(() => !document.hidden);
  /**
   * The open chat while this tab is on screen. Banners use it. The unread count and the heart
   * badge wait until the window is focused as well (`readingNow`).
   */
  const lookingAt = selected && tab !== "settings" && identity && pageVisible ? selected.id.toLowerCase() : null;
  const lookingAtRef = useRef(lookingAt);
  lookingAtRef.current = lookingAt;
  const alive = useRef(true);
  const selectedRef = useRef(selected);
  selectedRef.current = selected;
  useEffect(() => {
    return onPeerKeyBlocked((userId) => {
      if (selectedRef.current?.id.toLowerCase() === userId) setThreadError(PEER_KEY_CHANGED);
    });
  }, []);
  const conversationsRef = useRef(conversations);
  conversationsRef.current = conversations;
  const contactsRef = useRef(contacts);
  contactsRef.current = contacts;
  /** A call rings or runs on this device: new ones wait (calls/, components/CallOverlay.tsx). */
  const callBusy = useCallBusy();
  const threadRef = useRef(thread);
  threadRef.current = thread;
  /** Start of the next older page for the open chat; null once its history is all here. */
  const olderCursor = useRef<HistoryCursor | null>(null);
  /** The older page in flight, shared by the background walk and the reader's scrolling. */
  const olderLoad = useRef<Promise<void> | null>(null);
  /** Bumped per opened chat, so a page for the previous one is dropped on arrival. */
  const historyEpoch = useRef(0);
  /**
   * Bumped per peer when that chat is cleared for us. A fetch that started earlier must not
   * restore it; one that starts afterwards may, because the server hides the old history.
   */
  const chatEpoch = useRef(new Map<string, number>());
  /** Optimistic ids the reader deleted while the send was still in flight. */
  const droppedSends = useRef(new Set<string>());
  /** Server id for an optimistic bubble, once the send has been accepted. */
  const confirmedSends = useRef(new Map<string, string>());
  /**
   * Highest reaction seq applied to the open chat. Starts at the newest page's snapshot: every
   * message loaded after that carries its own reactions, and changes to ones already on screen
   * come through catch-up (`syncReactions`) or the socket.
   */
  const reactionCursor = useRef<number | null>(null);
  const reactionSync = useRef<Promise<void> | null>(null);
  /** Our latest wanted set per message while a save for it is in flight (taps collapse). */
  const reactionIntents = useRef(new Map<string, string[]>());
  /** How many emoji one person may leave on a message: the server's setting (`GET /config`). */
  const reactionLimit = useRef(DEFAULT_REACTION_LIMIT);
  /** Our reaction as the server last confirmed it, per message with a save in flight. */
  const reactionConfirmed = useRef(new Map<string, Reaction | null>());
  /** The catch-up cursor when a message's first tap went out (see `react`). */
  const reactionCursorAtTap = useRef(new Map<string, number | null>());
  /** While a catch-up runs: the lowest point a page or a failed tap sent the cursor back to. */
  const reactionFloor = useRef<number | null>(null);
  const [reactionNotice, setReactionNotice] = useState<{ id: number; text: string } | null>(null);
  /**
   * Highest reaction seq this browser marked seen, per peer. A conversations refresh that
   * raced the seen call still carries the old heart badge; this keeps it from coming back.
   */
  const reactionsSeen = useRef(new Map<string, number>());
  /**
   * Chats this browser (or our other device) read, by peer: up to their `last_message_at` at the
   * time. A list that raced the read still counts those messages; this keeps the count at 0 until
   * something newer arrives.
   */
  const readThrough = useRef(new Map<string, number>());
  /** Mutes being saved, by peer, so a racing list does not flip the bell back meanwhile. */
  const pendingMutes = useRef(new Map<string, ChatMute | null>());
  /** Which change holds a pending mute: an earlier change's timer leaves a newer one alone. */
  const pendingMuteHolds = useRef(new Map<string, symbol>());
  const refreshSoonTimer = useRef(0);

  function discardMessage(messageId: string) {
    releaseImage(messageId);
    releaseVideo(messageId);
    releaseVoice(messageId);
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
  /** The draft's link preview, built in this browser through the link relay. */
  const linkPreview = useLinkPreviewComposer({
    draft,
    token: session.token,
    conversationId: selected?.id ?? null,
    canSend: Boolean(identity),
  });
  const realtime = useRef<Realtime | null>(null);
  const typingTimers = useRef(new Map<string, number>());
  const recordingTimers = useRef(new Map<string, number>());
  // With typing indicators off nothing is sent; the server would drop it anyway (privacy.ts).
  const typingSender = useMemo(
    () =>
      createTypingSender((peerId, typing) => {
        if (sendsTyping()) {
          realtime.current?.send({ type: "typing", peer_user_id: peerId, is_typing: typing });
        }
      }),
    [],
  );
  const recordingSender = useMemo(
    () =>
      createRecordingSender((peerId, recording) => {
        if (sendsTyping()) {
          realtime.current?.send({
            type: "recording",
            peer_user_id: peerId,
            is_recording: recording,
          });
        }
      }),
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

  /* The account's privacy switches, for the whole shell (privacy.ts). Cleared on the way out so
     the next account in this tab doesn't start from these. */
  const privacy = usePrivacySettings();
  useEffect(() => {
    let cancelled = false;
    api
      .privacySettings(session.token)
      .then((settings) => {
        if (!cancelled) setPrivacySettings(settings);
      })
      .catch(() => {
        // Unreachable: the server's defaults hold, and it enforces the real values anyway.
      });
    return () => {
      cancelled = true;
      setPrivacySettings(null);
    };
  }, [session.token]);

  /* The share code can change on another device (Settings → Privacy → Reset QR code there): the
     stored one would then be a QR code that no longer works. */
  useEffect(() => {
    let cancelled = false;
    api
      .me(session.token)
      .then(({ user }) => {
        if (cancelled || !user.share_code || user.share_code === session.user.share_code) return;
        saveShareCode(user.share_code);
        setNewShareCode(user.share_code);
      })
      .catch(() => {
        // Offline or signed out: the next refresh or sign-in settles it.
      });
    return () => {
      cancelled = true;
    };
  }, [session.token, session.user.share_code]);

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

  /**
   * What this browser did that a racing list may not show yet (pure: runs in state updaters):
   * heart badges it marked seen, chats read here or on our other device, mutes being saved.
   */
  const withLocalState = useCallback((list: Conversation[]): Conversation[] => {
    if (reactionsSeen.current.size === 0 && readThrough.current.size === 0 && pendingMutes.current.size === 0) {
      return list;
    }
    let changed = false;
    const next = list.map((c) => {
      const key = c.peer.id.toLowerCase();
      let out = c;
      const seen = reactionsSeen.current.get(key);
      if (seen != null && out.unseen_reactions && (out.reaction_seq ?? 0) <= seen) {
        out = { ...out, unseen_reactions: 0 };
      }
      const readTo = readThrough.current.get(key);
      const latest = Date.parse(out.last_message_at ?? out.created_at);
      if (readTo != null && (out.unread_count ?? 0) > 0 && !(latest > readTo)) {
        out = { ...out, unread_count: 0 };
      }
      if (pendingMutes.current.has(key)) {
        const mute = pendingMutes.current.get(key) ?? null;
        if (JSON.stringify(out.mute ?? null) !== JSON.stringify(mute)) out = { ...out, mute };
      }
      if (out !== c) changed = true;
      return out;
    });
    return changed ? next : list;
  }, []);

  const refresh = useCallback(async (): Promise<Conversation[]> => {
    // A clear during this refresh must not let its preview fetch put the old line back.
    const clearedAt = new Map(chatEpoch.current);
    const stillCurrent = (peerId: string) =>
      chatEpochOf(peerId) === (clearedAt.get(peerId.toLowerCase()) ?? 0);
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
    const nextConv = conv.status === "fulfilled" ? withLocalState(conv.value.conversations) : null;
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
        stillCurrent,
      ).then(() => {
        if (alive.current) setPreviewRev((n) => n + 1);
      });
    }
    return convs;
  }, [session.token, session.user.id, withLocalState]);

  /* What a switch change means for what is already on screen. */
  const previousPrivacy = useRef(privacy);
  useEffect(() => {
    const before = previousPrivacy.current;
    previousPrivacy.current = privacy;
    if (!before || !privacy) return;
    if (before.send_typing && !privacy.send_typing) {
      typingSender.stop();
      recordingSender.stop();
      for (const id of [...typingTimers.current.keys()]) markTyping(id, false);
      for (const id of [...recordingTimers.current.keys()]) markRecording(id, false);
    }
    if (before.share_presence !== privacy.share_presence) {
      // Hidden now: nobody reads as online or seen. Shown again: fetch afresh right away.
      setPresenceByUser({});
      lastPresenceSweep.current = 0;
      if (privacy.share_presence) void refresh().catch(() => undefined);
    }
  }, [privacy, typingSender, recordingSender, markTyping, markRecording, refresh]);

  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);

  useEffect(() => {
    const onVisibility = () => setPageVisible(!document.hidden);
    document.addEventListener("visibilitychange", onVisibility);
    return () => document.removeEventListener("visibilitychange", onVisibility);
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
    void api
      .clientConfig(session.token)
      .then((config) => {
        reactionLimit.current = Math.max(1, config.reactions.max_per_user);
      })
      .catch(() => {
        /* the default stands until the next load */
      });
  }, [session.token]);

  /** One roster refresh for a burst of reaction events (their heart badges). */
  const refreshSoon = useCallback(() => {
    if (refreshSoonTimer.current) return;
    refreshSoonTimer.current = window.setTimeout(() => {
      refreshSoonTimer.current = 0;
      void refresh().catch(() => {
        /* the regular poll retries */
      });
    }, 700);
  }, [refresh]);
  useEffect(() => () => window.clearTimeout(refreshSoonTimer.current), []);

  /**
   * Clears a chat's heart badge here and asks the server to clear it everywhere. `upTo`
   * defaults to the chat's latest known change; the server clamps it and never goes back.
   */
  const markReactionsSeen = useCallback(
    (peerId: string, upTo = 0) => {
      const key = peerId.toLowerCase();
      const conv = conversationsRef.current.find((c) => c.peer.id.toLowerCase() === key);
      const open = selectedRef.current?.id.toLowerCase() === key ? reactionCursor.current ?? 0 : 0;
      const seq = Math.max(upTo, conv?.reaction_seq ?? 0, open);
      if (seq <= 0) return;
      const previous = reactionsSeen.current.get(key);
      reactionsSeen.current.set(key, Math.max(previous ?? 0, seq));
      setConversations((prev) => withLocalState(prev));
      if (conv) void closeNotifications(reactionTag(conv.id));
      void api.markReactionsSeen(session.token, key, seq).catch(() => {
        // Not saved: back to what we knew (another device's mark included), so the next list
        // shows the badge again and the next look tries again.
        if (reactionsSeen.current.get(key) !== seq) return;
        if (previous == null) reactionsSeen.current.delete(key);
        else reactionsSeen.current.set(key, previous);
      });
    },
    [session.token, withLocalState],
  );

  /**
   * The chat was read here: its count clears at once, its notifications close, and the server
   * clears it on our other devices (and sends the peer read receipts).
   */
  const markChatRead = useCallback(
    (peerId: string) => {
      const key = peerId.toLowerCase();
      const conv = conversationsRef.current.find((c) => c.peer.id.toLowerCase() === key);
      if (!conv) return;
      const through = Date.parse(conv.last_message_at ?? conv.created_at);
      readThrough.current.set(key, Math.max(readThrough.current.get(key) ?? 0, through));
      setConversations((prev) => withLocalState(prev));
      void closeChatNotifications(conv.id);
      void api.markChatRead(session.token, key).catch(() => {
        // Not saved: the next list brings the count back, and the next look tries again.
        if (readThrough.current.get(key) === through) readThrough.current.delete(key);
      });
    },
    [session.token, withLocalState],
  );

  /** Mutes a chat on every device of ours (`null` = until unmuted), or unmutes it (`"off"`). */
  const setChatMute = useCallback(
    async (peerId: string, seconds: number | null | "off") => {
      const key = peerId.toLowerCase();
      const previous =
        conversationsRef.current.find((c) => c.peer.id.toLowerCase() === key)?.mute ?? null;
      const optimistic: ChatMute | null =
        seconds === "off"
          ? null
          : { until: seconds === null ? null : new Date(Date.now() + seconds * 1000).toISOString() };
      const hold = Symbol("mute");
      pendingMuteHolds.current.set(key, hold);
      pendingMutes.current.set(key, optimistic);
      setConversations((prev) => withLocalState(prev));
      try {
        if (seconds === "off") {
          await api.unmuteChat(session.token, key);
        } else {
          const saved = await api.muteChat(session.token, key, seconds);
          if (pendingMuteHolds.current.get(key) === hold) pendingMutes.current.set(key, saved.mute);
          setConversations((prev) => withLocalState(prev));
        }
      } catch (err) {
        // Back to what it was — offline, a refresh could not say — and the error stays up
        // until the next list.
        if (pendingMuteHolds.current.get(key) === hold) {
          pendingMutes.current.delete(key);
          pendingMuteHolds.current.delete(key);
          setConversations((prev) =>
            prev.map((c) => (c.peer.id.toLowerCase() === key ? { ...c, mute: previous } : c)),
          );
        }
        setError(err instanceof ApiError ? err.message : "Could not change the chat's notifications.");
        throw err;
      }
      // Held until a list that includes it has surely landed.
      window.setTimeout(() => {
        if (pendingMuteHolds.current.get(key) !== hold) return;
        pendingMutes.current.delete(key);
        pendingMuteHolds.current.delete(key);
      }, 6000);
    },
    [session.token, withLocalState],
  );

  function runChatMenu(peerId: string, action: ChatMenuAction) {
    setChatMenu(null);
    if (action === "read") {
      markChatRead(peerId);
      return;
    }
    if (action === "unmute") {
      void setChatMute(peerId, "off").catch(() => undefined);
      return;
    }
    const seconds = muteSeconds(action);
    if (seconds !== undefined) void setChatMute(peerId, seconds).catch(() => undefined);
  }

  useEffect(() => {
    const focus = () => setWindowFocused(document.hasFocus());
    window.addEventListener("focus", focus);
    window.addEventListener("blur", focus);
    document.addEventListener("visibilitychange", focus);
    return () => {
      window.removeEventListener("focus", focus);
      window.removeEventListener("blur", focus);
      document.removeEventListener("visibilitychange", focus);
    };
  }, []);

  /* The chat on screen, in a focused window, is being read: its unread count goes, here and on
     every other device of ours. */
  const readingNow = lookingAt && windowFocused ? lookingAt : null;
  const readingNowRef = useRef(readingNow);
  readingNowRef.current = readingNow;
  useEffect(() => {
    if (!readingNow) return;
    const conv = conversations.find((c) => c.peer.id.toLowerCase() === readingNow);
    if ((conv?.unread_count ?? 0) > 0) markChatRead(readingNow);
  }, [conversations, readingNow, markChatRead]);
  /* A background window keeps the heart, the same way it keeps the unread count. */
  useEffect(() => {
    if (!readingNow) return;
    const conv = conversations.find((c) => c.peer.id.toLowerCase() === readingNow);
    if ((conv?.unseen_reactions ?? 0) > 0) markReactionsSeen(readingNow);
  }, [conversations, readingNow, markReactionsSeen]);
  /* Whatever this browser shows about the chat being read goes, whatever its count: reactions,
     and pushes from while it was locked for messages read elsewhere since. */
  const readingConversationId = readingNow
    ? conversations.find((c) => c.peer.id.toLowerCase() === readingNow)?.id ?? null
    : null;
  useEffect(() => {
    if (readingConversationId) void closeChatNotifications(readingConversationId);
  }, [readingConversationId]);
  /* The first list after unlocking: notifications of chats read meanwhile (on another device,
     while this one was locked) go. */
  const closedReadOnes = useRef(false);
  useEffect(() => {
    if (loading || closedReadOnes.current) return;
    closedReadOnes.current = true;
    for (const c of conversations) {
      if ((c.unread_count ?? 0) === 0) void closeNotifications(c.id.toLowerCase());
      if ((c.unseen_reactions ?? 0) === 0) void closeNotifications(reactionTag(c.id));
    }
  }, [loading, conversations]);
  /* Looking at the requests answers their notifications. */
  useEffect(() => {
    if (tab === "contacts" && windowFocused) void closeNotifications("contacts");
  }, [tab, windowFocused]);

  /** Unread messages across chats; muted chats only when the badge setting counts them. */
  const unreadTotal = useMemo(
    () =>
      conversations.reduce((sum, c) => {
        if (!notificationPrefs.badgeIncludesMuted && isMuted(c.mute)) return sum;
        return sum + (c.unread_count ?? 0);
      }, 0),
    [conversations, notificationPrefs.badgeIncludesMuted],
  );
  useEffect(() => {
    // Not before the first list: an empty one would clear the badge a push just set.
    if (loading) return;
    showUnreadCount(notificationPrefs.badge ? unreadTotal : 0);
  }, [unreadTotal, notificationPrefs.badge, loading]);
  useEffect(() => () => hideUnreadCount(), []);

  /* The browser's own settings can change it while Shroud is in the background. */
  useEffect(() => {
    if (windowFocused) setPermission(notificationPermission());
  }, [windowFocused]);

  /* Each unlock: this browser's push subscription and settings, as the server should know them. */
  useEffect(() => {
    void syncNotifications(session.token).finally(() => setPermission(notificationPermission()));
  }, [session.token]);

  /* A click on a notification opens its chat (or the requests, for a contact request). */
  useEffect(
    () =>
      onNotificationOpen((open) => {
        // A ringing call's notification only brings Shroud forward: the call screen is up.
        if (open.kind === "call" || open.kind === "video_call") return;
        if (open.kind === "contact_request") {
          setTab("contacts");
          setSelected(null);
          return;
        }
        setTab("chats");
        if (open.peer) setPendingOpen(open);
      }),
    [],
  );
  useEffect(() => {
    if (!pendingOpen?.peer) return;
    const key = pendingOpen.peer.toLowerCase();
    const conv = conversations.find((c) => c.peer.id.toLowerCase() === key);
    const contact = contacts.find((c) => c.user_id.toLowerCase() === key);
    const username = conv?.peer.username ?? contact?.username;
    if (username) {
      setPendingOpen(null);
      setSelected({ id: conv?.peer.id ?? contact?.user_id ?? pendingOpen.peer, username });
    } else if (!loading) {
      // Not a chat of ours (any more): the list it would be in is open anyway.
      setPendingOpen(null);
    }
  }, [pendingOpen, conversations, contacts, loading]);

  /** Moves the open chat's catch-up cursor back, never forward; a catch-up running stops there. */
  const lowerReactionCursor = useCallback((to: number) => {
    if (reactionCursor.current != null && to < reactionCursor.current) reactionCursor.current = to;
    if (reactionFloor.current != null && to < reactionFloor.current) reactionFloor.current = to;
  }, []);

  /**
   * Opens reaction changes and folds them into the open chat. Changes for messages it does not
   * hold are skipped: those arrive with the message's own history page. Rejects, applying
   * nothing, when a sender's key is out of reach: the caller must not move past them.
   */
  const applyWireReactions = useCallback(
    async (wires: WireReaction[]) => {
      const material = loadIdentity(session.user.id);
      if (!material) return;
      const epoch = historyEpoch.current;
      const held = new Map(threadRef.current.map((m) => [m.id.toLowerCase(), m]));
      // Only the two people in the open chat react in it.
      const allowed = new Set([session.user.id.toLowerCase(), selectedRef.current?.id.toLowerCase() ?? ""]);
      const changes: { messageId: string; reaction: Reaction }[] = [];
      for (const wire of wires) {
        if (!allowed.has(wire.user_id.toLowerCase())) continue;
        const message = held.get(wire.message_id.toLowerCase());
        if (!message || message.deleted) continue;
        const reaction = await openReaction(
          wire,
          session.user.id,
          material,
          (id) => peerIdentityPublic(session.token, id),
          message.reactions,
        );
        changes.push({ messageId: wire.message_id, reaction });
      }
      if (epoch !== historyEpoch.current || changes.length === 0) return;
      setThread((prev) => applyReactionChanges(prev, changes));
    },
    [session.token, session.user.id],
  );

  /** Brings reactions on messages already on screen up to `seen` (a page's snapshot). */
  const syncReactions = useCallback(
    (peerId: string, seen: number | null) => {
      if (seen == null) return;
      if (reactionCursor.current == null) {
        reactionCursor.current = seen;
        return;
      }
      if (seen <= reactionCursor.current || reactionSync.current) return;
      const epoch = historyEpoch.current;
      reactionFloor.current = Number.POSITIVE_INFINITY;
      const run = (async () => {
        let after = reactionCursor.current ?? seen;
        // A long absence is walked a few pages per poll; the next one carries on.
        for (let page = 0; page < 5; page += 1) {
          const res = await api.reactionChanges(session.token, peerId, after);
          if (epoch !== historyEpoch.current) return;
          await applyWireReactions(res.reactions);
          if (epoch !== historyEpoch.current) return;
          after = res.next_seq;
          // A page or a failed tap that went back meanwhile keeps the cursor there.
          reactionCursor.current = Math.min(after, reactionFloor.current ?? after);
          if (!res.has_more) break;
        }
      })()
        .catch(() => {
          /* cursor kept (a sender's key out of reach, too): the next poll tries again */
        })
        .finally(() => {
          if (reactionSync.current !== run) return;
          reactionSync.current = null;
          reactionFloor.current = null;
        });
      reactionSync.current = run;
    },
    [session.token, applyWireReactions],
  );

  /**
   * Picking an emoji (bar, grid, chip): sets it, or takes it back when it already is ours.
   * Shown at once; one save per message at a time, taps meanwhile collapse into one more save
   * with the last choice. Each save is built on our record as last confirmed; when our other
   * device wrote in between, what we changed goes on top of its set. A failed save puts back
   * what the server holds.
   */
  const react = useCallback(
    (message: ChatMessage, emoji: string) => {
      const peer = selectedRef.current;
      const material = loadIdentity(session.user.id);
      if (!peer || !material) return;
      if (message.pending || message.failed || message.deleted || isUnsent(message)) {
        setReactionNotice({ id: Date.now(), text: "You can react once the message is sent." });
        return;
      }
      const me = session.user.id.toLowerCase();
      const key = message.id.toLowerCase();
      const live = threadRef.current.find((m) => m.id.toLowerCase() === key) ?? message;
      const current = live.reactions?.find((r) => r.userId === me) ?? null;
      const wanted = toggledReactions(emoji, emojisOf(me, live.reactions), reactionLimit.current);
      const inFlight = reactionConfirmed.current.has(key);
      if (!inFlight) {
        reactionConfirmed.current.set(key, current);
        reactionCursorAtTap.current.set(key, reactionCursor.current);
      }
      setThread((prev) =>
        withMyReaction(prev, key, me, { userId: me, emojis: wanted, seq: current?.seq ?? 0, pending: true }),
      );
      reactionIntents.current.set(key, wanted);
      if (inFlight) return;

      // Message ids are unique across chats: acting on the thread after a chat switch is a
      // no-op (`withMyReaction` finds nothing), and skipping it would leave the tap pending.
      void (async () => {
        const peerPub = await peerIdentityForSending(session.token, peer.id).catch((err: unknown) => {
          if (err instanceof Error && err.name === "PeerKeyChanged") setThreadError(err.message);
          return null;
        });
        /* Seqs and the notice belong to the chat the tap was made in. */
        const stillOpen = () => selectedRef.current?.id.toLowerCase() === peer.id.toLowerCase();
        const couldNotSave = () => {
          if (!stillOpen()) return;
          setReactionNotice({
            id: Date.now(),
            text: navigator.onLine ? "Couldn’t save your reaction." : "You’re offline. Your reaction wasn’t saved.",
          });
        };
        let rebases = 0;
        /* The set of a save whose answer never came (it may have landed all the same). */
        let unanswered: string[] | null = null;
        while (reactionIntents.current.has(key)) {
          const next = reactionIntents.current.get(key) ?? [];
          reactionIntents.current.delete(key);
          const base = reactionConfirmed.current.get(key) ?? null;
          try {
            if (!peerPub) throw new Error("no peer key");
            const result = await saveReaction({
              token: session.token,
              me,
              messageId: key,
              emojis: next,
              base,
              material,
              peerIdentityPublic: peerPub,
            });
            if ("saved" in result) {
              unanswered = null;
              rebases = 0;
              // 204 on a removal: the server held none, which is what we wanted.
              const confirmed = result.saved ?? (base ? { userId: me, emojis: [], seq: base.seq } : null);
              reactionConfirmed.current.set(key, confirmed);
              if (!reactionIntents.current.has(key)) {
                setThread((prev) => withMyReaction(prev, key, me, confirmed));
              }
              continue;
            }
            // Only our own record on this very message can be that answer.
            if (result.changed.user_id.toLowerCase() !== me || result.changed.message_id.toLowerCase() !== key) {
              throw new Error("409 names another record");
            }
            // Our other device wrote first: what we changed since `base` goes on top of its set,
            // so neither device's pick is lost.
            const now = await openReaction(result.changed, me, material, (id) =>
              peerIdentityPublic(session.token, id),
            );
            reactionConfirmed.current.set(key, now);
            // A save whose answer was lost may have landed: when the record is exactly what it
            // sent, it is ours, and what we changed since counts from there.
            const landed =
              unanswered != null &&
              unanswered.length === now.emojis.length &&
              unanswered.every((e, i) => e === now.emojis[i]);
            unanswered = null;
            const merged = rebasedReactions(
              reactionIntents.current.get(key) ?? next,
              landed ? now.emojis : base?.emojis ?? [],
              now.emojis,
              reactionLimit.current,
            );
            rebases += 1;
            const settled = merged.length === now.emojis.length && merged.every((e, i) => e === now.emojis[i]);
            if (settled || rebases > MAX_REACTION_REBASES) {
              reactionIntents.current.delete(key);
              setThread((prev) => withMyReaction(prev, key, me, now));
              if (!settled) couldNotSave();
            } else {
              reactionIntents.current.set(key, merged);
              setThread((prev) => withMyReaction(prev, key, me, { ...now, emojis: merged, pending: true }));
            }
          } catch {
            unanswered = next;
            if (reactionIntents.current.has(key)) continue;
            const back = reactionConfirmed.current.get(key) ?? null;
            setThread((prev) => withMyReaction(prev, key, me, back));
            // A change from our other device, ignored while this tap was pending, comes back
            // with the next catch-up.
            const atTap = reactionCursorAtTap.current.get(key);
            if (atTap != null && stillOpen()) lowerReactionCursor(atTap);
            couldNotSave();
          }
        }
        reactionConfirmed.current.delete(key);
        reactionCursorAtTap.current.delete(key);
      })();
    },
    [session.token, session.user.id, lowerReactionCursor],
  );

  /** Fetches the page before the oldest one loaded; a no-op once the start is reached. */
  const loadOlder = useCallback((): Promise<void> => {
    if (olderLoad.current) return olderLoad.current;
    const cursor = olderCursor.current;
    const open = selectedRef.current;
    const material = loadIdentity(session.user.id);
    if (!cursor || !open || !material) return Promise.resolve();
    const started = chatEpochOf(open.id);
    const epoch = historyEpoch.current;
    setLoadingOlder(true);
    const run: Promise<void> = loadHistoryPage(session.token, session.user.id, open.id, material, cursor)
      .then((page) => {
        // Decoded across a clear: the page's bodies and the preview it wrote have to go.
        if (dropClearedPage(open.id, started, page.messages)) return;
        if (epoch !== historyEpoch.current) return;
        olderCursor.current = page.older;
        setHasOlder(page.older !== null);
        // A change catch-up skipped while this page was in flight (its message wasn't here
        // yet) is not in the page either, nor is one the page couldn't open: go back for them.
        if (page.reactionSeq != null) {
          lowerReactionCursor(Math.min(page.reactionSeq, (page.reactionUnopened ?? Number.POSITIVE_INFINITY) - 1));
        }
        setThread((prev) => mergeMessages(prev, page.messages));
      })
      .catch(() => {
        /* cursor kept: the next scroll to the top tries again */
      })
      .finally(() => {
        if (olderLoad.current === run) olderLoad.current = null;
        if (epoch === historyEpoch.current) setLoadingOlder(false);
      });
    olderLoad.current = run;
    return run;
  }, [session.token, session.user.id, lowerReactionCursor]);

  useEffect(() => {
    const material = loadIdentity(session.user.id);
    if (!selected || !material) {
      setThread([]);
      setThreadError(material ? null : "Unlock this browser with your encryption phrase to read chats.");
      return;
    }
    const peerId = selected.id;
    let cancelled = false;
    let prefetchTimer = 0;
    const started = chatEpochOf(peerId);
    historyEpoch.current += 1;
    const epoch = historyEpoch.current;
    olderCursor.current = null;
    olderLoad.current = null;
    reactionCursor.current = null;
    setHasOlder(false);
    setLoadingOlder(false);
    setThread([]);
    setDraft("");
    setReplyTo(null);
    setThreadLoading(true);
    setThreadError(isPeerKeyBlocked(peerId) ? PEER_KEY_CHANGED : null);
    /* Only the newest page is decrypted before the chat shows. A few older pages follow
       in the background, one at a time with a pause between them so decrypting never
       competes with the reader; past that, pages load as they scroll up (`loadOlder`). */
    const prefetch = () => {
      if (cancelled || !olderCursor.current || threadRef.current.length >= PREFETCH_MESSAGES) return;
      prefetchTimer = window.setTimeout(() => void loadOlder().then(prefetch), PREFETCH_PAUSE_MS);
    };
    loadHistoryPage(session.token, session.user.id, peerId, material)
      .then((page) => {
        // A page read before the chat was deleted must not bring it back (`forgetChat`).
        if (dropClearedPage(peerId, started, page.messages)) return;
        if (cancelled || epoch !== historyEpoch.current) return;
        if (selectedRef.current?.id.toLowerCase() !== peerId.toLowerCase()) return;
        olderCursor.current = page.older;
        setHasOlder(page.older !== null);
        // A reaction the page couldn't open (its sender's key out of reach) comes via catch-up.
        reactionCursor.current =
          page.reactionSeq != null && page.reactionUnopened != null
            ? Math.min(page.reactionSeq, page.reactionUnopened - 1)
            : page.reactionSeq;
        // A message that arrived over the socket while the page was in flight stays.
        setThread((prev) => mergeMessages(page.messages, prev));
        setPreviewRev((n) => n + 1);
        prefetch();
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
      window.clearTimeout(prefetchTimer);
    };
  }, [selected?.id, session.token, session.user.id, loadOlder]);

  function chatEpochOf(peerId: string): number {
    return chatEpoch.current.get(peerId.toLowerCase()) ?? 0;
  }

  function bumpChat(peerId: string) {
    const key = peerId.toLowerCase();
    chatEpoch.current.set(key, chatEpochOf(key) + 1);
  }

  /** True when this chat was cleared after `started`. Drops the page's bodies and its preview. */
  function dropClearedPage(peerId: string, started: number, messages: { id: string }[]): boolean {
    if (chatEpochOf(peerId) === started) return false;
    for (const message of messages) discardMessage(message.id);
    replacePreview(session.user.id, peerId, null);
    setPreviewRev((n) => n + 1);
    return true;
  }

  /** The chat is gone for us: the open thread empties, and nothing this tab kept of it stays. */
  function forgetChat(peerId: string) {
    const key = peerId.toLowerCase();
    // A fetch already running still has the old bodies. A later one can show a new chat.
    bumpChat(key);
    // Its chat-list line quotes the newest message.
    replacePreview(session.user.id, key, null);
    setPreviewRev((n) => n + 1);
    if (selectedRef.current?.id.toLowerCase() !== key) return;
    // Pages still on their way were read before the delete; they are dropped when they land.
    historyEpoch.current += 1;
    olderCursor.current = null;
    setHasOlder(false);
    setLoadingOlder(false);
    // Bubbles still sending are newer than the delete, so they stay.
    for (const m of threadRef.current) if (!isUnsent(m)) discardMessage(m.id);
    setThread((prev) => prev.filter(isUnsent));
    setReplyTo((current) => (current && !isUnsent(current) ? null : current));
  }

  /**
   * What the peer sent reads "Message deleted" now; the rest of the chat stays. The poll only
   * adds new ids, so every page the open chat holds is fetched again and the server's copy wins
   * (`loadHistoryPage` forgets what this tab kept of each tombstone).
   */
  async function reloadChat(peerId: string) {
    // Every message of theirs is a tombstone, so a list line quoting one is stale.
    const preview = loadPreview(session.user.id, peerId);
    if (preview && !preview.isMine) {
      replacePreview(session.user.id, peerId, { ...preview, text: "Message deleted", failed: false });
      setPreviewRev((n) => n + 1);
    }
    const material = loadIdentity(session.user.id);
    if (!material || selectedRef.current?.id.toLowerCase() !== peerId) return;
    const started = chatEpochOf(peerId);
    const epoch = historyEpoch.current;
    const fetched = new Set<string>();
    let before: HistoryCursor | null = null;
    try {
      for (;;) {
        const page = await loadHistoryPage(
          session.token,
          session.user.id,
          peerId,
          material,
          before,
          OLDER_PAGE_SIZE,
        );
        // Cleared while the page was on its way: its bodies must not stay cached.
        if (dropClearedPage(peerId, started, page.messages)) return;
        // Another chat was opened while the page was on its way.
        if (!alive.current || epoch !== historyEpoch.current) return;
        const gone = new Set(page.messages.filter((m) => m.deleted).map((m) => m.id.toLowerCase()));
        setThread((prev) => mergeMessages(page.messages, prev));
        // The page's reactions replaced what these messages held: catch-up re-applies anything
        // newer than its snapshot, and a record it couldn't open.
        if (page.reactionSeq != null) {
          lowerReactionCursor(Math.min(page.reactionSeq, (page.reactionUnopened ?? Number.POSITIVE_INFINITY) - 1));
        }
        setReplyTo((current) => (current && gone.has(current.id.toLowerCase()) ? null : current));
        setPreviewRev((n) => n + 1);
        for (const m of page.messages) fetched.add(m.id.toLowerCase());
        // Done once the pages reach back to the oldest message the chat had loaded.
        const oldest = threadRef.current.find((m) => !isUnsent(m));
        if (!page.older || !oldest || fetched.has(oldest.id.toLowerCase())) return;
        if (Date.parse(page.older.createdAt) < Date.parse(oldest.createdAt)) return;
        before = page.older;
      }
    } catch {
      /* the pages that landed stay; reopening the chat loads the rest */
    }
  }

  /* Calls while unlocked. Unmounting (a lock, a sign-out) hangs up a call this device is in. */
  useEffect(
    () =>
      configureCalls({
        token: session.token,
        userId: session.user.id,
        deviceId: session.device.id,
        // A copy: the controller wipes it once the call's secret is derived.
        identity: () => {
          const material = loadIdentity(session.user.id);
          return material
            ? { privateKey: material.agreementPrivate.slice(), publicKey: material.agreementPublic.slice() }
            : null;
        },
        peerKey: (userId) => peerIdentityForSending(session.token, userId),
        peerName: (userId) => {
          const key = userId.toLowerCase();
          return (
            contactsRef.current.find((c) => c.user_id.toLowerCase() === key)?.username ??
            conversationsRef.current.find((c) => c.peer.id.toLowerCase() === key)?.peer.username ??
            null
          );
        },
      }),
    [session.token, session.user.id, session.device.id],
  );

  /* Right after a login or sign-up — and whenever this browser has no name its account can
     read — ask what to call it. One `/auth/me` per unlock. */
  const [namePrompt, setNamePrompt] = useState<{ initial: string; named: boolean } | null>(null);
  useEffect(() => {
    const material = loadIdentity(session.user.id);
    if (!material) return;
    let cancelled = false;
    currentDeviceLabel(session.token, session.device.id, material.historyKey)
      .then((label) => {
        if (cancelled || (label && !isFreshSignIn())) return;
        setNamePrompt({ initial: label?.name ?? suggestedDeviceName(), named: Boolean(label) });
      })
      .catch(() => {
        /* offline: the next unlock asks again if the name is still missing */
      });
    return () => {
      cancelled = true;
    };
  }, [session.token, session.user.id, session.device.id]);

  const saveThisDeviceName = useCallback(
    async (name: string, custom: boolean) => {
      const material = loadIdentity(session.user.id);
      if (!material) return;
      await saveDeviceName(session.token, session.device.id, material.historyKey, {
        name,
        kind: "web",
        custom,
      });
    },
    [session.token, session.user.id, session.device.id],
  );

  /* Signing out (or a session the server ended) hangs up before the browser is cleared. */
  useEffect(() => {
    if (wipe) endCallForLock();
  }, [wipe]);

  const callPeer = useCallback((peer: PeerRef, modality: CallModality) => {
    startCall({ id: peer.id, username: peer.username }, modality);
  }, []);

  useEffect(() => {
    const connection = connectRealtime({
      token: session.token,
      onFatalAuth: endSession,
      // A call's signaling stays up when the tab is in the background. Otherwise the socket
      // closes, and a message or a call arrives as a notification.
      keepWhenHidden: () => {
        const view = getCallView();
        return view != null && view.phase !== "ended";
      },
      onEvent: (event) => {
        if (event.type === "auth.ok" || event.type.startsWith("call.")) {
          // A reconnect may have missed a call's events; the calls read them back.
          handleCallEvent(event);
          return;
        }
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
            // Taken before any await, so a clear that lands while this is loading still counts.
            const clearedAt = new Map(chatEpoch.current);
            try {
              const convs = await refresh();
              const material = loadIdentity(session.user.id);
              if (!material || !alive.current) return;
              const peer = peerIdForMessage(dto, session.user.id, convs);
              const started = clearedAt.get(peer.toLowerCase()) ?? 0;
              // Cleared while the roster was loading. Decoding would cache the body.
              if (chatEpochOf(peer) !== started) return;
              const msg = await ingestIncoming(
                dto,
                session.user.id,
                peer,
                session.token,
                material,
              );
              if (dropClearedPage(peer, started, [msg])) return;
              // Their message is what the typing/recording was for: cleared in the same
              // update that adds it, so it lands where the activity bubble was.
              markTyping(dto.sender_user_id, false);
              markRecording(dto.sender_user_id, false);
              const open = selectedRef.current;
              setPreviewRev((n) => n + 1);
              if (open && open.id.toLowerCase() === peer.toLowerCase()) {
                setThread((prev) => mergeMessages(prev, [msg]));
              }
              if (msg.isMine) {
                // Written on our other device: that chat has been read there.
                void closeChatNotifications(dto.conversation_id);
              } else if (msg.kind !== "annotation") {
                const conv = convs.find((c) => c.peer.id.toLowerCase() === peer.toLowerCase());
                announce({
                  kind: "message",
                  tag: dto.conversation_id,
                  peer,
                  sender: conv?.peer.username ?? "New message",
                  text: previewCopy(msg),
                  muted: isMuted(conv?.mute),
                  lookingAtThis: lookingAtRef.current === peer.toLowerCase(),
                });
              }
            } catch {
              /* roster refresh already ran; next poll/WS event retries */
              markTyping(dto.sender_user_id, false);
              markRecording(dto.sender_user_id, false);
            }
          })();
          return;
        }
        if (event.type === "conversation.deleted") {
          void refresh();
          // From the peer, or from one of our other devices (never the one that deleted it).
          const initiator = String(event.raw.user_id ?? "").toLowerCase();
          const other = String(event.raw.peer_user_id ?? "").toLowerCase();
          if (!initiator || !other) return;
          const ours = initiator === session.user.id.toLowerCase();
          // As on iOS: our copy went when we deleted it (either scope), or when the peer deleted
          // it for both and we let contacts do that. Otherwise only what the peer sent is gone.
          if (ours || event.raw.cleared_for_peer === true) forgetChat(ours ? other : initiator);
          else void reloadChat(initiator);
          return;
        }
        if (event.type === "message.reaction") {
          // Never moves the catch-up cursor: an event lost from the socket queue must still
          // come back through catch-up, and applying one twice changes nothing (same seq).
          const wire = event.raw.reaction as WireReaction | undefined;
          if (!wire?.message_id) return;
          void applyWireReactions([wire]).catch(() => {
            /* the sender's key was out of reach: catch-up brings the change back */
          });
          // The other side reacted to one of our messages: seen if its chat is being read,
          // otherwise the chat list's heart badge is re-read. Taking back one emoji of several
          // is no news; a whole removal may still lower the count.
          const me = session.user.id.toLowerCase();
          const sender = String(event.raw.message_sender_id ?? "").toLowerCase();
          if (wire.user_id.toLowerCase() === me || (sender && sender !== me)) return;
          const added = event.raw.added !== false;
          if (!added && wire.ciphertext) return;
          const conversationId = String(event.raw.conversation_id ?? "").toLowerCase();
          const conv = conversationsRef.current.find((c) => c.id.toLowerCase() === conversationId);
          const looking = readingNowRef.current;
          if (looking && conv && conv.peer.id.toLowerCase() === looking) {
            if (added) markReactionsSeen(looking, wire.seq);
          } else {
            refreshSoon();
          }
          if (added && wire.ciphertext && conv) {
            announce({
              kind: "reaction",
              tag: reactionTag(conv.id),
              peer: conv.peer.id,
              sender: conv.peer.username,
              text: null,
              muted: isMuted(conv.mute),
              lookingAtThis: looking === conv.peer.id.toLowerCase(),
            });
          }
          return;
        }
        if (event.type === "conversation.read") {
          // Our other device read this chat: the count and its notifications go here too.
          const peer = String(event.raw.peer_user_id ?? "").toLowerCase();
          const readAt = Date.parse(String(event.raw.read_at ?? ""));
          const conversationId = String(event.raw.conversation_id ?? "");
          if (!peer) return;
          if (Number.isFinite(readAt)) {
            readThrough.current.set(peer, Math.max(readThrough.current.get(peer) ?? 0, readAt));
          }
          const left = Number(event.raw.unread_count ?? 0);
          setConversations((prev) =>
            withLocalState(
              prev.map((c) =>
                c.peer.id.toLowerCase() === peer ? { ...c, unread_count: Number.isFinite(left) ? left : 0 } : c,
              ),
            ),
          );
          if (conversationId) void closeChatNotifications(conversationId);
          return;
        }
        if (event.type === "conversation.mute") {
          // Muted or unmuted on our other device.
          const peer = String(event.raw.peer_user_id ?? "").toLowerCase();
          if (!peer) return;
          const mute = (event.raw.mute as ChatMute | null | undefined) ?? null;
          setConversations((prev) =>
            prev.map((c) => (c.peer.id.toLowerCase() === peer ? { ...c, mute } : c)),
          );
          return;
        }
        if (event.type === "reactions.seen") {
          // Another of our devices looked at this chat.
          const peer = String(event.raw.peer_user_id ?? "").toLowerCase();
          const seen = Number(event.raw.seen_seq ?? 0);
          if (!peer || !Number.isFinite(seen)) return;
          reactionsSeen.current.set(peer, Math.max(reactionsSeen.current.get(peer) ?? 0, seen));
          setConversations((prev) => withLocalState(prev));
          return;
        }
        if (event.type === "contact.request") {
          const request = event.raw.request as ContactRequest | undefined;
          if (
            request?.status === "pending" &&
            request.to_user_id.toLowerCase() === session.user.id.toLowerCase()
          ) {
            announce({
              kind: "contact_request",
              tag: "contacts",
              peer: request.from_user_id,
              sender: request.user?.username ?? "Someone",
              text: null,
              muted: false,
              lookingAtThis: false,
            });
          }
        }
        if (event.type.startsWith("contact.") || event.type === "message.deleted") {
          void refresh();
          const open = selectedRef.current;
          if (event.type === "message.deleted") {
            const id = String(event.raw.message_id ?? "");
            // A page notification may quote it (previews on): the chat's go with it.
            const conversationId = String(event.raw.conversation_id ?? "");
            if (conversationId) void closeChatNotifications(conversationId);
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
  }, [
    session.token,
    session.user.id,
    navigate,
    refresh,
    markTyping,
    markRecording,
    applyWireReactions,
    markReactionsSeen,
    refreshSoon,
    withLocalState,
  ]);

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
      // Captured before the request. Clearing this chat moves its epoch; switching chats does not.
      const started = chatEpochOf(peerId);
      const epoch = historyEpoch.current;
      fetchLatest(session.token, session.user.id, peerId, material, known)
        .then(({ messages: extra, reactionSeq, reactionUnopened }) => {
          if (cancelled) return;
          // Started before the clear. Drop the bodies this request just cached, including its preview.
          if (dropClearedPage(peerId, started, extra)) return;
          // Switched chats, or this one was reloaded: leave what the request cached.
          if (epoch !== historyEpoch.current) return;
          if (selectedRef.current?.id.toLowerCase() !== peerId.toLowerCase()) return;
          // A reaction on a new message whose sender's key was out of reach: catch-up retries.
          // With no cursor yet (the first page failed), it starts below that one.
          if (reactionUnopened != null) {
            if (reactionCursor.current == null && reactionSeq != null) {
              reactionCursor.current = Math.min(reactionSeq, reactionUnopened - 1);
            } else {
              lowerReactionCursor(reactionUnopened - 1);
            }
          }
          // Costs a request only when a reaction changed since the last one we applied.
          syncReactions(peerId, reactionSeq);
          if (extra.length === 0) return;
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
  }, [selected?.id, session.token, session.user.id, syncReactions, lowerReactionCursor]);

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
        // The open chat never shows one: it is being looked at.
        newReactions:
          (c.unseen_reactions ?? 0) > 0 && selected?.id.toLowerCase() !== c.peer.id.toLowerCase(),
        unread: c.unread_count ?? 0,
        muted: isMuted(c.mute),
      }))
      .filter(
        (entry) =>
          !q ||
          entry.username.toLowerCase().includes(q) ||
          entry.subtitle.toLowerCase().includes(q),
      );
  }, [conversations, presenceByUser, previewRev, query, session.user.id, typingPeers, recordingPeers, selected?.id]);

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
    // Locking by hand ends a call first (the automatic lock waits for it instead).
    endCallForLock();
    lockSession();
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
  // Decrypted photos, videos and transcripts live only as long as the unlocked shell does.
  useEffect(
    () => () => {
      forgetDecryptedState();
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
    // Only a preview that finished loading, for a link still in the text, goes along.
    const attachment = linkPreview.takeAttachment(text);
    const largeImage =
      attachment?.largeImage && attachment.largeImageWidth && attachment.largeImageHeight
        ? { bytes: attachment.largeImage, width: attachment.largeImageWidth, height: attachment.largeImageHeight }
        : null;
    // The big picture shows from the local bytes while it uploads (like a photo).
    if (largeImage) adoptImage(localId, largeImage.bytes, "image/jpeg");
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
      linkPreview: attachment?.preview ?? null,
      localLinkImage: Boolean(largeImage),
      imageWidth: largeImage?.width ?? null,
      imageHeight: largeImage?.height ?? null,
    };
    setThread((prev) => [...prev, optimistic]);
    setDraft("");
    setReplyTo(null);
    setSendingPeer(peerId);
    setThreadError(null);
    try {
      let msg: ChatMessage | null = null;
      if (attachment && largeImage) {
        try {
          msg = await sendLinkWithImage({
            token: session.token,
            me: session.user.id,
            peerUserId: peerId,
            material: identity,
            text,
            replyTo: reference,
            preview: attachment.preview,
            image: largeImage.bytes,
            width: largeImage.width,
            height: largeImage.height,
            placeholder: attachment.placeholder,
          });
          rekeyImage(localId, msg.id);
        } catch {
          // Keep the message, lose the big picture: the text send carries the small thumbnail.
          releaseImage(localId);
          setThread((prev) => prev.map((m) => (m.id === localId ? { ...m, localLinkImage: false } : m)));
        }
      }
      msg ??= await sendText({
        token: session.token,
        me: session.user.id,
        peerUserId: peerId,
        text,
        material: identity,
        replyTo: reference,
        linkPreview: attachment?.preview ?? null,
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
      setThreadError(sendFailureText(err, "Could not send."));
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
    // The note goes out without waiting for Whisper, so the other side can play it
    // right away; the transcript follows as an annotation (see below). Here it shows
    // on the bubble as soon as it lands, even while the upload is still running.
    if (!take.transcript) {
      void pending.then((text) => {
        if (!text || !alive.current) return;
        if (selectedRef.current?.id.toLowerCase() !== peerId.toLowerCase()) return;
        const id = noteId;
        setThread((prev) =>
          prev.map((m) => (m.id === id && !m.transcript ? { ...m, transcript: text, text } : m)),
        );
      });
    }
    try {
      const msg = await sendVoice({
        token: session.token,
        me: session.user.id,
        peerUserId: peerId,
        material,
        take,
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
      setThreadError(sendFailureText(err, "Could not send the voice message."));
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
        imageWidth: draft.width,
        imageHeight: draft.height,
        mediaBytes: draft.estimatedBytes ?? draft.probe.bytes,
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
          quality: job.draft.quality,
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

  // A share code reset in Settings shows at once; the stored session has it too (saveShareCode).
  const profile =
    newShareCode && newShareCode !== session.user.share_code
      ? { ...session, user: { ...session.user, share_code: newShareCode } }
      : session;
  const shareLink = shareUrl(profile.user.share_code);
  const selectedPresence = selected ? presenceByUser[selected.id.toLowerCase()] : undefined;
  const selectedActivity = selected
    ? activityFor(selected.id, typingPeers, recordingPeers)
    : undefined;
  const requests = incoming.map((request) => ({
    id: request.id,
    username: request.user?.username ?? "Unknown",
  }));
  const mutedChats = conversations
    .filter((c) => isMuted(c.mute))
    .map((c) => ({ peerId: c.peer.id, username: c.peer.username, mute: c.mute as ChatMute }));
  const selectedConversation = selected
    ? conversations.find((c) => c.peer.id.toLowerCase() === selected.id.toLowerCase())
    : undefined;
  /** Offered once, until notifications are on, turned down, or the offer closed. */
  const offerNotifications =
    tab === "chats" &&
    !notificationPrefs.enabled &&
    !notificationPrefs.offerDismissed &&
    (permission === "default" || permission === "granted");

  async function turnOnNotifications() {
    await enableNotifications(session.token).catch(() => undefined);
    setPermission(notificationPermission());
  }

  function openChatMenu(peerId: string, username: string, anchor: MenuAnchor, trigger: HTMLElement | null) {
    setChatMenu({ peerId, username, anchor, trigger });
  }

  return (
    <div className="shell">
      <Rail
        tab={tab}
        onSelect={openTab}
        requestCount={requests.length}
        unreadCount={unreadTotal}
        user={{ id: session.user.id, username: session.user.username }}
        onProfile={() => setShowProfile(true)}
        onLogout={() => setConfirmLogout(true)}
      />

      <div className="shell-body">
        {tab === "settings" ? (
          <SettingsPane
            session={profile}
            identity={identity}
            shareLink={shareLink}
            onLogout={() => setWipe("logout")}
            onSessionEnded={endSession}
            onLockNow={lockNow}
            onShowQr={() => setShowQr(true)}
            onCacheCleared={() => setPreviewRev((n) => n + 1)}
            onShareCodeChanged={setNewShareCode}
            mutedChats={mutedChats}
            onUnmute={(peerId) => setChatMute(peerId, "off")}
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
              onEntryMenu={
                tab === "chats"
                  ? (entry, event) =>
                      openChatMenu(entry.id, entry.username, { x: event.clientX, y: event.clientY }, event.currentTarget)
                  : undefined
              }
              banner={
                offerNotifications ? (
                  <div className="notify-offer" role="region" aria-label="Notifications">
                    <span className="notify-offer-icon" aria-hidden="true">
                      <Bell size={17} />
                    </span>
                    <div className="notify-offer-copy">
                      <strong>Get notified of new messages</strong>
                      <p>Even when this tab is closed or locked.</p>
                      <button type="button" onClick={() => void turnOnNotifications()}>
                        Turn on notifications
                      </button>
                    </div>
                    <button
                      type="button"
                      className="icon-btn"
                      aria-label="Not now"
                      title="Not now"
                      onClick={() => updateNotificationPrefs({ offerDismissed: true })}
                    >
                      <X size={15} />
                    </button>
                  </div>
                ) : null
              }
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
                hasOlder={hasOlder}
                loadingOlder={loadingOlder}
                onLoadOlder={loadOlder}
                error={threadError}
                onAcceptIdentity={
                  threadError === PEER_KEY_CHANGED
                    ? () => {
                        acceptChangedPeerKey(selected.id, session.user.id);
                        setThreadError(null);
                      }
                    : undefined
                }
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
                myName={session.user.username}
                onReact={react}
                reactionNotice={reactionNotice}
                linkPreview={linkPreview}
                onCall={(modality) => callPeer(selected, modality)}
                callsDisabled={callBusy || !identity}
              />
            ) : (
              <section className="thread thread-placeholder hidden-mobile">
                <div className="placeholder-card">
                  <BrandMark size={48} />
                  <strong>Select a conversation</strong>
                  <p>Your messages are end-to-end encrypted, on this device and on theirs.</p>
                </div>
              </section>
            )}
          </>
        )}
      </div>

      <TabBar tab={tab} onSelect={openTab} requestCount={requests.length} unreadCount={unreadTotal} />

      {chatMenu ? (
        // Its own layer: opened from the contact info it has to sit above the dialog.
        <div className="chat-menu-layer">
        <ChatMenu
          anchor={chatMenu.anchor}
          username={chatMenu.username}
          trigger={chatMenu.trigger}
          muted={isMuted(
            conversations.find((c) => c.peer.id.toLowerCase() === chatMenu.peerId.toLowerCase())?.mute,
          )}
          unread={
            conversations.find((c) => c.peer.id.toLowerCase() === chatMenu.peerId.toLowerCase())
              ?.unread_count ?? 0
          }
          onAction={(action) => runChatMenu(chatMenu.peerId, action)}
          onClose={() => setChatMenu(null)}
        />
        </div>
      ) : null}

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

      {showQr ? <MyQrSheet session={profile} onClose={() => setShowQr(false)} /> : null}

      {showProfile ? (
        <ProfileSheet
          session={profile}
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

      {namePrompt && !wipe ? (
        <DeviceNameDialog
          initial={namePrompt.initial}
          onSave={async (name) => {
            await saveThisDeviceName(name, true);
            clearFreshSignIn();
            setNamePrompt(null);
          }}
          onSkip={() => {
            // Unnamed is not an option: skipping keeps the guess, which the next prompt can replace.
            if (!namePrompt.named) void saveThisDeviceName(namePrompt.initial, false).catch(() => {});
            clearFreshSignIn();
            setNamePrompt(null);
          }}
        />
      ) : null}

      {wipe ? <DeviceWipeDialog session={session} reason={wipe} continued={wipe === "logout"} /> : null}

      {/* Full screen in a portal; minimized, a pill here (a bar at the top on phones). */}
      <CallOverlay />

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
            <div className="call-start">
              {(["voice", "video"] as const).map((modality) => (
                <button
                  key={modality}
                  type="button"
                  disabled={callBusy || !identity}
                  onClick={() => {
                    setShowInfo(false);
                    callPeer(selected, modality);
                  }}
                >
                  {modality === "voice" ? <Phone size={18} aria-hidden="true" /> : <Video size={19} aria-hidden="true" />}
                  {modality === "voice" ? "Call" : "Video"}
                </button>
              ))}
            </div>
          </div>
          <div className="set-card">
            {/* A mute shows in the chat list: without a chat yet there is none to show it in. */}
            <button
              type="button"
              className="set-row set-row-button"
              aria-haspopup="menu"
              disabled={!selectedConversation}
              onClick={(event) => {
                const rect = event.currentTarget.getBoundingClientRect();
                openChatMenu(selected.id, selected.username, { x: rect.right - 12, y: rect.bottom }, event.currentTarget);
              }}
            >
              <span className="set-tile" style={{ background: "#e64a72" }} aria-hidden="true">
                {isMuted(selectedConversation?.mute) ? <BellOff size={15} /> : <Bell size={15} />}
              </span>
              <span className="set-row-copy">
                <strong>Notifications</strong>
              </span>
              <span className="set-row-value">
                {selectedConversation ? muteLabel(selectedConversation.mute) : "After the first message"}
              </span>
            </button>
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
