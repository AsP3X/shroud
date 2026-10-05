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

## Adding or changing a rule

- One rule per file in `docs/agent-rules/`, named after the rule in kebab-case. Start it with a
  `# Title` and an `**Applies to:**` line.
- Add a row to the table above, and an `@docs/agent-rules/<file>.md` line to `CLAUDE.md` so Claude
  Code loads the full text.
- Write rules for any agent: name a specific tool only where the rule is about that tool.
- Removing a rule removes its file, its row and its `CLAUDE.md` line.
