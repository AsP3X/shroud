# File sharing

Sending documents, PDFs, text, Office files, audio, image and video files in their original form,
and APKs, on iOS, iPadOS, the web and Android. Every client implements this page exactly: the wire
shapes, the type table, the name rules and the copy are shared, and the vectors in §9 pin them.

The server needs nothing new. A file is a `content_type = media` message whose blob is opaque, and
`GET /media/{id}/content` always answers `application/octet-stream` with `nosniff`. Clients send
`content_type: "application/octet-stream"` (or nothing) on `POST /media/uploads`, so the server's
database never learns that a blob is a PDF or an APK.

## 1. Sealed payload: `t: "file"`

A `MediaMessagePayload` / `MediaPayload` (see [architecture.md](./architecture.md#sealed-plaintext-shapes)):

| Key | Meaning |
| --- | --- |
| `t` | `"file"` |
| `n` | The file name, already cleaned by §5 on the sender. Receivers clean it again. Required. |
| `mime` | The canonical MIME type of the name's extension from §4 (never the OS-reported one). |
| `k` | Base64 AES-256 key of the SHRF1 blob (§3). |
| `s` | Plaintext size in bytes. Required; receivers check it against the blob. |
| `c` | Optional caption. |
| `th` | Optional ≤ 6 KB JPEG preview (images, videos and PDFs, when the sender can make one cheaply; every client shows one). A PDF's `th` is the **top of its first page** in a 2:1 frame (§10.1); every client sends one for PDFs. An audio file's `th` is its **cover art**, square (§11.2). |
| `w`, `h` | Pixel size of `th`, or `0`. |
| `pg` | Optional page count of a PDF (integer ≥ 1), when the sender could read it. Absent on other types and on payloads from before §10. |
| `d` | Optional duration of an audio file in milliseconds (integer ≥ 1), when the sender could read it (§11.2). |
| `ti`, `ar` | Optional title and artist of an audio file from its tags, cleaned by §11.2 on both ends. |
| `re` | The quoted message, as for every media kind. |

A payload with `t: "file"` is a file whatever its `mime` says: every reader checks `t == "file"`
**before** the `audio/` / `image/` / `video/` sniffing that old payloads rely on.

Builds from before this page read an unknown `t` by its MIME: a PDF or an Office file shows as a
photo placeholder with the caption, an `image/*` file as a photo, a `video/*` file as a video. None
of them can open the SHRF1 blob, so the update prompt (`GET /client-version`) is the way out.

**Reply quotes** use `k: "file"` with `x` = the file name. Old builds read an unknown `k` as text and
show `x`, which is the right fallback. The quote label is **File**. Audio files quote as `k: "audio"`
with `x` = the display title of §11.2, labelled **Audio**.

## 2. Limits

- At most **2 GiB − 1 MiB** of plaintext per file (`VideoMedia.maxPlaintextBytes`,
  `MediaCrypto.MAX_PLAINTEXT_BYTES`, `MAX_VIDEO_BYTES`), so the sealed blob stays under the
  server's 2 GiB `MAX_MEDIA_BYTES`.
- Empty files are refused (the server takes no empty blob).
- At most **10 files** per send, like photos. The caption and the reply go on the first file only.
- No client ever holds a whole file in memory: §3 is built to be sealed and opened one 64 KiB
  segment at a time, from a file handle / `Blob.slice` / `ContentResolver` stream to a file handle /
  `Blob` parts / the SHRM1 cache.

## 3. Blob format: SHRF1

The same segmented construction as Android's local SHRM1 cache (`LocalMediaCache.kt`), keyed
directly with the message's fresh random `k`:

```
header  = "SHRF1" (5) ‖ noncePrefix (7, random) ‖ segmentSize u32 BE (65536)   // 16 bytes
nonce_i = noncePrefix ‖ u32 BE(i) ‖ (last ? 0x01 : 0x00)
ct_i    = AES-256-GCM(k, nonce_i, plaintext[i·64K ..< min((i+1)·64K, n)], aad = header) ‖ tag (16)
blob    = header ‖ ct_0 ‖ … ‖ ct_last
sealedSize(n) = 16 + n + 16 · max(1, ⌈n / 65536⌉)
```

- Every segment is a plain one-shot AES-GCM call (CryptoKit, WebCrypto, the JCA), so each client
  uses its hardware AES and never needs a streaming GHASH.
- The header is every segment's AAD; the index and the last flag sit in the nonce. Reordering,
  dropping, truncating, appending or splicing segments fails a tag.
- Readers refuse a blob unless: the magic is `SHRF1`; the segment size is exactly 65536; the blob
  length is `sealedSize(s)` for the payload's `s`; every tag checks; and exactly the final segment
  carries the last flag. A reader never shows, saves or hands on a byte before the last tag passed:
  it opens into a staging file / uncommitted cache writer / unpublished `Blob`, and commits only at
  the end.
- `k` is used for one file only. A retry either re-uploads the same sealed bytes (iOS, web) or
  seals again under a fresh key and prefix (Android); it never re-seals with the same key.

## 4. Supported types

The extension decides the type, on both ends. The sender's `mime` is informational only: a
receiver takes the type, the MIME it opens the file as, and the warning from this table, looked up
by the lowercased extension of the cleaned name. An extension not listed is **unsupported**: senders
refuse it, receivers show the bubble with **Unsupported file** and offer no download.

| Category | Extensions → MIME | Warning |
| --- | --- | --- |
| Text | `txt` text/plain · `csv` text/csv | — |
| PDF | `pdf` application/pdf | — |
| Word | `docx` application/vnd.openxmlformats-officedocument.wordprocessingml.document · `dotx` application/vnd.openxmlformats-officedocument.wordprocessingml.template · `rtf` application/rtf | — |
| Word | `doc` application/msword · `dot` application/msword · `docm` application/vnd.ms-word.document.macroEnabled.12 · `dotm` application/vnd.ms-word.template.macroEnabled.12 | macros |
| Excel | `xlsx` application/vnd.openxmlformats-officedocument.spreadsheetml.sheet · `xltx` application/vnd.openxmlformats-officedocument.spreadsheetml.template | — |
| Excel | `xls` application/vnd.ms-excel · `xlt` application/vnd.ms-excel · `xlsm` application/vnd.ms-excel.sheet.macroEnabled.12 · `xltm` application/vnd.ms-excel.template.macroEnabled.12 · `xlsb` application/vnd.ms-excel.sheet.binary.macroEnabled.12 | macros |
| PowerPoint | `pptx` application/vnd.openxmlformats-officedocument.presentationml.presentation · `ppsx` application/vnd.openxmlformats-officedocument.presentationml.slideshow · `potx` application/vnd.openxmlformats-officedocument.presentationml.template | — |
| PowerPoint | `ppt` application/vnd.ms-powerpoint · `pps` application/vnd.ms-powerpoint · `pot` application/vnd.ms-powerpoint · `pptm` application/vnd.ms-powerpoint.presentation.macroEnabled.12 · `ppsm` application/vnd.ms-powerpoint.slideshow.macroEnabled.12 · `potm` application/vnd.ms-powerpoint.template.macroEnabled.12 | macros |
| Image | `jpg` `jpeg` image/jpeg · `png` image/png · `gif` image/gif · `webp` image/webp · `heic` image/heic · `heif` image/heif · `avif` image/avif · `tif` `tiff` image/tiff · `bmp` image/bmp | — |
| Video | `mp4` video/mp4 · `m4v` video/x-m4v · `mov` video/quicktime · `webm` video/webm · `mkv` video/x-matroska · `avi` video/x-msvideo · `3gp` video/3gpp | — |
| Audio | `mp3` audio/mpeg · `m4a` audio/mp4 · `aac` audio/aac · `wav` audio/wav · `flac` audio/flac · `ogg` audio/ogg · `opus` audio/ogg · `aif` `aiff` audio/aiff | — |
| App | `apk` application/vnd.android.package-archive | app |

- The legacy binary Office formats (`doc`, `xls`, `ppt` and their templates) carry the macro warning
  too: they hold VBA without a telltale extension and are the classic macro-malware carrier.
- No SVG, HTML, scripts, executables or archives: nothing a viewer could run.
- Images and videos go out as `t: "image"` / `t: "video"` (compressed, metadata scrubbed) so they
  show in the chat — also when they are picked **as files**, dropped or pasted: the file pick hands
  every image and video the client's photo/video pipeline can decode to the photo or video compose
  (§7). Only one it can't decode (a TIFF or BMP the platform can't read, an MKV, AVI or WebM the
  platform can't play) goes out untouched as `t: "file"`, the sender's metadata (EXIF, GPS) and
  all, which is why the file composer says so (§7). Receivers keep showing image and video
  `t: "file"` messages as file bubbles.

### Content check before opening

Before a client hands a received file to any viewer (Quick Look, another app, a browser tab, the
in-app text viewer) it checks the first bytes; on a mismatch it does not open the file and says
**This file doesn't match its .{ext} type, so Shroud won't open it.** Saving and sharing stay
possible (they keep the warning of §6).

| Types | Check |
| --- | --- |
| `pdf` | `%PDF-` within the first 1024 bytes |
| `docx dotx docm dotm xlsx xltx xlsm xltm xlsb pptx ppsx potx pptm ppsm potm apk` | starts with `50 4B 03 04` (zip) |
| `doc dot xls xlt ppt pps pot` | starts with `D0 CF 11 E0 A1 B1 1A E1` (OLE) |
| `rtf` | starts with `{\rtf` |
| `txt csv` | no `00` byte in the first 8 KiB |
| `mp3` | starts with `ID3`, or `FF` then a byte with its top three bits set (`b1 & E0 == E0`, MPEG frame sync) |
| `aac` | starts with `ID3`, or `FF` then `b1 & F6 == F0` (ADTS) |
| `m4a` | `ftyp` at offset 4 |
| `wav` | `RIFF` at 0 and `WAVE` at 8 |
| `flac` | starts with `fLaC` or `ID3` |
| `ogg opus` | starts with `OggS` |
| `aif aiff` | `FORM` at 0 and `AIFF` or `AIFC` at 8 |
| images, videos | none (the platform decoders reject what they can't read) |

## 5. File names

The name is cleaned the same way on the sender (before sealing) and on the receiver (before
showing or saving), so a hostile sender can't smuggle a path, a hidden extension or a
right-to-left override (`invoice‮fdp.exe`). Code points, not UTF-16 units or graphemes:

1. Normalize to NFC.
2. Keep what follows the last `/` or `\`.
3. Drop U+0000–0008, U+000E–001F, U+007F–009F, U+00AD, U+061C, U+180E, U+200B–200F, U+202A–202E,
   U+2060–2064, U+2066–206F, U+2028, U+2029, U+FEFF, U+FFF9–FFFB.
4. Replace each of `< > : " | ? *` with `_`.
5. Turn each run of U+0009–000D, U+0020, U+00A0, U+1680, U+2000–200A, U+202F, U+205F, U+3000 into
   one U+0020.
6. Trim spaces and `.` from both ends.
7. The extension is what follows the last `.`, if that `.` is not the first character and the rest
   is 1–10 ASCII letters or digits. Lookups lowercase it; the name keeps its case.
8. If the name is longer than 120 code points, cut the stem to `120 − (ext + 1)` and trim spaces and
   dots from its end again.
9. An empty stem becomes `file`.

UI shows the name on **one line, truncated in the middle**, so the extension is always visible.

## 6. Warnings

Both warnings show on the bubble for sender and receiver. On a **received** file, every action that
puts the plaintext in reach of another program (open, open with, save, share, download) first asks:

| | Bubble line | Dialog title | Dialog message |
| --- | --- | --- | --- |
| app | **Installs an app** | **This file can install an app** | APK files install apps on Android. A harmful app can take over the phone and read your data. Only continue if you trust {sender} and expected this file. |
| macros | **May contain macros** | **This file may contain macros** | Macros in Office files can run harmful code. Only continue if you trust {sender} and expected this file, and don't turn on macros unless you're sure. |

Buttons: **Cancel** (default, cancel role) and **Continue** (destructive style). `{sender}` is the
name the chat's header shows for the contact. Each confirmation is for one action; nothing is
remembered. On iOS, Quick Look's own share button sits inside the preview the user just confirmed,
so the Open warning covers it too; the bubble menu's **Share** asks again.

A download alone (into the sealed cache) asks nothing: no other program can reach the file yet.

Android never installs an APK itself: the app doesn't hold `REQUEST_INSTALL_PACKAGES`, so an APK
offers **Save to Downloads** and **Share**, not **Open**.

## 7. UI

### Attach

- iOS / iPadOS: the attach sheet's **File** row opens the document picker (`.fileImporter`,
  multiple, the §4 types as `UTType`s). Nothing is copied at pick time: the picked files stay where
  they are, their security scope held until they are sealed (read under `NSFileCoordinator`, so a
  file provider downloads one then) or the composer drops them, so a 2 GB file opens its composer as
  fast as a small one.
- Android: the attach sheet's **File** option opens `ACTION_OPEN_DOCUMENT` with the §4 MIME types,
  multiple.
- Web: the paperclip button (**Attach a file**) opens a file input whose `accept` lists the §4
  extensions. Below 900 px the paperclip is hidden and the **+** button (**Attach**) opens a menu:
  **Photo or Video** and **File**. Dropping or pasting files: images and videos keep their photo/video flow, any other
  §4 file goes to the file composer, the rest is refused with the toast below.

A file pick is split: images and videos the photo/video pipeline can decode (iOS: ImageIO /
AVFoundation, Android: the preview decoder / the video probe, web: the browser's decoder or the
HEIC converter / the video worker's probe) go to the photo or video compose, as a photo-library pick
of them would; the other files go to the file composer. A pick holding both opens them one after
the other — videos, then photos, then the file composer — so nothing is dropped. Drops and pastes
on the web split the same way by type.

Then the **file composer** (a sheet on iOS/Android, a modal on the web) lists the files — tile,
name, `{size} · {TYPE}`, the warning line — with a caption field (**Add a caption…**) and **Send**.
Its title is **Send File** / **Send {n} Files**, and under the list it says **Files are sent as they
are, without compression, and keep their metadata.** Each row has a remove button (**Remove**).

Refusals, as a toast/banner, one per pick:

- **Shroud can't send “{name}”: this file type isn't supported.**
- **“{name}” is larger than 2 GB.**
- **“{name}” is empty.**
- **You can send up to 10 files at once.** (the first 10 are kept)

### The file bubble

```
┌────────────────────────────────────────┐
│ ┌──────┐  Quarterly report 2026.pdf    │   name: 15, medium, one line, middle-truncated
│ │  ↓   │  2.4 MB · PDF                 │   meta: 13, secondary
│ └──────┘  ⚠ May contain macros         │   warning: 12, warning text colour (only when §6 applies)
│ caption text, when there is one        │
│                               12:04 ✓✓ │
└────────────────────────────────────────┘
```

- Tile 44×44, corner radius 12, spacing 10 to the text. Bubble width 240–300.
- Tile fill: accent on incoming bubbles, white at 22 % on outgoing ones; glyph white. With a `th`,
  the tile shows it (aspect fill) under a 35 % black scrim.
- Tile glyph by state: **not on this device** → download arrow; **transferring** (either way) →
  progress ring with a stop glyph, tap cancels a download; **on this device** → the category glyph
  (text, PDF, Word, Excel, PowerPoint, image, video, app); **failed send** → retry arrow (tap
  retries where the platform has retry); **unsupported** → question mark on a neutral fill, no tap.
- Meta line: `{size} · {TYPE}` (`TYPE` = the extension upper-cased); while transferring
  `{done} of {total}`; a failed send `Not sent`; unsupported `Unsupported file`. A PDF whose page
  count is known leads with it: `12 pages · 2.4 MB · PDF` (`1 page · …`).
- A PDF with a preview gets the preview card above the row (§10.1); its tile then shows no `th`.
- Tap: downloads when needed, then opens (after §4's check and §6's dialog). Opening: **PDFs in
  Shroud's own PDF viewer on every client (§10.2)**; other types: iOS Quick Look; Android
  `ACTION_VIEW` through the decrypted-media provider (no app → **No app on this phone can open
  .{ext} files.**); web: images and videos in a new tab, text in the in-app text viewer, everything
  else downloads.
- Message menu adds, for file messages: iOS **Share** (after Copy); Android **Save to Downloads**
  and **Share** (after Copy Link; saves go to `Download/Shroud/`); web **Download** (supported types
  only). On Android a tap on an APK opens this menu, since an APK has no Open.
- On outgoing bubbles the warning line is drawn in white (iOS, Android) or `#f5d9a6` (web): the
  warning colours have too little contrast on the accent fill.
- A failed send keeps the platform's failed-media look: iOS and Android outline the bubble in
  danger and put the error and **Retry** under it; the web keeps the red bubble and retries on a tap
  of the tile, within the same tab session.
- Accessibility label: `File, {name}, {size}` plus `, installs an app` / `, may contain macros`.

Platform copy beyond the lines above: iOS and Android show the send error under a failed bubble
(**Waiting for connection…**, **Could not prepare that file.**); Android says **Saved to Downloads**
and **Could not save / share / open / download / read that file.**; the web says **This file
couldn’t be downloaded. Try again.**, **This file is damaged, so Shroud won’t open it.** and, when the
browser blocks the new tab, **The browser blocked the new tab — click the file again to open it.**

### Elsewhere

- Chat list and notification preview: the caption, else the file name (audio: **🎵 {display title}**,
  §11.2).
- Delete dialog: **This file** on the web, **Delete this file?** on iOS; Android's sheet names no subject.
- Copy (where offered) copies the caption.

### Web text viewer

A modal titled with the file name: the text in the mono font, as plain text (never HTML), at most
the first 1 MiB with **Showing the first 1 MB.** under it, and **Download** in the header.

## 8. Storage

No decrypted file stays on disk.

- **iOS**: the SHRF1 blob as it came from the server (or as it was sealed for sending) is kept at
  `Application Support/shroud/files/{message id}.shrf` (file protection `complete`, excluded from
  backup). Its key `k` lives only in the history-sealed payload cache, so the blob is as protected
  as every other sealed media file. Opening decrypts segment by segment into
  `tmp/shroud-file-{id}/{name}` for Quick Look or the PDF viewer and removes the folder when it
  closes;
  `SensitiveTempFiles` sweeps leftovers.
- **Android**: the file is streamed into the SHRM1 cache (copied in from the `ContentResolver` on
  send, from the SHRF1 download on receive). Open/share serve it through `DecryptedMediaProvider`
  as a seekable proxy file descriptor (`StorageManager.openProxyFileDescriptor`, which PDF and Office
  viewers need; a pipe is the fallback) with the cleaned name as `DISPLAY_NAME` and the size; **Save to Downloads** streams it into
  `MediaStore.Downloads`.
- **Web**: nothing is cached. Opening downloads, checks and decrypts into `Blob` parts; the last
  opened file stays in memory until the chat locks or another file opens.
- Delete-for-everyone, chat deletion, logout and wipes remove these files with the rest of the
  message's media.

## 9. Vectors

Run `node scripts/gen_file_vectors.mjs`; every client pins its output.

SHRF1 with `k = 00 01 … 1f`, `noncePrefix = a0 a1 a2 a3 a4 a5 a6`:

| Plaintext | Sealed |
| --- | --- |
| `"hello"` (5 B) | 37 B: `5348524631a0a1a2a3a4a5a6000100001f042572d7a195d96a199b78b66a618521c6b35bc9` |
| `i % 251` × 65536 | 65568 B, SHA-256 `440b505128af2e20c12d50b1009d4113697e1ecb8b9791171608c1e3fc83d74e` |
| `i % 251` × 65537 | 65585 B, SHA-256 `6caead1d0580c871ba5d374b0ba5ff1482ebaf769370a637c6597b832d5f3b08` |
| `i % 251` × 200000 | 200080 B, SHA-256 `b59a0b0206599c2fb139a31cc5a82d0feb6e8e6a715f5e4dbc03081275f924a5` |

Names: the `== names ==` block of the script's output (input → cleaned), all 20 rows.

Audio (§11): the `== audio tags ==` (tag text → cleaned, `null` = absent), `== audio titles ==`,
`== audio durations ==` and `== audio sniff ==` blocks, every row.

## 10. PDFs: preview card and viewer

PDFs get two things beyond the plain file bubble: a preview of the first page in the bubble, as
WhatsApp and Telegram show one, and Shroud's own viewer, so a PDF is read inside the app and its
plaintext never reaches another program unless the reader shares it.

### 10.1 Preview card

```
┌────────────────────────────────────────┐
│ ┌────────────────────────────────────┐ │   card: inset 4, radius 12, 2:1, white under the image
│ │ ▔▔▔▔▔▔▔▔▔▔▔▔▔▔                     │ │   the top of page 1, aspect fill, pinned to the top edge
│ │ ▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔           │ │   hairline: 0.5 black @ 10 % inside the edge
│ └────────────────────────────────────┘ │
│ ┌──────┐  Quarterly report 2026.pdf    │   the §7 row, unchanged; the tile shows no `th`
│ │  ↓   │  12 pages · 2.4 MB · PDF      │
│ └──────┘                               │
│ caption text, when there is one        │
│                               12:04 ✓✓ │
└────────────────────────────────────────┘
```

- Shown on a PDF bubble (sent or received, any state but unsupported) that has a preview image:
  the local render below, else `th`. Without either, the bubble is the plain §7 bubble (with the
  page count, when known).
- With the card, the bubble takes its full width cap (300, or less where the row is narrower).
- The card sits 4 from the bubble's top and sides (6 below a reply quote), corner radius 12, height
  = width ÷ 2. The image fills it, aspect fill, **aligned to the top**, so the page's top edge
  always shows (an older full-page `th` shows its top half). The card is white under the image,
  with a 0.5 hairline of black at 10 % just inside its edge. No scrim, no glyph: the row's tile
  keeps the state glyph on its normal fill.
- A tap on the card is a tap on the bubble (download when needed, then open; while downloading it
  stops the download, as the tile does).
- The card is hidden from accessibility; the bubble's label gains the page count:
  `File, {name}, {n} pages, {size}`.

**Sender `th` for a PDF.** Page 1 rendered on white at 480 px wide, its top 480 × 240 cut out
(the whole page, when it is wider than 2:1), JPEG. Shrink the width by 0.8 and lower the quality
until it fits 6 KB; give up below 160 px wide. `w`/`h` = its pixel size, `pg` = the page count.
Nothing (no `th`, no `pg`) for a PDF the platform can't parse, a password-protected one, or when
it takes longer than 2 s. iOS uses PDFKit/CoreGraphics, Android `PdfRenderer` on the picked
`ContentResolver` descriptor, the web pdf.js reading ranges of the picked `File` (never the whole
file into one buffer).

**Local render.** Where the PDF itself is at hand — on this device (iOS, Android), or decrypted in
memory on the web after the viewer opened it — the client draws the card from the file: page 1 at
the card's pixel size (card width × screen scale), cropped to 2:1 from the top. It is kept **in
memory only** (an LRU bounded to about 24 MB of pixels), never written to disk, and cross-fades over `th`
(150 ms) when ready. The same pass reads the page count when the payload has no `pg`. iOS renders
files up to 64 MB this way, decrypting into memory; Android reads through the proxy descriptor of
§8; the sender's own just-sent PDF renders from the picked file. A file that fails §4's check, is
password-protected or doesn't parse keeps `th`.

### 10.2 Viewer

Opens from a tap on a PDF bubble once the file is on hand and passed §4's `%PDF-` check (PDFs carry
no §6 warning). It replaces Quick Look (iOS), `ACTION_VIEW` (Android) and the new tab (web) for
PDFs only; the other types open as in §7.

```
┌──────────────────────────────────────────────────────────┐
│ (✕)(▯▯)       Quarterly report 2026.pdf           (⌕)(⇪) │  title: name, middle-truncated
│                 Page 3 of 12                             │  subtitle: live page / "Loading…"
├────────────┬─────────────────────────────────────────────┤
│  ┌──────┐  │    ┌──────────────────────────────┐         │  pages sidebar (wide): 200 wide,
│  │  1   │  │    │                              │         │  one column of thumbnails,
│  └──────┘  │    │          page 3              │         │  number under each
│     1      │    │                              │         │
│ ╔══════╗   │    └──────────────────────────────┘         │  canvas: light #ECECF0, dark #111113
│ ║  3   ║   │    ┌──────────────────────────────┐         │  pages: white, radius 2, soft shadow,
│ ╚══════╝   │    │          page 4              │         │  12 apart, fit to width (12 margin),
│     3      │    │                              │         │  at most 920 wide
└────────────┴─────────────────────────────────────────────┘
```

**Chrome.** Top bar: **Close** (✕) leading; title = the file name, subtitle = **Page {n} of {count}**
(the page filling most of the viewport; **1 page** for a single page; **Loading…** until the
document is parsed); **Pages** (sidebar glyph) right after Close, so it sits above the sidebar it
opens; trailing **Search** (⌕) and the platform's file action:
iOS **Share** (the share sheet on the open file), Android **More** (⋮: **Share**, **Save to
Downloads**, **Open in Another App**), web **Download**. Each platform draws it in its own viewer
idiom (iOS: the Liquid Glass navigation bar, the title in its own glass pill as in the photo
viewer, so it reads over a white page in dark mode; Android: the media viewer's chrome; web: the
modal header). On touch screens a single tap on a page (not on a link) hides or
shows the chrome (fade, 180 ms); the web keeps it.

**Reading.** Pages scroll vertically, continuously. Zoom 1× (fit width) to 6×: pinch, trackpad
pinch or Ctrl/⌘ + wheel; a double tap toggles 1× ↔ 2.5× around the tap. Once a zoom settles, the
visible pages are drawn again at the new scale, so text is never left as a blurry upscale. The web
adds **Zoom out** / **Zoom in** buttons around the percentage in the header and Ctrl/⌘ `+` `−` `0`.

**Pages sidebar.** The page overview is a **vertical list on the left**, as in Preview or
Acrobat: one column of page thumbnails, each with its number under it.

- *Wide windows* (iPad and iPhone in regular width, the web from 900 px, Android from 840 dp): a
  sidebar **200 wide** beside the canvas, which shrinks to make room (the zoom stays relative to
  the fit). It is **open by default** for a document of more than one page; **Pages** toggles it
  and the choice holds for the rest of the session.
- *Narrow windows* (phones, the web below 900 px): the same list as a **drawer from the left**,
  `min(280, 80 %)` wide, over a 30 % black scrim; a tap on the scrim, a swipe to the left, Escape
  or picking a page closes it.
- *Motion*: the sidebar and the drawer **slide in from the left and back out**, 280 ms on
  `cubic-bezier(0.32, 0.72, 0, 1)` (the scrim fades with them); a drawer closed part-way in or
  mid-swipe leaves from where it is. With reduced motion they appear and go at once. While the
  sidebar slides, the pages move and scale with it as a transform only — iOS a Core Animation
  layer transform, the web a CSS transform — and are laid out at the new width once (pdf.js
  re-fitting 500 pages costs ~11 ms a frame); Android re-measures the row each frame without
  composing the viewer again.
- Sidebar fill: light `#F7F7F9`, dark `#1A1A1D`, a 1 px separator on its right edge (the drawer
  has a shadow instead). The list is padded 16 at the top and bottom, rows 20 apart.
- Thumbnail: the page's own aspect at **128 wide** (a landscape page is shorter; no page is
  taller than 182), white, radius 2, a 0.5 hairline and a soft shadow; the number 12 under it in
  the secondary text colour, 6 below.
- Current page: a **2 px accent outline 3 outside** the thumbnail and its number in the accent
  text colour, semibold. As the reader scrolls the document, the list keeps the current page in
  view (scrolling only when it leaves the visible part, animated); while the reader is scrolling
  the list itself, it doesn't follow.
- A tap on a thumbnail jumps to that page's top (the drawer then closes; the sidebar stays).
- Accessibility: each row is a button, `Page {n} of {count}`, selected for the current page; the
  list is labelled **Pages**.
- Performance: thumbnails render lazily, only for the rows in view and a few ahead, at 128 × the
  screen scale, off the main thread, two at a time; renders for rows that scrolled away are
  cancelled; finished thumbnails stay in a bounded cache (about 12 MB) for the viewer's life, so
  reopening the drawer or toggling the sidebar never renders them again. Rows are laid out from
  the page sizes alone, so a 500-page PDF scrolls its list immediately and never renders 500
  thumbnails.

**Search.** The search button turns the title into a field (**Search in PDF**), with **{i} of {n}**,
**Previous result** / **Next result** and **Done**; Return goes to the next match. Matching ignores
case and diacritics; every match is highlighted (accent at 30 %), the current one stronger (accent at
60 %) and scrolled into view. **No results** when none. iOS (PDFKit) and the web (pdf.js) search
everywhere; Android only where `PdfRenderer` has text search (API 35+) and hides the button below.

**Text and links.** iOS and the web let the reader select and copy text; Android draws pages as
images. Links inside the PDF: a link to a page of the document jumps there; an `http`, `https` or
`mailto` link goes through the client's usual way of opening a link from a message; any other scheme
does nothing. Android follows no links.

**States.**

- *Loading*: a spinner on the canvas until the document is parsed; where pages are laid out before
  they are drawn (Android, web), white placeholders at each page's aspect (else A4) until they are.
- *Password*: a lock glyph, **This PDF is protected**, **Enter its password to open it.**, a
  secure field (**Password**) and **Open**; a wrong one says **Wrong password. Try again.** iOS,
  the web and Android 15+ (API 35, `PdfRenderer` with `LoadParams`) unlock in place. Below API 35,
  Android shows **This PDF is protected with a password. Open it in another app to read it.** and
  **Open in Another App**.
- *Damaged*: **Shroud can't show this PDF.** / **It may be damaged or use features Shroud can't
  display.** with the platform's file action (iOS **Share**, Android **Open in Another App**, web
  **Download**).

