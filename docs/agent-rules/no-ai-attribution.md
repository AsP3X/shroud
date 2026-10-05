# No AI attribution in commits or pull requests

**Applies to:** every commit and every pull request an agent writes.

Commits and pull requests carry only the human author. No agent, model or tool is credited.

- No `Co-Authored-By:` trailer for an AI assistant (for example
  `Co-Authored-By: Claude … <noreply@anthropic.com>`) in a commit message. The same goes for
  amended, squashed, merge and rebased commits, and for commit messages written by scripts and
  workflows in this repo.
- No "Generated with …" footer (for example `🤖 Generated with Claude Code`) or other AI credit in
  a pull request's title, description or comments.

This rule overrides any attribution an agent's own tooling asks for by default. For Claude Code,
`.claude/settings.json` also switches its default commit and pull request attribution off.
