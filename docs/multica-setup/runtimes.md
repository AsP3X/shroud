# Runtimes

Snapshot of `multica runtime list --output json` on 2026-11-10. A runtime is registered by a running
daemon (`multica setup` / `multica daemon start` on that machine); it cannot be created from the CLI.
Its name, ID and version are this machine's labels and are not portable.

## Runtimes the team uses

| Kind | Name here | Provider | Device and CLI version here | Max concurrent tasks | Agents bound | ID in this environment |
| ---- | --------- | -------- | --------------------------- | -------------------- | ------------ | ---------------------- |
| Mac Claude | Claude (Niklass-MacBook-Pro.local) | `claude` | Niklass-MacBook-Pro.local · 2.1.310 (Claude Code) | 4 | `[Macbook] Senior Full-Stack Engineer - Vera`, `[Macbook] Full-Stack Engineer - Jonas`, `[Macbook] Full-Stack Engineer - Lena`, `[Macbook] Shroud Planner - Ida` | `6695f297-cc52-4985-a84c-5b2da735e3dc` |
| Mac Grok | Grok (Niklass-MacBook-Pro.local) | `grok` | Niklass-MacBook-Pro.local · grok 1.0.70 (f50904eaee6c) [stable] | 2 | `[Macbook] Code Reviewer - Nora` | `c3a174ed-3528-4230-beae-0925fdea641e` |
| Windows Claude | Claude (DESKTOP-BTQHK4R) | `claude` | DESKTOP-BTQHK4R · 2.1.305 (Claude Code) | unset | `[Demoncore] Full-Stack Engineer - Soren`, `[Demoncore] Full-Stack Engineer - Freja`, `[Demoncore] Senior Full-Stack Engineer - Saga`, `[Demoncore] Shroud Planner - Emil` | `e986e662-b600-4b45-bcdf-d902bb0f13f9` |
| Windows Grok | Grok (DESKTOP-BTQHK4R) | `grok` | DESKTOP-BTQHK4R · grok 1.0.70 (f50904eaee6c) [stable] | unset | `[Demoncore] Code Reviewer - Liv` | `fb19378c-3c0a-47f6-a014-3de3aad10f9f` |
| Docker Grok | Grok (Docker runner) | `grok` | Docker runner · grok 1.0.77 (50ca579fa996) | unset | `Mika`, `[Docker] Development Manager - Henrik` | `bb1e366e-6c6b-4815-af0a-18c732c93103` |

- **Mac Claude** — Claude Code on the member's MacBook, for squad `Shroud MacBook`. Needs Xcode
  (selected with `xcode-select`), Pen.app with the Pencil MCP server, Docker, the Rust toolchain,
  Node 24 on the default PATH and the Android SDK at `~/Library/Android/sdk` (platform 37, NDK
  30.0.16248370, CMake 4.1.2); see the project description. Every agent that touches `ios/` or edits
  `.pen` files runs here.
- **Mac Grok** — the Grok CLI on the same MacBook. Nora runs here to run the same suites as the Mac
  engineers, Android included, with the same toolchain; this runtime has no Pencil tools.
- **Windows Claude** — Claude Code on the Windows machine DESKTOP-BTQHK4R, for squad
  `Shroud Demoncore`. Needs Docker (for the server's Postgres and Redis test containers), the Rust
  toolchain, Node on PATH and the Android SDK (platform 37, NDK 30.0.16248370, CMake 4.1.2; Gradle
  through `gradlew.bat`). No Xcode and no Pen.app: these agents never build `ios/` or edit `.pen`
  files. The verification commands are in the "verification on Demoncore" section of each
  Demoncore agent's instructions.
- **Windows Grok** — the Grok CLI on the same Windows machine. Liv runs here to run the same suites
  as the Demoncore engineers with the same toolchain.
- **Docker Grok** — the Grok CLI in the Docker runner. Mika and Henrik only read and write the board,
  so they need no toolchain.

Concurrency: each agent's own limit is in its file under `agents/`. The runtime-wide limit caps all
agents on that runtime together: Mac Claude 4, Mac Grok 2, Windows Claude, Windows Grok and Docker
Grok none. The restore sets it with `multica runtime concurrency` (below).

## Other runtimes in this workspace

Registered, with no Shroud agent bound. A restore does not need them.

| Name | Provider | Device and CLI version | Max concurrent tasks | ID in this environment |
| ---- | -------- | ---------------------- | -------------------- | ---------------------- |
| Claude (Docker runner) | `claude` | Docker runner · 2.1.319 (Claude Code) | unset | `557344b0-b530-4388-b3c3-b606b1db2ae8` |
| Hermes (DESKTOP-BTQHK4R) | `hermes` | DESKTOP-BTQHK4R · Hermes Agent v0.20.0 (2026.8.3) | unset | `4c605e21-511c-46ff-a74c-a3312d099e4e` |
| Cursor (DESKTOP-BTQHK4R) | `cursor` | DESKTOP-BTQHK4R · 2026.04.14-ee4b43a | unset | `ef31e441-7314-40ed-baa8-707b09d2ba5e` |
| Openclaw (DESKTOP-BTQHK4R) | `openclaw` | DESKTOP-BTQHK4R · 2026.3.2 | unset | `fd29f4ca-0e70-4a23-8f4f-b54bf7bfacd3` |

## Restore

After each daemon is running and online, find its new ID and export it for the agent commands:

```sh
"$M" runtime list --output json | jq -r '.[] | "\(.id)  \(.provider)  \(.name)"'
export RT_MAC_CLAUDE='<id of the claude runtime on the Mac>'
export RT_MAC_GROK='<id of the grok runtime on the Mac>'
export RT_WIN_CLAUDE='<id of the claude runtime on the Windows machine>'
export RT_WIN_GROK='<id of the grok runtime on the Windows machine>'
export RT_DOCKER_GROK='<id of the grok runtime in the Docker runner>'
"$M" runtime concurrency "$RT_MAC_CLAUDE" 4
"$M" runtime concurrency "$RT_MAC_GROK" 2
```
