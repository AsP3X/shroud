# Shroud web client

Desktop + mobile browser client. Visual source: `design/webclient.pen`.
Deploy: `./deploy.sh` / `.\deploy.ps1`.

## Key decisions

| Decision | Choice | Why |
| --- | --- | --- |
| Device model | First-class device (Telegram-style) | Server already has per-device identity keys, OTPKs, and a 5-device cap. The browser is a real device: username/password login, 12-word phrase unlock. |
| Crypto | TypeScript + WebCrypto, golden-tested against iOS vectors | Two implementations, one wire format. Shared WASM is a later unification, not v1. |
| At rest | One vault key seals everything (`crypto/vault.ts`); PIN or phrase opens it; auto-lock | Identity keys, ratchet sessions, message bodies, transcripts, chat previews, language statistics and the media cache are AES-256-GCM sealed in local storage / IndexedDB under keyed-hash names (no message or contact ids). The vault key is stored only wrapped: under PIN + a server-held pepper (the PIN guard, see [server-plan.md](./server-plan.md#pin-guard-web-vault): PBKDF2-SHA256 600k gives an auth key the server checks before releasing the pepper; 10 wrong PINs delete it) and under the phrase's history key ("Forgot PIN"). The session token is sealed too; `shroud.session` only says who is signed in. Locking — 5 minutes idle, the tab hidden, "Lock chats now", or any reload — drops the key and the token from memory. Pre-vault browsers are sealed on their first unlock (`crypto/vaultAccess.ts`). |
| Logout | Clear the browser, then prove it (`deviceWipe.ts`) | Local and session storage emptied, every IndexedDB database deleted, other tabs reload; a final check re-reads every store before the dialog says "clear". A tab closed mid-wipe is finished on the next load. Only the Whisper weights (public files) stay; the device-id anchor goes too, and at the 5-device cap the server hands the next login a device nobody is signed in on. A forced sign-out (401) clears the same way. |
| Layout | iOS light/dark tokens in a WhatsApp-Web three-pane | Rail + chat list + thread on desktop; stacked list/thread + tab bar on mobile. PWA-installable. |
| Hosting | `./deploy.sh` configures the public URL | Same-origin: web nginx reverse-proxies `/api/v1` (and WebSocket) to the API. iOS still talks to the API host. `WEB_PUBLIC_URL` also seeds CORS if someone splits origins. |
| v1 product | Chats, contacts, requests, Notes, media, notifications, privacy/devices/safety numbers | No calls in v1. Voice notes recorded in the browser are transcribed on-device (Whisper); transcripts sit behind the →A toggle in the voice bubble. |
| Replies | Quote sealed inside the plaintext (same `re` object as iOS); swipe left on touch, hover button with a mouse | The server never learns which message answers which. See [architecture.md](./architecture.md#sealed-plaintext-shapes). |
| Links | Found with the same rules as iOS (`links.ts`, shared test vectors); open in a new tab without a referrer. Previews render Telegram-style and are **built here too**: the composer shows one while you type, with Telegram's options (above/below the text, larger/smaller picture, remove) | A browser cannot fetch other sites (CORS, this app's CSP), so it speaks TLS itself in WebAssembly (`web/tls/`, `src/linkPreview/`) through `GET /api/v1/link-relay`. The server moves encrypted bytes and sees only the website's host; the website sees the server. A large picture is uploaded like a photo (`t:"link"`). Off switch: Settings → Privacy → Link previews. |
| Add contact | Paste invite / share code; optional webcam QR | Server has no username directory. |
| Notifications | Web Push through a service worker (`public/sw.js`) while the app is closed or locked; page notifications while it is unlocked but not watched; Settings → Notifications and Sounds | The server pushes only to devices without a live socket (a locked or closed tab has none), and the push service reads nothing: RFC 8291 encryption, with the sender's name inside and never the message. The open app can read the message, so its own notifications may show the text (off by default). A chat's messages collapse into one notification that counts them; its reactions get their own. Mutes are per account (chat menu, contact info); unread counts come from the server (tab title, favicon dot, app badge). Clicking a notification opens the chat, after the PIN if the vault is locked (the click waits in memory, never in storage). Safari on iPhone and iPad only pushes to a Home Screen web app; elsewhere the page says notifications work only while it is open. |
| Stack | Vite + React + TypeScript | Static SPA. No SSR (nothing to render server-side without plaintext). |

## Security (must match iOS on the wire)

- Server stores ciphertext envelopes only. Phrase never leaves the device.
- At rest, nothing account-related is readable without the PIN or the phrase — the session token included. A copy of the browser profile cannot be brute-forced offline: without the server's pepper the PIN wrap does not open, and every PIN guess is a request the server counts (10, then the pepper is gone). The PIN therefore needs the server to unlock; the phrase does not. What stays unsealed: who is signed in (user id, username, device id), the device anchor (until logout), and non-secret preferences (theme, link-preview switch, lock-on-hidden, notification settings in `shroud.notifications`). Someone holding both a profile copy **and** the server database could still brute-force the PIN offline.
- Identity TOFU + safety numbers: same `IdentitySafetyNumber` (SHA-256 of sorted X25519 pubs) as iOS. Sending blocks on `PeerIdentityError.changed` until the user accepts.
- This browser counts toward the 5-device cap. Settings can revoke other devices.

## Deploy

```bash
./deploy.sh            # wizard on first run, then compose up
./deploy.sh --status   # print web + API URLs
.\deploy.ps1           # Windows
```

`PROXY_MODE=local` publishes `:8081` (web) and `:8080` (API).
`PROXY_MODE=npm` joins `proxy-network`; point Nginx Proxy Manager at `shroud-web:80` and `shroud-api:8080`.

## PR plan

1. **Deploy + SPA shell** (this change) — compose overlays, wizard, nginx same-origin proxy, welcome/login/unlock/chats chrome.
2. **WebCrypto port** — BIP39 phrase, identity keys, X3DH + Double Ratchet, media AES-GCM, golden vectors from `ios/shroudTests`.
3. **Sealed IndexedDB + PIN vault** — history key wrap, idle lock wired to wipe RAM.
4. **Live messaging** — send/recv text, WS, receipts, Notes.
5. **Media** — photo / video / voice upload-download on the existing sealed blob path.
6. **Privacy** — blocks, delete chat, safety numbers, device list/revoke, identity-change banner.
7. **PWA polish** — service worker (no caching of `/api`), add-to-dock, responsive QA against `design/webclient.pen`.
8. **Notifications** — Web Push service worker, page notifications and sounds, mutes, server unread counts, Settings → Notifications and Sounds.
