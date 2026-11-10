# Multica workspace setup

This folder is a snapshot of the Multica workspace that runs Shroud's agent team, taken from the live
CLI on 2026-11-10. It holds everything needed to rebuild the same team on a blank workspace: the
seven agents with their full instructions, models and runtimes, the squad, the `Shroud delivery`
workflow with every handoff text, the `Shroud` project with its repository binding, and the routing
the instructions encode.

The live CLI was the source of truth. Where the setup card and the CLI disagreed, these files follow
the CLI. Texts are copied byte for byte and still contain this environment's UUIDs; the restore
replaces them (step 5).

| File | Contents |
| ---- | -------- |
| [agents/](agents/) | One file per agent: board name, role, description, model, thinking level, runtime kind, concurrency, conversation starters, full instructions, create command |
| [workflow.md](workflow.md) | Workflow `Shroud delivery`: statuses in board order, handler, next and back, every handoff text, steps file |
| [squad.md](squad.md) | Squad `Shroud development team`: leader, members and roles, full instructions |
| [project.md](project.md) | Project `Shroud`: description, status, lead, repository resource, workflow binding |
| [runtimes.md](runtimes.md) | The three runtime kinds, which agents run on each, and the other runtimes present here |
| [routing.md](routing.md) | Who takes what, as two diagrams and a checklist for a restored workspace |

## What a new environment needs first

- A Multica workspace, with you as owner or admin, and the `multica` CLI logged in to it
  (`multica login`, then `multica workspace switch <workspace>`).
- `jq`, `awk` and `sed` on the machine that runs the restore.
- Three runtimes, each from a daemon on its machine (`multica setup` there):
  - **Mac Claude** — Claude Code on the Mac that builds Shroud (Xcode selected with `xcode-select`,
    Pen.app with the Pencil MCP server, Docker, Rust, Node 24 on the default PATH, the Android SDK
    at `~/Library/Android/sdk`). Runs Vera, Jonas, Lena and Ida.
  - **Mac Grok** — the Grok CLI on the same Mac. Runs Nora.
  - **Docker Grok** — the Grok CLI in a Docker runner. Runs Mika and Henrik.
- Access to `https://github.com/AsP3X/shroud` for the Mac runtimes (`gh auth login`), with `dev` as
  the working branch.

## Delivery rule

This is the rule the restored texts carry, as the live workspace has it since 2026-11-10:

- Worktrees and feature branches start from `dev`. The engineer pushes the feature branch to
  origin, posts one delivery comment and moves the card to `in_review`. No pull request.
- When the review finds no faults and the checks passed, the reviewer merges the feature branch
  into `dev` with git and pushes `origin dev`.
- A pull request exists only when the member asks for one, and it is always `dev` → `master`. Only
  the member merges `master`.