**Keys** (web; iPad with a keyboard where free): Escape closes search, then the pages drawer, then the viewer; Ctrl/⌘ F
opens search; ← → PageUp PageDown go a page back or on; Home/End go to the first/last page.

**Session memory.** Reopening a PDF in the same session goes back to the page it was left on (kept
in memory per message, dropped on lock).

**Closing.** Close, Escape, the system back (Android), VoiceOver's escape gesture (iOS). Deleting the
message closes it; locking the chats closes it and frees everything (iOS removes the staged plaintext
of §8 at once).

**Performance.** Only the pages in view and one either side are rendered, at screen scale × zoom,
off the main thread; renders of pages that scrolled away are cancelled; bitmaps are kept in a
bounded cache (pdf.js's own page buffer on the web, PDFKit's tiles on iOS, about six pages on
Android). Opening a 500-page PDF parses it and draws the first page — it never draws 500.

**Where the plaintext is.** iOS: PDFKit reads the staged file of §8 (`tmp/shroud-file-{id}/`),
removed when the viewer closes. Android: `PdfRenderer` reads a seekable proxy descriptor
(`StorageManager.openProxyFileDescriptor`) that decrypts SHRM1 segments on demand, so no plaintext
touches the disk. Web: pdf.js reads the decrypted `Blob` through ranged slices
(`PDFDataRangeTransport`), runs in its worker with `isEvalSupported: false`, and loads its fonts,
CMaps and WebAssembly from Shroud's own origin (the CSP allows nothing else).

