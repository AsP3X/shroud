# Admin API fixtures

Examples of every `/api/admin` response in `docs/admin-plan.md` §3, plus the state files named in §3.8. `routes.json` maps each route to a file. A `?state=` on the fixture dev server (Claude's C0.2) selects the file named in that route's `states` object.

`schema/` is JSON Schema draft 2020-12. Grok's later route tests check real responses against these schemas. A response that does not match is a contract bug (§3.9), not a UI change.

The clock is `2026-10-08T14:02:31Z`, Overview's `checked_at`. "Today" on the frames is 2026-10-08, "yesterday" is 2026-10-07, "41 days ago" is 2026-08-28, and "9 days ago" is 2026-09-29. Sizes are decimal: 412 GB is `412000000000`, 1.8 GB is `1800000000`.

Frame words that are not JSON values: "Admin" is role `write` and "View only" is `read`. A device marked Active has `session: "live"`. Stale has `session: "none"` and `revoked: false`. Removed has `revoked: true`. The users row marked Dormant is `status: "active"` with `last_active_on` `2026-08-28`.

No fixture carries a client address, user agent, username, device name, message, media object path, push token, or subscription endpoint. The one address is the server's own bind value on Configuration, `0.0.0.0:8080`. The audit frame's "from 10.0.4.17" lines are not in `audit-log.json`. The setup TOTP secret `JBSWY3DPEHPK3PXP` is the public Base32 example for "Hello!" (RFC 4648), not an operator secret. Recovery codes are `demo-0001` through `demo-0008`. Setup URLs are relative paths with nil ids.

These frame details are not fields in §3, so they are not in the files:

- Overview: migration count, pool size, the Redis sentence, "42 open" link relays, and "as of 14:01:31". `overview.database-down.json` is still a 200 body with `ready.status` `not_ready`. A console that cannot query Postgres at all is `error.upstream-postgres.json` (502), which is also the Users · Couldn't load body.
- Users: the online dot, and which icons make up `mixed`. `7f3c9a1e` is `mixed` (the detail frame has APNs, Web Push, and UnifiedPush). `a91b02cd` is `apns` (APNs plus the No-push icon).
- User detail: the 18:04 created time, one-time prekeys `87 · 64 · 12`, unattached media, and 14 calls in 30 days. `counts.contacts`, `counts.blocks`, and `counts.conversations` are `null` because those rows say "Not visible" and give no number.
- Storage: the daily chart, per-account sizes, and the last cleanup run. `max_object_bytes` is the server cap `2147483648`, which the frame does not print. `migrated_total` is `0`.
- Calls: "38 today", the voice/video split, the relay share, outcomes, and the TURN test. `created_total` is `612`, the same counter as overview `metrics.calls_created_total`.
- Privacy: there is no fifth state for the "Check" badge. The Nginx Proxy Manager row is `not_stored` with detail "Can't be checked from here. Turn it off in each proxy host."
- Operators: the "your sessions" list. No route returns it.
- Sign in: "4 tries left" is not a field on `BAD_CREDENTIALS`.

Routes that answer `204` are listed in `routes.json` under `no_body` and have no file. Loading frames are the dev server's latency switch, not a fixture.
