# Squad `Shroud development team`

Snapshot of `multica squad get 7f2bdde6-4f27-43a8-86f0-4ef99ee97aab` and `multica squad member list 7f2bdde6-4f27-43a8-86f0-4ef99ee97aab` on 2026-11-10.

| Field | Value |
| ----- | ----- |
| Name | `Shroud development team` |
| Description | Manager-led delivery loop for Shroud: planner → manager → engineers → reviewer → member QA. |
| Leader | [Docker] Development Manager - Henrik |
| ID in this environment | `7f2bdde6-4f27-43a8-86f0-4ef99ee97aab` |

## Members

| Member | Type | Role (verbatim) | ID in this environment |
| ------ | ---- | --------------- | ---------------------- |
| [Docker] Development Manager - Henrik | agent | leader | `14fadf41-db2c-4659-a140-d89caca0b458` |
| [Macbook] Senior Full-Stack Engineer - Vera | agent | Senior engineer: server contracts, migrations, crypto, calls, Android core, security, cross-platform changes, technical calls on blocked cards | `c7aef5da-c655-4543-98f3-766c9cb6a999` |
| [Macbook] Full-Stack Engineer - Jonas | agent | Jonas: single-platform features and fixes (web, iOS, Android UI, admin) | `3ccc6b5b-56e1-4b80-b0cf-5bb0763a1070` |
| [Macbook] Full-Stack Engineer - Lena | agent | Lena: single-platform features and fixes (web, iOS, Android UI, admin) | `06178866-5778-495a-a831-c5c321fcdd66` |
| [Macbook] Code Reviewer - Nora | agent | Reviewer: runs the suites, one verdict per pass, never edits or merges | `f437f7bd-f806-4466-919c-b912a84fb037` |
| [Macbook] Shroud Planner - Ida | agent | Planner: writes and orders cards, audits drift; read-only on code | `93ce23a8-177e-4b78-a005-0652eb6a21f0` |
| the member (workspace owner) | member | Owner: merges pull requests, runs Device QA, decides product questions | `e13c707b-587b-4b8f-94ec-94714c3e3529` |

Mika is not a squad member.

## Instructions

Full text, unchanged. It names agents by first name only, so it needs no ID replacement.

~~~~ instructions
You lead the Shroud development team. Shroud is an end-to-end encrypted messenger (Rust server, operator console, web, iOS, Android, Pencil designs) at https://github.com/AsP3X/shroud, working branch dev. Coordinate. Do not implement product code.

The status loop assigns the work (project "Shroud", workflow "Shroud delivery"):
- todo: you. Route by the card's Owner line and your brief's routing table: Vera is optional, not a review step: only when a high-quality pass is required, or when the member invoked her. Security-audit follow-ups are not automatic. Everything else goes to Jonas or Lena. Start a card only when that engineer is free, with one `issue update --assignee-id <engineer> --status in_progress` call. Limits: one implementation card per engineer; at most one card in progress that changes visible UI (one Pen.app); never two cards on the same platform directory; no Android implementation while the member's runtime card is blocked on the Android SDK, unless the card is an unbuilt delivery for Device QA.
- in_progress: the engineer does every work item, runs the platform suite, pushes the feature branch, posts one delivery comment, moves the card to in_review. No pull request.
- in_review: the reviewer runs the suites again. Clean, no faults → she merges the feature branch into dev with git and pushes origin dev, then done. No pull request, and she never pushes master. This Mac could not build it, or the Pencil `save` tool was unavailable → Device QA. Failures, including a later pass, → in_progress, assigned to the author. Judgment → blocked. Do not send a review return to Vera.
- Device QA: the member. Leave it alone.
- done: the review gate passed and the feature branch is merged into dev. The member requests any pull request, and the only one is dev → master. The member alone merges master.
- blocked: you. Resolve it from the docs and rules. Do not call Vera unless a high-quality pass is required or the member invoked her. Assign the member only for a real product choice, with a TLDR.

Do not cancel a live run by reassigning it. Plan-writing stays with the planner. Every delivery is pushed to origin on its task branch before review; a commit that exists only on the Mac is not delivered. No force-push.
~~~~

## Restore

Run from `docs/multica-setup/` after the agents exist (README step 6). Member IDs below are this
environment's; `sed -f ids.sed` turns them into the new ones.

```sh
HENRIK_ID=$(echo 14fadf41-db2c-4659-a140-d89caca0b458 | sed -f ids.sed)
SQUAD_ID=$("$M" squad create --name 'Shroud development team' \
  --description 'Manager-led delivery loop for Shroud: planner → manager → engineers → reviewer → member QA.' \
  --leader "$HENRIK_ID" --output json | jq -r .id)
"$M" squad update "$SQUAD_ID" --instructions "$(extract squad.md instructions)"
sed -f ids.sed > members.sh <<'EOF'
"$M" squad member add "$SQUAD_ID" --member-id c7aef5da-c655-4543-98f3-766c9cb6a999 \
  --role 'Senior engineer: server contracts, migrations, crypto, calls, Android core, security, cross-platform changes, technical calls on blocked cards'
"$M" squad member add "$SQUAD_ID" --member-id 3ccc6b5b-56e1-4b80-b0cf-5bb0763a1070 \
  --role 'Jonas: single-platform features and fixes (web, iOS, Android UI, admin)'
"$M" squad member add "$SQUAD_ID" --member-id 06178866-5778-495a-a831-c5c321fcdd66 \
  --role 'Lena: single-platform features and fixes (web, iOS, Android UI, admin)'
"$M" squad member add "$SQUAD_ID" --member-id f437f7bd-f806-4466-919c-b912a84fb037 \
  --role 'Reviewer: runs the suites, one verdict per pass, never edits or merges'
"$M" squad member add "$SQUAD_ID" --member-id 93ce23a8-177e-4b78-a005-0652eb6a21f0 \
  --role 'Planner: writes and orders cards, audits drift; read-only on code'
"$M" squad member add "$SQUAD_ID" --member-id e13c707b-587b-4b8f-94ec-94714c3e3529 --type member \
  --role 'Owner: merges pull requests, runs Device QA, decides product questions'
EOF
M="$M" SQUAD_ID="$SQUAD_ID" sh members.sh
"$M" squad member list "$SQUAD_ID" --output json | jq -r '.[] | "\(.role)"'
```

`squad create` adds the leader with the role `leader`. If the list does not show it, add Henrik
with `--role leader`.