**Accessibility.** Controls are labelled as above. iOS: PDFKit exposes the text to VoiceOver.
Android and the web: each page is one element, `Page {n} of {count}`; on the web the text layer
is readable.

## 11. Audio files

Songs, podcasts and recordings sent as files play in the chat: a round play button with the cover
art, the title and artist, and a scrubber, as WhatsApp and Telegram show music. They stay
`t: "file"` messages — SHRF1 blob, 2 GB limit, storage, delete, save and share all as §1–§8 — so a
build without this section shows **Unsupported file** instead of a broken voice bubble (an unknown
`t` with an `audio/` MIME would read as a voice note). Voice notes are unchanged.

Designs: the **Conversation · Audio Files** sections of `design/iOS-App.pen`, `iPad-App.pen`,
`webclient.pen` and `Android-App.pen` (components *Audio Bubble / In · Out*, *AudioBubbleIn · Out*,
*NowPlayingBar*).

### 11.1 Scope

- The types are the **Audio** row of §4; the sender reads the extension as for every file.
- Audio picks are never transcoded and never go to the photo/video compose: they go to the file
  composer with the other files. Like every file they keep their metadata (the composer says so).
- There are no lock-screen, media-notification or Media Session controls yet, and iOS and Android
  pause when the app leaves the foreground (§11.5).

