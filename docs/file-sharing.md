# File sharing

Sending documents, PDFs, text, Office files, image and video files in their original form, and
APKs, on iOS, iPadOS, the web and Android. Every client implements this page exactly: the wire
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
| `th` | Optional ≤ 6 KB JPEG preview (images, videos and PDFs, when the sender can make one cheaply; today iOS sends one, every client shows one). |
| `w`, `h` | Pixel size of `th`, or `0`. |
| `re` | The quoted message, as for every media kind. |

A payload with `t: "file"` is a file whatever its `mime` says: every reader checks `t == "file"`
**before** the `audio/` / `image/` / `video/` sniffing that old payloads rely on.

Builds from before this page read an unknown `t` by its MIME: a PDF or an Office file shows as a
photo placeholder with the caption, an `image/*` file as a photo, a `video/*` file as a video. None
of them can open the SHRF1 blob, so the update prompt (`GET /client-version`) is the way out.

**Reply quotes** use `k: "file"` with `x` = the file name. Old builds read an unknown `k` as text and
show `x`, which is the right fallback. The quote label is **File**.

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
| App | `apk` application/vnd.android.package-archive | app |

- The legacy binary Office formats (`doc`, `xls`, `ppt` and their templates) carry the macro warning
  too: they hold VBA without a telltale extension and are the classic macro-malware carrier.
- No SVG, HTML, scripts, executables or archives: nothing a viewer could run.
- Images and videos picked as photos/videos keep going out as `t: "image"` / `t: "video"`
  (compressed, metadata scrubbed). Picked **as files**, they go out untouched as `t: "file"` — the
  sender's metadata (EXIF, GPS) is sent as it is, which is why the file picker is the explicit
  "original" path and the composer says so (§7).

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
  multiple, the §4 types as `UTType`s, copied in with security-scoped access).
- Android: the attach sheet's **File** option opens `ACTION_OPEN_DOCUMENT` with the §4 MIME types,
  multiple.
- Web: the paperclip button (**Attach a file**) opens a file input whose `accept` lists the §4
  extensions. Below 900 px the paperclip is hidden and the **+** button (**Attach**) opens a menu:
  **Photo or Video** and **File**. Dropping or pasting files: images and videos keep their photo/video flow, any other
  §4 file goes to the file composer, the rest is refused with the toast below.

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
  `{done} of {total}`; a failed send `Not sent`; unsupported `Unsupported file`.
- Tap: downloads when needed, then opens (after §4's check and §6's dialog). Opening: iOS Quick Look;
  Android `ACTION_VIEW` through the decrypted-media provider (no app → **No app on this phone can
  open .{ext} files.**); web: PDFs, images and videos in a new tab, text in the in-app text viewer,
  everything else downloads.
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

- Chat list and notification preview: the caption, else the file name.
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
  `tmp/shroud-file-{id}/{name}` for Quick Look and removes the folder when the preview closes;
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
