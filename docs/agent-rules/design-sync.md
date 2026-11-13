# Keep the designs in sync with the UI

**Applies to:** any change that adds, changes or removes visible UI in `ios/`, `web/` or `android/`.

Every UI change — a new screen or component, a change to an existing one, or a removal — also
lands in the matching `.pen` file in the same piece of work:

| Code                 | Design file              |
| -------------------- | ------------------------ |
| `ios/`               | `design/iOS-App.pen`     |
| `ios/` (iPad layout) | `design/iPad-App.pen`    |
| `web/`               | `design/webclient.pen`   |
| `android/`           | `design/Android-App.pen` |

A change that touches both clients updates both files. Removed UI is removed from the design too,
not left behind as a stale frame.

- Edit `.pen` files only through the Pencil MCP tools; never read or write them directly.
  Open the file in Pen first (`open -a /Applications/Pen.app design/<file>.pen`).
- Reuse the file's existing components and variables, and match the code: same copy, spacing,
  colours and states (empty, loading, error) as the implementation.
- Pencil edits live only in the running Pen app until they are saved. When the design work is
  done, confirm with the Pencil `get_app_state` tool that the open document is the file you
  changed, then save it yourself with the Pencil `save` tool. If the Pencil server offers no
  `save` tool, say so and say plainly that the `.pen` change is not on disk; never claim it was
  saved.
- After saving and before committing a `.pen` file, check that its diff holds only this change — a
  save also writes other sessions' unsaved edits.
- If a UI change deliberately skips the design (a pure bug fix with no visible difference, say),
  say so in the summary instead of silently leaving it out.