### 11.2 Payload

On top of §1, the sender adds what it could read **within 2 s** (else it sends the file without):

- `d`: the duration in ms, rounded, ≥ 1.
- `ti`, `ar`: the title and artist tags, cleaned as below; absent when empty.
- `th`, `w`, `h`: the embedded cover art, centre-cropped to a square, scaled to 160 px, JPEG.
  Shrink by 0.8 and lower the quality until it fits 6 KB; give up below 64 px (no `th`).

Where the sender reads them: iOS `AVURLAsset` (`load(.duration)`, common metadata
`.commonIdentifierTitle` / `.commonIdentifierArtist` / `.commonIdentifierArtwork`); Android
`MediaMetadataRetriever` on the picked URI (`METADATA_KEY_DURATION`, `_TITLE`, `_ARTIST`,
`embeddedPicture`); the web reads the duration from an `<audio preload="metadata">` on the
`File`'s object URL and the tags with its own parser over `Blob.slice` ranges, never the whole file:
ID3v2.2–2.4 (`TT2`/`TIT2`, `TP1`/`TPE1`, `PIC`/`APIC`), MP4 `moov/udta/meta/ilst`
(`©nam`, `©ART`, `covr`) found by walking box headers, and FLAC `VORBIS_COMMENT` (`TITLE`,
`ARTIST`) and `PICTURE` blocks.

