#!/bin/zsh
# Save every UI-pass worktree after its agent was stopped: WIP-commit uncommitted work on its
# claude/* branch, then remove the worktree (the branch stays). Run from the main checkout.
cd /Users/nvorberg/Documents/development/shroud || exit 1
git worktree list --porcelain | awk '/^worktree /{p=$2} /^branch /{print p" "$2}' | while read -r wt ref; do
  b=${ref#refs/heads/}
  case "$b" in claude/c*) ;; *) continue ;; esac
  if [ -n "$(git -C "$wt" status --porcelain -- android)" ]; then
    git -C "$wt" add -A android
    git -C "$wt" commit -q -m "WIP: Save the paused UI work on $b (stopped before the usage limit; unverified, may not compile)." && echo "committed $b"
  fi
  git worktree remove --force --force "$wt" && echo "removed $wt ($b)"
done
git worktree prune
for s in 5560 5562; do ~/Library/Android/sdk/platform-tools/adb -s emulator-$s emu kill 2>/dev/null && echo "killed emulator-$s"; done
for p in shroud-c13 shroud-c14 shroud-c3 shroud-c4 shroud-c17; do
  [ -d /tmp/$p ] && SHROUD_E2E_PREFIX=$p SHROUD_E2E_STATE=/tmp/$p android/e2e/stack-down.sh >/dev/null 2>&1 && echo "stack $p down"
done
git worktree list
git branch --list 'claude/*' -v | cut -c1-120
