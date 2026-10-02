# Shroud

## Keep the designs in sync with the UI

Every UI change — a new screen or component, a change to an existing one, or a removal — also
lands in the matching `.pen` file in the same piece of work:

| Code                | Design file              |
| ------------------- | ------------------------ |
| `ios/`              | `design/iOS-App.pen`     |
| `ios/` (iPad layout) | `design/iPad-App.pen`   |
| `web/`              | `design/webclient.pen`   |
| `android/`          | `design/Android-App.pen` |

A change that touches both clients updates both files. Removed UI is removed from the design too,
not left behind as a stale frame.

- Edit `.pen` files only through the Pencil MCP tools; never read or write them directly.
  Open the file in Pen first (`open -a /Applications/Pen.app design/<file>.pen`).
- Reuse the file's existing components and variables, and match the code: same copy, spacing,
  colours and states (empty, loading, error) as the implementation.
- Pencil edits live only in the running Pen app. When the design work is done, ask the user to
  press ⌘S in Pen and say plainly that the `.pen` change is not on disk until they do.
- Before committing a `.pen` file, check that its diff holds only this change — a save also
  writes other sessions' unsaved edits.
- If a UI change deliberately skips the design (a pure bug fix with no visible difference, say),
  say so in the summary instead of silently leaving it out.

## No deprecated APIs

Do not call, extend, or suppress a deprecated API, type, method, library, or language feature.
Search the change for `@Deprecated`, deprecation warnings, and `@Suppress` of `DEPRECATION` or
`OVERRIDE_DEPRECATION`.

- Replace each use with the current supported API.
- If the platform or library has no supported replacement, implement the behavior in the project.
- Do not add a new `@Deprecated` shim so callers can keep the old path.
- Do not silence a deprecation with `@Suppress` to leave the old call in place.

A method that is deprecated only from a newer SDK, while this repo's minimum SDK is lower, still
counts. Use the new API on versions that have it. On older versions, write the behavior without
that method when it can be done in-process. Keep a version check only when the operating system
exposes that behavior solely through the deprecated method and there is no in-process equivalent.
On those older versions, leave the compiler note. Do not suppress it.