**Tag text** (`ti`, `ar`) is cleaned on the sender before sealing and again on the receiver:
NFC; drop the §5 step-3 code points; turn each run of §5 step-5 spaces into one U+0020 (none at the
start); cut to 200 code points; trim trailing spaces; empty → absent.

**Display title**: `{ti} – {ar}` (U+2013 with spaces) when both are present, else `ti`, else the
cleaned file name. It is what the reply quote's `x`, the chat list and the notification use.

**Durations** show as `m:ss`, from one hour `h:mm:ss`: a total (`d`) rounds to whole seconds, an
elapsed time floors.

### 11.3 Composer

The §7 file composer lists an audio file as a row with a **44 round cover** (`th`, else the accent
circle with a music-notes glyph), the title (`ti`, else the name, middle-truncated) and
`{ar} · {duration} · {size} · {EXT}`, leaving out what is missing. Nothing else changes: the title
(**Send File** / **Send {n} Files**), the note, the caption, the 10-file limit, the refusals.

### 11.4 The audio bubble

```
┌────────────────────────────────────────┐
│ ╭──────╮  Midnight City                │   title 15 medium, one line
│ │  ▶   │  M83 · 4:03                   │   detail 13, tabular digits, as the §7 meta line
│ ╰──────╯  ━━━━━━━━●──────────────      │   scrubber: only on the active file
│ caption, when there is one             │
│                               18:09 ✓✓ │
└────────────────────────────────────────┘
```

