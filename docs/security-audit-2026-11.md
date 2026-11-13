# Security audit, November 2026

A read-only review of `dev` at `64728c06` (2026-11-10): server, operator console, deploy and CI,
web, iOS and Android. It builds on [the October audit](security-audit-2026-10.md) and does not
repeat its findings. It re-checked every October fix; S1 is a regression from L10, and A1, A2, S3,
S6 and C1 are gaps in H5, L3, H1, L6 and M7. Nothing was built or run during the review itself.

Each finding was filed as its own card under SHRO-10, which holds the full finding, the delivery
evidence and the review. The table records the outcome on `dev`. There are no High findings: 5
Medium, 11 Low and 1 decision. Every finding is fixed except S5, which is kept on purpose.

## Summary

| # | Severity | Area | Finding | Card | Status | Commits on `dev` |
| - | -------- | ---- | ------- | ---- | ------ | ---------------- |
| S1 | Medium | Server | A media upload holds a DB connection and row lock while publishing, so 10 uploads exhaust the pool (regression from L10) | SHRO-11 | Fixed | `0afb3010` |
| S2 | Medium | Server, console | Per-IP budgets key on the full IPv6 /128, so registration is unlimited from a /64 | SHRO-12 | Fixed | `ffc84360` |
| A1 | Medium | Deploy | Superuser deploy SQL runs unqualified operators in a schema the API role owns, so the API role can become superuser (H5 incomplete) | SHRO-17 | Fixed | `ff7a1c9d`, `109f5d8a` |
| A2 | Medium | Deploy, console | `grants.sql` adopts `admin.audit_log` without checking for planted rules or triggers, which then run as superuser (L3 gap) | SHRO-20 | Fixed | `6c7e5145`, `10ed873f`, `c72f48b8` |
| A3 | Medium | Console | No per-operator sign-in ceiling: TOTP can be guessed across addresses once a password is known | SHRO-21 | Fixed | `6c68e6f1`, `23ffcfd0`, `0aa0096a` |
| S3 | Low | Server, nginx | In `local` mode any private-address peer is trusted as a proxy, so clients pick their IP key (H1 incomplete) | SHRO-19 | Fixed | `8dcc8249`, `2d10cb6d`, `70d40c60` |
| S4 | Low | Server | `DELETE /conversations/{id}?scope=everyone` pushes events to any user, ungated and unbudgeted | SHRO-13 | Fixed | `f12bd5d1`, `3fe443cf` |
| S6 | Low | Server | A message or ring can slip past a block committing at the same moment (L6 covers accepts only) | SHRO-14 | Fixed | `f925ae0d`; contact removal: `018c8d38` (SHRO-46) |
| C1 | Low | Web, Android, iOS | The sealed-recipient check trusts the server-set `content_type`, so own media can be misfiled into another chat (M7 gap) | SHRO-15, SHRO-30 | Fixed | web `17371e07`, Android `a62bdf12`, iOS `d4459f2c`, docs `9a95b5d9`, `b0d30810`; Android with an unknown chat: `0dbd4d82` (SHRO-45) |
| W1 | Low | Web | A non-string caption or thumbnail from a contact makes that chat's history unloadable for good | SHRO-16 | Fixed | `2501998c`; iOS checked and pinned by a test: `52b711cc` (SHRO-49) |
| A4 | Low | Console | Setup-link tokens land in the console's and the proxy's request logs | SHRO-18 | Fixed | `a6d12065`, `712cd75b` |
| W2 | Low | Web | A shared transcript attaches to a voice note in another chat with the same id | SHRO-22 | Fixed | web `b1890de3`, Android `337804c1`; iOS `bd82d0a9` (SHRO-49) |
| W3 | Low | Web | The Whisper model is fetched unpinned and unhashed; huggingface.co is not named in the privacy docs | SHRO-23 | Fixed | `3fa5145d`, `84a73861`, `6c7e3ebb` |
| A6 | Low | Deploy | The TURN secret, and the DB passwords while a deploy runs, are visible in process arguments on the host | SHRO-24 | Fixed | `ee08f66d`, `30e4abe0` (landed again as `bf1c6f29`, `68ac8eae`) |
| A5 | Low | Console | Audit rows show the mutable operator name; re-auth is logged wrongly or not at all | SHRO-25 | Fixed | `6ecdafba`, `88d246e7`, `e533374e`, `6ee696ec` |
| A7 | Low | Deploy, CI | Operator port reachable from `proxy-network`; web container unhardened; images pinned by tag; no Dependabot; no admin UI CI | SHRO-26 | Fixed | `cfa69b5b`, `804fadf3`, `37357da7`, `f8bae478`, `e274bc1b`, `c89b2bb6`, `7f5b64f8`, `8dbb3e33`, `ccf8ff96`, `38200bba`, `487362be`, `5afc66df`, `ac2d68a1` |
| S5 | Decision | Server, docs | A share-code reset does not stop requests from people who already have the id | SHRO-27 | Kept on purpose | none |

## Notes

- **S5.** On 2026-11-13 the owner chose to keep the documented limit (SHRO-27, option 1): a reset
  retires the old code, but anyone who looked it up before already has the account's id and can
  still send a request. `docs/privacy-options.md:186-187` documents it. The separate leak S5 sits
  next to, `GET /users/{id}` handing the new code to anyone with the id, was closed by `ab3b9c9e`
  (`docs/anonymity-plan.md` 2.5).
- **Found after the fixes.** The media quota lock (October M4) bound the account id as a UUID,
  and the contact pair lock (L6, taken in sends since S6) runs the same SQL with a text bind. sqlx
  caches a statement per connection by its SQL alone, so an upload on a connection that had taken a
  pair lock failed with invalid UTF-8. Fixed by `bd547982` (SHRO-43).
- **Checking A6 on Windows.** `deploy.ps1` has no `-Calls` switch. coturn is the `calls` Compose
  profile: the setup wizard turns it on, or set `COMPOSE_PROFILES=calls` in `.env`
  (`README.md:108,131`).
- **Still open from October.** L16 (the iOS Whisper download is unpinned; transcription plan T3.6,
  iOS half) and the device checks M8, L15 and L17 ask for. They start only when the owner asks.
