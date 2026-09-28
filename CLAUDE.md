# Shroud

## Keep the designs in sync with the UI

Every UI change — a new screen or component, a change to an existing one, or a removal — also
lands in the matching `.pen` file in the same piece of work:

| Code                | Design file              |
| ------------------- | ------------------------ |
| `ios/`              | `design/iOS-App.pen`     |
| `web/`              | `design/webclient.pen`   |

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