The §7 file bubble's shell (width, corners, padding, caption, footer, failed look) with a **44 round
cover** in place of the tile:

- Fill: accent on incoming bubbles, white at 22 % on outgoing ones. With `th`, the cover fills it
  (aspect fill) under a 35 % black scrim.
- Glyph (white, 18): **not on this device** → download arrow; **transferring** either way →
  progress ring with a stop glyph; **on this device** → play, or pause while it plays; **failed
  send** → retry arrow.
- Title: `ti`, else the file name (middle-truncated, §5).
- Detail line, by state:
  - transferring: `{done} of {total}`;
  - failed send: **Not sent**;
  - the **active** file (playing, or paused part-way): `{elapsed} / {duration}`;
  - otherwise with `ar`: `{ar} · {duration}`, plus ` · {size}` while not on this device;
  - otherwise: `{duration} · {size} · {EXT}`.
  A missing duration drops its part.
- The detail line takes the §7 meta line's colours (secondary; on outgoing bubbles white 65 % on
  iOS and Android, white 78 % on the web).
- Scrubber, only on the active file: a 3 pt track (accent / white played; the rest accent 25 % on
  iOS, `#8E8EA0` at 30 % on Android and the web, white 30 % on outgoing bubbles) with a 10 pt
  thumb. Dragging or tapping it seeks; the web uses a range input (←/→ seek 5 s).
