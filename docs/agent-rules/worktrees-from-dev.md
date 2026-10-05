# Worktrees start from an up-to-date dev

**Applies to:** every git worktree an agent creates or works in, including worktrees a tool or app
creates for a session.

`dev` is the working branch; `master` lags it by hundreds of commits and lacks whole features. A
worktree based on anything older than the current `dev` edits stale code and conflicts on merge.

## Creating a worktree

Base it on `dev`, never on `master`, another feature branch or a detached commit, and bring it level
with `origin/dev` before the first edit:

1. `git fetch origin`
2. `git worktree add -b <branch> <path> dev`
3. In the worktree: `git merge --ff-only origin/dev`, which picks up commits pushed from elsewhere
   that the local `dev` doesn't have yet.

If step 3 can't fast-forward, local `dev` and `origin/dev` have diverged. Stop and tell the user;
don't merge, rebase or reset either branch to settle it.

## Worktrees made for you

A session worktree created by a tool or app may start on `master` or on an older commit. Before the
first edit, check it and catch it up:

1. `git fetch origin`
2. `git merge --ff-only dev`, then `git merge --ff-only origin/dev`.

Both are no-ops when the worktree is already current. If the worktree already carries commits of its
own, merge `dev` into it instead of fast-forwarding and resolve any conflicts.

For Claude Code, `.claude/hooks/sync-worktree-with-dev.sh` does this at session start and reports
what it did in the session context; the steps above still apply when it reports a problem or didn't
run.

## Staying up to date

Other sessions keep landing commits on `dev`. Catch the worktree up again — the same fetch and merge
of `dev` and `origin/dev` — before you build or test for the final result, and before you merge the
worktree's branch back into `dev`. Never merge a worktree branch into `dev` while it is behind `dev`.