Vera runs only when the member asked for her or mentioned her, or when Mika assigned her because it
is absolutely necessary; [routing.md](routing.md#when-vera-runs) spells that out. Android is built
and tested on the Mac like every other platform.
[routing.md](routing.md#where-the-live-texts-still-disagree) lists the places where the live texts
still carry an earlier rule.

## Do not copy

Tokens, API keys, daemon credentials, `custom_env` values, MCP secrets, and the contents of
`~/.multica` stay with the machine they belong to. None is in this folder: every agent here has no
custom environment, no MCP config, no custom args and no skills. Set up credentials fresh on the new
machines. The only password-like string in these files is `POSTGRES_PASSWORD=shroud` inside the
engineers' and reviewer's instructions, which is the throwaway password of the local test container.

## Restore

Run every step from this folder, in one shell. Each step creates new records; none needs an old
UUID to exist. Keep the shell open: later steps use the variables earlier ones set.

### 1. Helpers

```sh
cd docs/multica-setup
M=multica          # or "$MULTICA_CLI_PATH" inside a Multica run
: > ids.sed        # old UUID -> new UUID, one sed command per line

# Print the body of the "~~~~ <tag>" block in <file>, without a trailing newline.
extract() {
  awk -v tag="$2" 'f && $0 == "~~~~" { exit }
                   f { printf "%s%s", sep, $0; sep = "\n" }
                   $0 == "~~~~ " tag { f = 1 }' "$1"
}
```

### 2. Runtimes

Start the three daemons, then export their new IDs and set the runtime-wide limits as shown in
[runtimes.md](runtimes.md#restore).

### 3. Agents

Run the `## Restore` block of each agent file, in this order (any order works; this one matches the
board): [mika](agents/mika.md), [henrik](agents/henrik.md), [nora](agents/nora.md),
[vera](agents/vera.md), [jonas](agents/jonas.md), [lena](agents/lena.md), [ida](agents/ida.md).
Each block appends its old→new ID line to `ids.sed`. Set the emoji avatars in the web app.

### 4. Member and project

Add the owner's new user ID to the map, then create the project and its repository resource with
the first block of [project.md](project.md#restore):

```sh
"$M" workspace member list --output json | jq -r '.[] | "\(.user_id)  \(.role)  \(.name)"'
MEMBER_ID='<user_id of the owner>'
echo "s/e13c707b-587b-4b8f-94ec-94714c3e3529/$MEMBER_ID/g" >> ids.sed
```

### 5. Agent instructions

Every ID the instructions mention now has a line in `ids.sed`. Set each agent's instructions with
the IDs replaced:

```sh
for f in agents/*.md; do
  old=$(awk -F'`' '/^\| ID in this environment \|/ { print $2 }' "$f")
  new=$(echo "$old" | sed -f ids.sed)
  "$M" agent update "$new" --instructions "$(extract "$f" instructions | sed -f ids.sed)" --output table
done
```

Check that no old ID is left in any instruction text (prints nothing when clean):

```sh
sed -E 's#^s/([^/]*)/.*#\1#' ids.sed > old-ids.txt
"$M" agent list --output json | jq -r '.[].id' | while read -r a; do
  "$M" agent get "$a" --output json | jq -r .instructions
done | grep -F -f old-ids.txt
```

### 6. Squad

Run the block in [squad.md](squad.md#restore).

### 7. Workflow

Run the blocks in [workflow.md](workflow.md#restore). The workflow's handlers and texts go through
`ids.sed`, so they point at the new agents and the new owner.

### 8. Bind the project to the workflow

Run the second block of [project.md](project.md#restore).

### 9. Check the result

- `"$M" agent list` shows seven agents with the board names, models and thinking levels in
  [routing.md](routing.md#agents-and-models).
- `"$M" workflow get 'Shroud delivery'` lists the statuses, handlers, next and back from
  [workflow.md](workflow.md#statuses-in-board-order).
- `"$M" project workflow get <project-id>` shows `uses_default: false` and `Shroud delivery`.
- Walk the checklist in [routing.md](routing.md#checking-a-restored-workspace).

If the new machines differ from the member's MacBook (where Xcode, Node 24 or the Android SDK
live), edit the "Mac runtime facts" in the project description and the "Verification per platform"
sections of the engineer and reviewer instructions after the restore.

## This environment

The records this snapshot was read from. They identify this workspace only and are not needed by
the restore.

| Record | Name | ID |
| ------ | ---- | -- |
| Workspace | `Shroud` (issue prefix `SHRO`) | `299469fa-1647-4638-a95c-ec9db2ef3c9c` |
| Owner (member) | `services` | user `e13c707b-587b-4b8f-94ec-94714c3e3529` |
| Agent | `Mika` | `113e4dc8-b273-4a51-a2c4-ccae7ccea0cf` |
| Agent | `[Docker] Development Manager - Henrik` | `14fadf41-db2c-4659-a140-d89caca0b458` |
| Agent | `[Macbook] Code Reviewer - Nora` | `f437f7bd-f806-4466-919c-b912a84fb037` |
| Agent | `[Macbook] Senior Full-Stack Engineer - Vera` | `c7aef5da-c655-4543-98f3-766c9cb6a999` |
| Agent | `[Macbook] Full-Stack Engineer - Jonas` | `3ccc6b5b-56e1-4b80-b0cf-5bb0763a1070` |
| Agent | `[Macbook] Full-Stack Engineer - Lena` | `06178866-5778-495a-a831-c5c321fcdd66` |
| Agent | `[Macbook] Shroud Planner - Ida` | `93ce23a8-177e-4b78-a005-0652eb6a21f0` |
| Runtime (Mac Claude) | `Claude (Niklass-MacBook-Pro.local)` | `6695f297-cc52-4985-a84c-5b2da735e3dc` |
| Runtime (Mac Grok) | `Grok (Niklass-MacBook-Pro.local)` | `c3a174ed-3528-4230-beae-0925fdea641e` |
| Runtime (Docker Grok) | `Grok (Docker runner)` | `bb1e366e-6c6b-4815-af0a-18c732c93103` |
| Squad | `Shroud development team` | `7f2bdde6-4f27-43a8-86f0-4ef99ee97aab` |
| Workflow | `Shroud delivery` | `c89e651c-017c-4a29-a87f-2f4042075b90` |
| Project | `Shroud` | `87e4b813-d93b-4a0c-80a3-1da9c68242dc` |
| Project resource | `shroud (dev)` | `228646cd-e195-4767-ae96-b1bbea49a552` |

The other runtimes registered here, which no Shroud agent uses, are listed in
[runtimes.md](runtimes.md#other-runtimes-in-this-workspace).

## Keeping this current

The snapshot goes stale as soon as an agent, the squad or the workflow changes. To refresh it, read
the same commands again (`multica agent get <id> --output json`, `multica workflow get "Shroud
delivery" --output json`, `multica squad get` and `squad member list`, `multica project get`,
`multica project resource list`, `multica runtime list`) and update the files in a `DOCS:` change.