- Tap on the bubble (not on the scrubber): not on this device → download, then play when it
  finishes, unless something else started playing meanwhile or the chat closed; transferring → stop
  the transfer where the platform can (the web can't stop an upload, so an uploading bubble takes
  no tap, as its file bubble); on this device → play / pause.
- Accessibility label: `Audio, {title}, {artist}, {duration}` without the missing parts; the
  play/pause action is the bubble's default action.
- The message menu is the §7 file menu (Share, Save to Downloads, Download …).

**Can't play here.** Whether a file plays is decided by the platform player, not by this page. A file
it can't open (Ogg Vorbis on iOS, AIFF where ExoPlayer has no extractor, a codec the browser
lacks — on the web known from `canPlayType` before a download) is drawn as the plain §7 file bubble
with a music-notes glyph, `{size} · {EXT}` and the line **Can't play on this iPhone** / **Can't play
on this iPad** / **Can't play on this phone** / **Can't play in this browser** (secondary colour,
info glyph) in place of a warning. Its tap does what a §7 file tap does. A file that fails §4's
content check shows that toast and is never handed to the player.

### 11.5 Playback

- **One sound at a time.** Starting an audio file stops a playing voice note and the other way
  round; a call stops both.
- **Plaintext.** iOS plays the `.shrf` blob of §8 through `AVPlayer` with an
  `AVAssetResourceLoaderDelegate` that decrypts the SHRF1 segments the player asks for; before the
  first play in a session the whole blob is checked once (every tag, the last flag, the length) and
  the result is kept in memory per message, so §3's rule holds. Android plays the SHRM1 cache
  through `SealedMediaDataSource` into ExoPlayer. The web opens the file as for §7 (a verified,
  decrypted `Blob`), plays it from an object URL and revokes it when another file starts, the chat
  closes or the chats lock. No platform writes decrypted audio to disk.
