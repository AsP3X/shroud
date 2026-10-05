#!/bin/bash
# SessionStart hook: bring a fresh session worktree level with dev and origin/dev
# (docs/agent-rules/worktrees-from-dev.md). Does nothing in the main checkout or
# in other repositories. Whatever it prints on stdout reaches the agent as
# session context.
#
# It's registered in the user's ~/.claude/settings.json, pointing at this file
# in the main checkout: a worktree cut from master has neither this script nor
# the project settings, so a project-level hook wouldn't run where it's needed.

script_dir=$(cd "$(dirname "$0")" && pwd -P)
repo_common=$(cd "$script_dir" && cd "$(git rev-parse --git-common-dir)" && pwd -P) || exit 0

dir=$(jq -r '.cwd // empty' 2>/dev/null)
cd "${dir:-${CLAUDE_PROJECT_DIR:-.}}" 2>/dev/null || exit 0

git_dir=$(git rev-parse --absolute-git-dir 2>/dev/null) || exit 0
common_dir=$(cd "$(git rev-parse --git-common-dir)" && pwd -P)
[ "$common_dir" = "$repo_common" ] || exit 0
[ "$(cd "$git_dir" && pwd -P)" = "$common_dir" ] && exit 0

rule="docs/agent-rules/worktrees-from-dev.md"

fetch_note=""
if ! git fetch --quiet origin dev 2>/dev/null; then
  fetch_note=" (git fetch origin failed; origin/dev may be stale)"
fi

target=dev
if git rev-parse --verify --quiet origin/dev >/dev/null; then
  if git merge-base --is-ancestor dev origin/dev; then
    target=origin/dev
  elif ! git merge-base --is-ancestor origin/dev dev; then
    echo "Worktree sync: local dev and origin/dev have diverged${fetch_note}. Tell the user before editing; don't merge, rebase or reset either branch ($rule)."
    exit 0
  fi
fi

if git merge-base --is-ancestor "$target" HEAD; then
  echo "Worktree sync: already up to date with $target${fetch_note}."
  exit 0
fi

if ! git merge-base --is-ancestor HEAD "$target"; then
  echo "Worktree sync: this worktree has commits that $target doesn't, so it wasn't fast-forwarded${fetch_note}. Merge $target into it before the first edit ($rule)."
  exit 0
fi

before=$(git rev-parse --short HEAD)
if git merge --ff-only --quiet "$target" >/dev/null 2>&1; then
  echo "Worktree sync: fast-forwarded this worktree from $before to $target ($(git rev-parse --short HEAD))${fetch_note}."
else
  echo "Worktree sync: fast-forward from $before to $target failed (local changes in the way?)${fetch_note}. Run git merge --ff-only $target before the first edit ($rule)."
fi
