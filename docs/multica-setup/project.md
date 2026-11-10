# Project `Shroud`

Snapshot of `multica project get 87e4b813-d93b-4a0c-80a3-1da9c68242dc`, `multica project resource list 87e4b813-d93b-4a0c-80a3-1da9c68242dc` and
`multica project workflow get 87e4b813-d93b-4a0c-80a3-1da9c68242dc` on 2026-11-10.

| Field | Value |
| ----- | ----- |
| Title | `Shroud` |
| Icon | 🛡️ |
| Status | `in_progress` |
| Priority | `none` |
| Lead | [Docker] Development Manager - Henrik (agent) |
| Workflow | `Shroud delivery` (`uses_default: false`) |
| Resource | `github_repo` `https://github.com/AsP3X/shroud`, ref `dev`, default branch hint `dev`, label `shroud (dev)` |
| ID in this environment | `87e4b813-d93b-4a0c-80a3-1da9c68242dc` |

Start and due dates are unset.

## Description

Full text, unchanged.

~~~~ description
Shroud: end-to-end encrypted messenger. Rust/Axum/Postgres/Redis API in server/, operator console in admin/, web client in web/ (Vite + React), iOS/iPad in ios/ (SwiftUI, scheme shroud), Android in android/ (Kotlin + Compose, no Google services), Pencil designs in design/*.pen (source of truth per platform; edit only through the Pencil MCP tools with Pen.app open; the engineer calls the Pencil `save` tool and commits the file, with no ⌘S from the member).

Branches: dev is the working branch and the base of every worktree and feature branch. Those branches merge into dev with git after a clean review. master is the release line; agents never push to it or base work on it. A pull request exists only when the member asks, and it is dev → master. Fast-forward to origin/dev before the first edit and before the final suite (docs/agent-rules/worktrees-from-dev.md). Commits start with TASK:, FIX:, BUGFIX:, DOCS: or CHORE: and carry no AI attribution. No deprecated APIs.

Rules: AGENTS.md indexes docs/agent-rules/ (binding); .cursor/rules/*.mdc are the detailed conventions (crypto invariants, API error envelope, immutable migrations, data safety, definition of done, regression testing, design system, Swift quality). Plaintext and private keys exist only on devices; the server never gains a decryption path.

Mac runtime facts: `xcode-select -p` is `/Applications/Xcode.app/Contents/Developer` (do not set DEVELOPER_DIR unless it is not); Node 24 is on the default PATH (do not prepend Zed's Node); the Android SDK is at `~/Library/Android/sdk` (platform 37, NDK 30.0.16248370, CMake 4.1.2); start postgres:16-alpine and redis:7-alpine containers for the server tests (DATABASE_URL=postgres://shroud:shroud@localhost:5432/shroud, REDIS_URL=redis://localhost:6379).

Delivery: one issue, one feature branch, one delivery comment, then in_review. No pull request for that branch. A clean review with no faults merges the branch into dev with git and moves the card to done. The reviewer never pushes master. She moves an incomplete check to Device QA, failures back to in_progress, and a judgment to blocked. The member requests every pull request, always dev → master, and is the only one who merges master. Security-audit follow-ups start only when the member invokes them.
~~~~

The "Mac runtime facts" paragraph describes the member's MacBook: `xcode-select` already points at
Xcode.app, Node 24 is on the default PATH, the Android SDK is installed at `~/Library/Android/sdk`,
and the server tests use local Postgres and Redis containers. On a different machine, update that
paragraph and the matching sections of the engineer and reviewer instructions. The description
does not mention the Windows machine; the Demoncore agents carry its facts in their own
instructions (see [runtimes.md](runtimes.md)).

## Restore

Create the project once the agents exist (README step 4), so its new ID can go into `ids.sed`
before the instructions are set:

```sh
PROJECT_ID=$("$M" project create --title 'Shroud' --icon '🛡️' \
  --status in_progress --lead '[Docker] Development Manager - Henrik' \
  --description "$(extract project.md description)" --output json | jq -r .id)
"$M" project resource add "$PROJECT_ID" --type github_repo \
  --url https://github.com/AsP3X/shroud --ref dev --default-branch-hint dev --label 'shroud (dev)'
echo "s/87e4b813-d93b-4a0c-80a3-1da9c68242dc/$PROJECT_ID/g" >> ids.sed
```

Bind it to the workflow once the workflow exists (README step 8):

```sh
"$M" project workflow set "$PROJECT_ID" 'Shroud delivery' --dry-run
"$M" project workflow set "$PROJECT_ID" 'Shroud delivery'
"$M" project workflow get "$PROJECT_ID" --output json | jq '{uses_default, name: .workflow.name}'
```
