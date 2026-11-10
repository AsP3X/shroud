# Squads `Shroud MacBook` and `Shroud Demoncore`

Snapshot of `multica squad get` and `multica squad member list` for both squads on 2026-11-10.
Mika is the superior of both squads and their leader. Henrik routes the board under her and does not
lead either squad. Each card belongs to one squad; [routing.md](routing.md#two-squads) explains how
Henrik picks it.

| Squad | Machine | Platforms | Reviewer | ID in this environment |
| ----- | ------- | --------- | -------- | ---------------------- |
| `Shroud MacBook` | the member's MacBook (Mac Claude, Mac Grok) | every platform; the only squad for `ios/` and `design/*.pen` | Nora | `7f2bdde6-4f27-43a8-86f0-4ef99ee97aab` |
| `Shroud Demoncore` | the Windows machine DESKTOP-BTQHK4R (Windows Claude, Windows Grok) | `server/`, `admin/`, `web/`, `android/`; no iOS, no `.pen` edits | Liv | `18795066-c2ce-43ef-9a9d-4b685a3dd716` |

## `Shroud MacBook`

This squad was called `Shroud development team` until the Demoncore squad was added; it kept its ID
and members. Its description, leader and instructions changed when Mika became the superior of both
squads.

| Field | Value |
| ----- | ----- |
| Name | `Shroud MacBook` |
| Description | Mika is the superior. Henrik routes under her: planner, engineers, reviewer, then member QA. Only this squad builds iOS or edits .pen files. |
| Leader | Mika |
| ID in this environment | `7f2bdde6-4f27-43a8-86f0-4ef99ee97aab` |

### Members

| Member | Type | Role (verbatim) | ID in this environment |
| ------ | ---- | --------------- | ---------------------- |
| Mika | agent | Superior of both squads. | `113e4dc8-b273-4a51-a2c4-ccae7ccea0cf` |
| [Docker] Development Manager - Henrik | agent | Board router under Mika. Does not lead the squad. | `14fadf41-db2c-4659-a140-d89caca0b458` |
| [Macbook] Senior Full-Stack Engineer - Vera | agent | Optional high-quality pass, or a security review the member invoked. Not a review step | `c7aef5da-c655-4543-98f3-766c9cb6a999` |
| [Macbook] Full-Stack Engineer - Jonas | agent | Implementation, including server contracts, migrations, crypto, calls, Android core, and suite audits | `3ccc6b5b-56e1-4b80-b0cf-5bb0763a1070` |
| [Macbook] Full-Stack Engineer - Lena | agent | Same implementation work as Jonas, when Jonas already has an implementation card | `06178866-5778-495a-a831-c5c321fcdd66` |
| [Macbook] Code Reviewer - Nora | agent | Reviewer: runs the suites, one verdict per pass, merges a clean branch into dev with git, never pushes master | `f437f7bd-f806-4466-919c-b912a84fb037` |
| [Macbook] Shroud Planner - Ida | agent | Planner: writes and orders cards, audits drift; read-only on code | `93ce23a8-177e-4b78-a005-0652eb6a21f0` |
| the member (workspace owner) | member | Owner: requests the dev → master pull request and merges master, runs Device QA, decides product questions | `e13c707b-587b-4b8f-94ec-94714c3e3529` |

### Instructions

Full text, unchanged except for the trailing newline the live text ends with (`$(...)` drops it on
restore). It names agents by first name only.

~~~~ instructions-macbook
Mika (113e4dc8-b273-4a51-a2c4-ccae7ccea0cf) is the superior of Shroud MacBook and Shroud Demoncore. Henrik routes the board under her. He does not lead either squad.

This is the MacBook squad. Mac members execute on Niklas's MacBook. Only they may touch ios/ or edit design/*.pen. Nora reviews their cards and git-merges a clean branch into dev.

Demoncore executes server, admin, web, and Android on the Windows machine. Liv reviews those cards. A card belongs to one squad. The other squad continues it only when the owning runtime is offline, the branch is pushed, and the card does not need the Mac.

Vera and Saga run only when the member asked for that person, mentioned them, or Mika assigned them. No pull request unless the member asks, and then it is dev → master.
~~~~

## `Shroud Demoncore`

| Field | Value |
| ----- | ----- |
| Name | `Shroud Demoncore` |
| Description | Mika is the superior. Henrik routes under her. Windows squad for server, admin, web, and Android. No iOS and no .pen edits. |
| Leader | Mika |
| ID in this environment | `18795066-c2ce-43ef-9a9d-4b685a3dd716` |

### Members

| Member | Type | Role (verbatim) | ID in this environment |
| ------ | ---- | --------------- | ---------------------- |
| Mika | agent | Superior of both squads. | `113e4dc8-b273-4a51-a2c4-ccae7ccea0cf` |
| [Docker] Development Manager - Henrik | agent | Board router under Mika. Does not lead the squad. | `14fadf41-db2c-4659-a140-d89caca0b458` |
| [Demoncore] Full-Stack Engineer - Soren | agent | Demoncore implementation, primary | `e4449943-228b-4892-9961-f0d10a1f378d` |
| [Demoncore] Full-Stack Engineer - Freja | agent | Demoncore implementation when Soren has a card | `717f0bda-5468-4880-97bb-8f2fa66909a2` |
| [Demoncore] Code Reviewer - Liv | agent | Demoncore reviewer. Git-merges a clean branch into dev. No ios. | `b0e769fd-b7e9-4cd5-9035-3ee685c8d36b` |
| [Demoncore] Senior Full-Stack Engineer - Saga | agent | Demoncore senior. Only when the member asked, mentioned her, or Mika assigned her. No iOS. | `c29d370f-286b-432b-bb08-79fc4c1d5429` |
| [Demoncore] Shroud Planner - Emil | agent | Demoncore planner. Does not duplicate Ida's cards. | `36dd57c7-0202-4fd7-9e6f-c11fabe74f4b` |
| the member (workspace owner) | member | Owner. Requests the dev to master pull request, runs Device QA, decides product questions. | `e13c707b-587b-4b8f-94ec-94714c3e3529` |

### Instructions

Full text, unchanged except for the trailing newline. It contains this environment's agent UUIDs;
the restore replaces them through `ids.sed`.

~~~~ instructions-demoncore
Mika (113e4dc8-b273-4a51-a2c4-ccae7ccea0cf) is the superior of Shroud MacBook and Shroud Demoncore. Henrik routes the board under her. He does not lead this squad.

This is the Demoncore squad on the Windows machine DESKTOP-BTQHK4R. Soren (e4449943-228b-4892-9961-f0d10a1f378d) and Freja (717f0bda-5468-4880-97bb-8f2fa66909a2) implement. Liv (b0e769fd-b7e9-4cd5-9035-3ee685c8d36b) reviews and git-merges a clean branch into dev. Saga (c29d370f-286b-432b-bb08-79fc4c1d5429) runs only when the member asked for her, mentioned her, or Mika assigned her. Emil (36dd57c7-0202-4fd7-9e6f-c11fabe74f4b) plans work this squad can do and does not duplicate Ida's cards.

No ios/. No .pen edits. No Xcode. If a card needs either, it belongs to Shroud MacBook. Continue a Mac card only when Henrik assigns it because the Mac runtime is offline and the card does not need the Mac. One squad per card. No pull request unless the member asks, and then it is dev → master.
~~~~

## Restore

Run from `docs/multica-setup/` after the agents exist (README step 6). Member IDs below are this
environment's; `sed -f ids.sed` turns them into the new ones.

```sh
MIKA_ID=$(echo 113e4dc8-b273-4a51-a2c4-ccae7ccea0cf | sed -f ids.sed)

MAC_SQUAD_ID=$("$M" squad create --name 'Shroud MacBook' \
  --description 'Mika is the superior. Henrik routes under her: planner, engineers, reviewer, then member QA. Only this squad builds iOS or edits .pen files.' \
  --leader "$MIKA_ID" --output json | jq -r .id)
"$M" squad update "$MAC_SQUAD_ID" --instructions "$(extract squad.md instructions-macbook | sed -f ids.sed)"

WIN_SQUAD_ID=$("$M" squad create --name 'Shroud Demoncore' \
  --description 'Mika is the superior. Henrik routes under her. Windows squad for server, admin, web, and Android. No iOS and no .pen edits.' \
  --leader "$MIKA_ID" --output json | jq -r .id)
"$M" squad update "$WIN_SQUAD_ID" --instructions "$(extract squad.md instructions-demoncore | sed -f ids.sed)"

sed -f ids.sed > members.sh <<'EOF'
for SQ in "$MAC_SQUAD_ID" "$WIN_SQUAD_ID"; do
  "$M" squad member set-role "$SQ" --member-id 113e4dc8-b273-4a51-a2c4-ccae7ccea0cf \
    --role 'Superior of both squads.' ||
  "$M" squad member add "$SQ" --member-id 113e4dc8-b273-4a51-a2c4-ccae7ccea0cf \
    --role 'Superior of both squads.'
  "$M" squad member add "$SQ" --member-id 14fadf41-db2c-4659-a140-d89caca0b458 \
    --role 'Board router under Mika. Does not lead the squad.'
done
"$M" squad member add "$MAC_SQUAD_ID" --member-id c7aef5da-c655-4543-98f3-766c9cb6a999 \
  --role 'Optional high-quality pass, or a security review the member invoked. Not a review step'
"$M" squad member add "$MAC_SQUAD_ID" --member-id 3ccc6b5b-56e1-4b80-b0cf-5bb0763a1070 \
  --role 'Implementation, including server contracts, migrations, crypto, calls, Android core, and suite audits'
"$M" squad member add "$MAC_SQUAD_ID" --member-id 06178866-5778-495a-a831-c5c321fcdd66 \
  --role 'Same implementation work as Jonas, when Jonas already has an implementation card'
"$M" squad member add "$MAC_SQUAD_ID" --member-id f437f7bd-f806-4466-919c-b912a84fb037 \
  --role 'Reviewer: runs the suites, one verdict per pass, merges a clean branch into dev with git, never pushes master'
"$M" squad member add "$MAC_SQUAD_ID" --member-id 93ce23a8-177e-4b78-a005-0652eb6a21f0 \
  --role 'Planner: writes and orders cards, audits drift; read-only on code'
"$M" squad member add "$MAC_SQUAD_ID" --member-id e13c707b-587b-4b8f-94ec-94714c3e3529 --type member \
  --role 'Owner: requests the dev → master pull request and merges master, runs Device QA, decides product questions'
"$M" squad member add "$WIN_SQUAD_ID" --member-id e4449943-228b-4892-9961-f0d10a1f378d \
  --role 'Demoncore implementation, primary'
"$M" squad member add "$WIN_SQUAD_ID" --member-id 717f0bda-5468-4880-97bb-8f2fa66909a2 \
  --role 'Demoncore implementation when Soren has a card'
"$M" squad member add "$WIN_SQUAD_ID" --member-id b0e769fd-b7e9-4cd5-9035-3ee685c8d36b \
  --role 'Demoncore reviewer. Git-merges a clean branch into dev. No ios.'
"$M" squad member add "$WIN_SQUAD_ID" --member-id c29d370f-286b-432b-bb08-79fc4c1d5429 \
  --role 'Demoncore senior. Only when the member asked, mentioned her, or Mika assigned her. No iOS.'
"$M" squad member add "$WIN_SQUAD_ID" --member-id 36dd57c7-0202-4fd7-9e6f-c11fabe74f4b \
  --role 'Demoncore planner. Does not duplicate Ida'\''s cards.'
"$M" squad member add "$WIN_SQUAD_ID" --member-id e13c707b-587b-4b8f-94ec-94714c3e3529 --type member \
  --role 'Owner. Requests the dev to master pull request, runs Device QA, decides product questions.'
EOF
M="$M" MAC_SQUAD_ID="$MAC_SQUAD_ID" WIN_SQUAD_ID="$WIN_SQUAD_ID" sh members.sh
"$M" squad member list "$MAC_SQUAD_ID" --output json | jq -r '.[] | "\(.role)"'
"$M" squad member list "$WIN_SQUAD_ID" --output json | jq -r '.[] | "\(.role)"'
```

`squad create` adds the leader, Mika, with the role `leader`; the loop in `members.sh` sets her role
to the live `Superior of both squads.`, or adds her with it if the list did not show her. Henrik is
an ordinary member of both squads, not their leader. Each list must print eight roles.
