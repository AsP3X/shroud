# Workflow `Shroud delivery`

Snapshot of `multica workflow get "Shroud delivery" --output json` on 2026-11-10. It is bound to the
project `Shroud` and is not the workspace default.

Description:

> backlog → todo (manager) → in_progress (assigned engineer, who saves .pen files) → in_review (reviewer) → done, or Device QA (member) for anything this Mac could not build; blocked goes to the manager.

## Statuses in board order

| Key | Column | Handler | Next | Back |
| --- | ------ | ------- | ---- | ---- |
| `backlog` | Backlog | none | — | — |
| `todo` | Todo | agent: [Docker] Development Manager - Henrik (`14fadf41-db2c-4659-a140-d89caca0b458`) | `in_progress` | — |
| `in_progress` | In Progress | none | `in_review` | `blocked` |
| `in_review` | In Review | agent: [Docker] Development Manager - Henrik (`14fadf41-db2c-4659-a140-d89caca0b458`) | `done` | `in_progress` |
| `device_qa` | Device QA | member: the member (workspace owner) (`e13c707b-587b-4b8f-94ec-94714c3e3529`) | `in_review` | `in_progress` |
| `blocked` | Blocked | agent: [Docker] Development Manager - Henrik (`14fadf41-db2c-4659-a140-d89caca0b458`) | — | `in_progress` |
| `done` | Done | none | — | — |
| `cancelled` | Cancelled | none | — | — |

`device_qa` is a custom workspace status shown as **Device QA**. The CLI does not print a status's
category; the restore creates it as `started` (work still under way), which is how it behaves on the
board. If the old workspace is still reachable, check the category under Workspace Settings › Issue
Statuses and use that instead.

Steps without a handler (`backlog`, `in_progress`, `done`, `cancelled`) start no run: whoever holds
the card does the work. `in_progress` is held by the engineer Henrik assigned.

`in_review` wakes Henrik, not a reviewer. He reads the author from the delivery comment and assigns
the card to that squad's reviewer, Nora for a Mac author and Liv for a Demoncore author, with
`multica issue update <id> --assignee-id <reviewer> --status in_review`. The assignment starts the
reviewer's run. Henrik does not review the diff.

## Handoff instructions

Full text of each step's instructions, unchanged. The UUIDs inside are this environment's; the
restore replaces them through `ids.sed`.

### `todo` — Todo

Handler: agent: [Docker] Development Manager - Henrik (`14fadf41-db2c-4659-a140-d89caca0b458`).

~~~~ handoff-todo
Shroud is an end-to-end encrypted messenger; working branch dev. This card is in todo. Route it by its Owner line. Vera (c7aef5da-c655-4543-98f3-766c9cb6a999, claude-fable-5-2) is not a review step and not yours to call. Assign her only when the member asked for her, mentioned her, or Mika already assigned her. A review return stays with the author. A follow-up from docs/security-audit-2026-10.md that the member did not invoke goes back to backlog, not to Vera. Everything else, including server contracts, migrations, crypto, calls, Android core, and a suite audit across platforms, goes to Jonas (3ccc6b5b-56e1-4b80-b0cf-5bb0763a1070), or Lena (06178866-5778-495a-a831-c5c321fcdd66) if Jonas already has an implementation card. Check the in_progress column first. If the engineer you would pick already has an implementation card, or this card changes visible UI while another UI card is in progress, or it touches a platform directory another in_progress card touches, leave it in todo and write nothing. Otherwise move it with one call: multica issue update <id> --assignee-id <engineer> --status in_progress. Do not start the rest of the backlog. Plan cards stay with the planner.