- **The end of a file** rewinds it and plays the next audio file below it in the chat (either
  sender) if it is on this device and playable; otherwise playback stops.
- **Stops** when the conversation closes, the chats lock, the message is deleted, or a call starts.
  iOS and Android also **pause** when the app leaves the foreground and on an audio interruption;
  the web keeps playing in a hidden tab until the chats lock.
- **Speed**: 1× → 1.5× → 2×, from the now-playing bar's chip, shown only for files of 10 minutes or
  more; it holds for the session.

### 11.6 Now-playing bar

While an audio file is active (playing or paused part-way) and its bubble is not on screen, the
conversation shows a bar under its header:

| | iPhone / iPad | Android | Web |
| --- | --- | --- | --- |
| Shape | Liquid Glass capsule, 48 tall, 16 from the sides, 8 under the chat bar | card, 52 dp, radius 16, bubble-in fill, elevation 2, 16 from the sides | 44 px strip across the thread, under its head, elevated background |
| Content | play/pause (32 accent circle), title 14 medium over `{ar} · {elapsed} / {duration}` 12 secondary, speed chip, close ✕ | same | play/pause (28), title 14/500 then the artist 13 secondary on one line, `{elapsed} / {duration}` 12, speed chip, close ✕ |

A 2 pt accent progress line runs along its bottom edge. A tap on the text scrolls to the bubble;
close stops playback. Voice notes don't get the bar. Labels: **Play**, **Pause**, **Playback speed**
(value `1×`, `1.5×`, `2×`), **Stop**.
