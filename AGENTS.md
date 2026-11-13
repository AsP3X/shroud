# Shroud — instructions for coding agents

This is the single source of the project rules for every coding agent. Claude Code reads it through
`CLAUDE.md`; other agents read it directly.

## Rules

Every rule is mandatory. Each one lives in its own file under `docs/agent-rules/`. Before you start
work, read the full file of every rule that applies to it — the summaries below are an index, not
the rule.

| Rule | Applies to | Summary |
| ---- | ---------- | ------- |
| [Keep the designs in sync with the UI](docs/agent-rules/design-sync.md) | Visible UI changes in `ios/`, `web/`, `android/` | Every UI change also lands in the matching `design/*.pen`, edited only through the Pencil MCP tools. |
| [No deprecated APIs](docs/agent-rules/no-deprecated-apis.md) | Every code change | Don't call, extend or suppress deprecated APIs; replace them or implement the behavior. |
| [No AI attribution](docs/agent-rules/no-ai-attribution.md) | Every commit and pull request | No `Co-Authored-By` trailer or "Generated with …" footer for an AI assistant. |
| [Worktrees start from an up-to-date dev](docs/agent-rules/worktrees-from-dev.md) | Every git worktree an agent creates or works in | Base worktrees on `dev`, fast-forward to `origin/dev` before the first edit, and catch up again before testing and merging back. |

## Conventions

The detailed conventions live in `.cursor/rules/`, one `.mdc` file each. They are binding too. Read
the full file of every convention that applies to your change. Where a convention disagrees with
a rule above, the rule in `docs/agent-rules/` wins.

| Convention | Applies to | Summary |
| ---------- | ---------- | ------- |
| [Agent non-negotiables](.cursor/rules/agent.mdc) | Every change | End-to-end encryption, the design files, data safety, git and the definition of done. |
| [API errors and logging](.cursor/rules/api-error-shape.mdc) | Server and client API code | The JSON error envelope, and what goes to logs versus clients. |
| [Immutable sqlx migrations](.cursor/rules/api-sqlx-migrations.mdc) | `server/migrations/` | Never edit an applied migration; add a new numbered file. |
| [Data safety](.cursor/rules/data-safety.mdc) | Every change | No destructive or data-loss action without the user's explicit permission. |
| [Definition of done](.cursor/rules/definition-of-done.mdc) | Every change | The checklist to satisfy, with evidence, before calling work done. |
| [Design system](.cursor/rules/design-system.mdc) | UI and `.pen` work | Tokens, typography, component recipes and screen composition. |
| [Git commits](.cursor/rules/git-commits.mdc) | Every commit | `TASK:`, `FIX:`, `BUGFIX:`, `DOCS:` or `CHORE:` messages, and when to commit. |
| [Inline documentation](.cursor/rules/inline-documentation.mdc) | Rust and Swift code | Required inline comments for humans, with an optional line for agents. |
| [Project layout](.cursor/rules/project-layout.mdc) | New files and folders | Where new code belongs in the repository. |
| [Regression testing](.cursor/rules/regression-testing.mdc) | Every feature change | Regression tests and backtests for added or changed behaviour. |
| [Rust quality](.cursor/rules/rust-quality.mdc) | Rust code | No panics outside tests, no unjustified `unsafe`, proper errors, no blocking in async. |
| [Security and crypto](.cursor/rules/security-crypto.mdc) | Every change | Key handling, plaintext boundaries, no hand-rolled crypto. |
| [Swift quality](.cursor/rules/swift-quality.mdc) | Swift code | The quality bar for the native iOS app. |

## Adding or changing a rule

- One rule per file in `docs/agent-rules/`, named after the rule in kebab-case. Start it with a
  `# Title` and an `**Applies to:**` line.
- Add a row to the table above, and an `@docs/agent-rules/<file>.md` line to `CLAUDE.md` so Claude
  Code loads the full text.
- Write rules for any agent: name a specific tool only where the rule is about that tool.
- Removing a rule removes its file, its row and its `CLAUDE.md` line.
- Adding, renaming or removing a convention in `.cursor/rules/` updates its row in the
  Conventions table.