Two squads. Mac (Jonas, Lena, Nora, Vera, Ida) is the only squad for ios/ or a .pen edit. Demoncore (Soren e4449943-228b-4892-9961-f0d10a1f378d, Freja 717f0bda-5468-4880-97bb-8f2fa66909a2, Liv b0e769fd-b7e9-4cd5-9035-3ee685c8d36b, Saga c29d370f-286b-432b-bb08-79fc4c1d5429, Emil 36dd57c7-0202-4fd7-9e6f-c11fabe74f4b) takes server, admin, web, and Android. One squad per card. If both can do it, pick the squad with fewer in-progress cards. Saga only when the member asked for her, mentioned her, or Mika assigned her. The other squad continues a card only when the owning runtime is offline, the branch is pushed, and the card does not touch ios/ or design/*.pen.
~~~~

### `in_review` — In Review

Handler: agent: [Docker] Development Manager - Henrik (`14fadf41-db2c-4659-a140-d89caca0b458`).

~~~~ handoff-in_review
You dispatch the reviewer. You do not review the diff and you do not merge.

The delivery comment names the author. Mac authors are Jonas (3ccc6b5b-56e1-4b80-b0cf-5bb0763a1070), Lena (06178866-5778-495a-a831-c5c321fcdd66), and Vera (c7aef5da-c655-4543-98f3-766c9cb6a999). Assign Nora (f437f7bd-f806-4466-919c-b912a84fb037). Demoncore authors are Soren (e4449943-228b-4892-9961-f0d10a1f378d), Freja (717f0bda-5468-4880-97bb-8f2fa66909a2), and Saga (c29d370f-286b-432b-bb08-79fc4c1d5429). Assign Liv (b0e769fd-b7e9-4cd5-9035-3ee685c8d36b).

If the diff touches ios/ or a .pen file, assign Nora even if a Demoncore engineer touched the branch. Use `multica issue update <id> --assignee-id <reviewer> --status in_review`.

Do not assign Vera or Saga unless the member asked for that person, mentioned them, or Mika assigned them.
~~~~

### `device_qa` — Device QA

Handler: member: the member (workspace owner) (`e13c707b-587b-4b8f-94ec-94714c3e3529`).

~~~~ handoff-device_qa
The reviewer approved what it could run. Build or run what it could not (the comment says which: an Android build and device run, or an iOS flow on a device). If the comment says the Pencil server was started with --disable_save_tool, restart it without that flag and move the card back to in_progress. If the build or run works, move the card to in_review so Nora merges the branch into dev with git. Do not merge it yourself, and do not open a pull request. If it fails, move it to in_progress with what failed.
~~~~

### `blocked` — Blocked

Handler: agent: [Docker] Development Manager - Henrik (`14fadf41-db2c-4659-a140-d89caca0b458`).

~~~~ handoff-blocked
Read the comment that blocked this card. Resolve it yourself when the issue, the docs under docs/, AGENTS.md, docs/agent-rules/ or ordinary engineering decide it, then move the card back to in_progress assigned to the same engineer with your decision in one comment. Do not assign Vera yourself. She runs only when the member asked for her, mentioned her, or Mika already assigned her. Do not assign a security-audit follow-up the member did not invoke. Assign the member (e13c707b-587b-4b8f-94ec-94714c3e3529) only when their approval or a real product choice is required (a privacy guarantee, a new stored field, a new dependency, a wire-format break, a design decision the .pen files do not settle, a release), with a TLDR: issue, what is stuck, the number that matters, what is waiting, the choice. Do not hand them test failures, review notes or build-environment problems.
~~~~

## Restore

The steps file below is the live workflow in the shape `multica workflow create --steps-file`
accepts, with the old UUIDs. Run from `docs/multica-setup/` once `ids.sed` lists every agent and
the member (README steps 3 to 5).

```sh
extract workflow.md steps | sed -f ids.sed > steps.json
"$M" workflow create --name 'Shroud delivery' \
  --description 'backlog → todo (manager) → in_progress (assigned engineer, who saves .pen files) → in_review (reviewer) → done, or Device QA (member) for anything this Mac could not build; blocked goes to the manager.' \
  --steps-file steps.json --output json | jq -r .id
"$M" workflow get 'Shroud delivery' --output json | jq -r '.steps[].status_key'
```

The last command must print, in this order: `backlog`, `todo`, `in_progress`, `in_review`, `device_qa`, `blocked`, `done`, `cancelled`.
If the Device QA key came out other than `device_qa`, use that key below. Then give Device QA its
handoff (the member's new user ID is `$MEMBER_ID`):

```sh
extract workflow.md handoff-device_qa > device_qa.txt
"$M" workflow handoff set 'Shroud delivery' device_qa \
  --handler "$MEMBER_ID" --instructions-file device_qa.txt \
  --next in_review --back in_progress
```

~~~~ steps
[
  {
    "status_key": "backlog"
  },
  {
    "status_key": "todo",
    "handler": {
      "id": "14fadf41-db2c-4659-a140-d89caca0b458",
      "type": "agent"
    },
    "instructions": "Shroud is an end-to-end encrypted messenger; working branch dev. This card is in todo. Route it by its Owner line. Vera (c7aef5da-c655-4543-98f3-766c9cb6a999, claude-fable-5-2) is not a review step and not yours to call. Assign her only when the member asked for her, mentioned her, or Mika already assigned her. A review return stays with the author. A follow-up from docs/security-audit-2026-10.md that the member did not invoke goes back to backlog, not to Vera. Everything else, including server contracts, migrations, crypto, calls, Android core, and a suite audit across platforms, goes to Jonas (3ccc6b5b-56e1-4b80-b0cf-5bb0763a1070), or Lena (06178866-5778-495a-a831-c5c321fcdd66) if Jonas already has an implementation card. Check the in_progress column first. If the engineer you would pick already has an implementation card, or this card changes visible UI while another UI card is in progress, or it touches a platform directory another in_progress card touches, leave it in todo and write nothing. Otherwise move it with one call: multica issue update <id> --assignee-id <engineer> --status in_progress. Do not start the rest of the backlog. Plan cards stay with the planner.\n\nTwo squads. Mac (Jonas, Lena, Nora, Vera, Ida) is the only squad for ios/ or a .pen edit. Demoncore (Soren e4449943-228b-4892-9961-f0d10a1f378d, Freja 717f0bda-5468-4880-97bb-8f2fa66909a2, Liv b0e769fd-b7e9-4cd5-9035-3ee685c8d36b, Saga c29d370f-286b-432b-bb08-79fc4c1d5429, Emil 36dd57c7-0202-4fd7-9e6f-c11fabe74f4b) takes server, admin, web, and Android. One squad per card. If both can do it, pick the squad with fewer in-progress cards. Saga only when the member asked for her, mentioned her, or Mika assigned her. The other squad continues a card only when the owning runtime is offline, the branch is pushed, and the card does not touch ios/ or design/*.pen.",
    "next_status_key": "in_progress"
  },
  {
    "status_key": "in_progress",
    "next_status_key": "in_review",
    "back_status_key": "blocked"
  },
  {
    "status_key": "in_review",
    "handler": {
      "id": "14fadf41-db2c-4659-a140-d89caca0b458",
      "type": "agent"
    },
    "instructions": "You dispatch the reviewer. You do not review the diff and you do not merge.\n\nThe delivery comment names the author. Mac authors are Jonas (3ccc6b5b-56e1-4b80-b0cf-5bb0763a1070), Lena (06178866-5778-495a-a831-c5c321fcdd66), and Vera (c7aef5da-c655-4543-98f3-766c9cb6a999). Assign Nora (f437f7bd-f806-4466-919c-b912a84fb037). Demoncore authors are Soren (e4449943-228b-4892-9961-f0d10a1f378d), Freja (717f0bda-5468-4880-97bb-8f2fa66909a2), and Saga (c29d370f-286b-432b-bb08-79fc4c1d5429). Assign Liv (b0e769fd-b7e9-4cd5-9035-3ee685c8d36b).\n\nIf the diff touches ios/ or a .pen file, assign Nora even if a Demoncore engineer touched the branch. Use `multica issue update <id> --assignee-id <reviewer> --status in_review`.\n\nDo not assign Vera or Saga unless the member asked for that person, mentioned them, or Mika assigned them.",
    "next_status_key": "done",
    "back_status_key": "in_progress"
  },
  {
    "new_status": {
      "name": "Device QA",
      "category": "started"
    }
  },
  {
    "status_key": "blocked",
    "handler": {
      "id": "14fadf41-db2c-4659-a140-d89caca0b458",
      "type": "agent"
    },
    "instructions": "Read the comment that blocked this card. Resolve it yourself when the issue, the docs under docs/, AGENTS.md, docs/agent-rules/ or ordinary engineering decide it, then move the card back to in_progress assigned to the same engineer with your decision in one comment. Do not assign Vera yourself. She runs only when the member asked for her, mentioned her, or Mika already assigned her. Do not assign a security-audit follow-up the member did not invoke. Assign the member (e13c707b-587b-4b8f-94ec-94714c3e3529) only when their approval or a real product choice is required (a privacy guarantee, a new stored field, a new dependency, a wire-format break, a design decision the .pen files do not settle, a release), with a TLDR: issue, what is stuck, the number that matters, what is waiting, the choice. Do not hand them test failures, review notes or build-environment problems.",
    "back_status_key": "in_progress"
  },
  {
    "status_key": "done"
  },
  {
    "status_key": "cancelled"
  }
]
~~~~
