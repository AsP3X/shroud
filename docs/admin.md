# Admin console operations

The console is the `admin` Compose service (`shroud-admin`). It uses role `shroud_admin` and schema `admin` in the API's Postgres database. `shroud_admin` keeps `CREATE` on the database, because its first migration creates schema `admin`. So the API pins `search_path` to `public` on every connection, and a schema the console creates is never searched by the API. The console pins its own connections to `pg_catalog, admin, pg_temp` and names the API's tables `public.<table>`, so a function or operator `shroud_api` creates in `public` never runs inside the console's queries. The superuser, which applies `api-role.sql`, `grants.sql` and the role steps, searches `pg_catalog` alone in this database, because `public` belongs to `shroud_api`: each of those files sets that first and names every table with its schema, and `api-role.sql` makes it the superuser's default here. In `psql` as the superuser, write `public.users`, not `users`. `ADMIN_SECRET_KEY` is 64 hex characters. The console will not start while `ADMIN_DATABASE_URL` is set and the key is missing or not that length.

## Deploying the console

The API and web client come up with `./deploy.sh`. The console is its own command, and it does not run the setup wizard:

```
./deploy.sh --admin
```

That turns the console on and deploys it. A secret already in `.env` is kept. Open it at the Shroud site's address plus `/admin`. If the site is `https://shroud-app.com`, the console is `https://shroud-app.com/admin`. Local mode is the same rule: the web URL in `.env`, plus `/admin`. There is no extra port to publish.

The web client proxies `/admin` and `/api/admin` to the console container. While the console is off, those paths do not answer.

The sign-in cookie is `__Host-admin`. Browsers require that name to use `Path=/` and to omit `Domain`, so it is sent to the whole site. It is `HttpOnly`. The messenger cannot read it, and the API ignores it.

### A host of its own (recommended)

On the web host, the console and the messenger share one origin: a script that ran in either one (a cross-site scripting bug) could use the operator's console session, or read the operator's messenger vault in the same browser. Give the console its own host name to separate them:

1. Set `ADMIN_HOST` in `.env` to a lowercase host name, for example `admin.shroud-app.com`.
2. Point its DNS record at the same machine as the web host, and give it TLS the same way: with `PROXY_MODE=npm`, add an NPM proxy host for it to `http://shroud-web:80`.
3. Run `./deploy.sh --admin` (`.\deploy.ps1 -Admin`). `ADMIN_PUBLIC_URL` becomes `https://admin.shroud-app.com/admin`, with the web address's scheme and port.

From then on the console answers only on that host (its `/` goes to `/admin/`), and the web host answers `404` on `/admin` and `/api/admin`. Setup links and the bootstrap link use the new address. Clearing `ADMIN_HOST` and deploying again puts the console back on the web host.

Create the first operator after that. The link is shown once:

```
./deploy.sh --admin bootstrap
```

Open it and choose a password and an authenticator.

### Setup links and proxy logs

A setup link's token is part of its URL, and anyone who opens a live link first can choose that operator's password. The console's own log replaces the token with `…` (`/api/admin/setup/…`, `/admin/setup/…`), and the web container keeps no access log. Its error log does record the full URL of a request it could not pass to the console, while the console is down or restarting (the page answers `502` or `504`). If opening a link fails that way, don't use that link again: issue a new one with **Reset authenticator** on the Operators page, or `./deploy.sh --admin bootstrap --recover` for a bootstrap link. Either one retires that operator's unused links. Nginx Proxy Manager keeps an access log: it writes every request's full URL to `/data/logs/proxy-host-<id>_access.log`, so a setup link stays readable there for anyone with access to NPM's data. Turn that log off on the proxy host that serves the console: edit the host, open **Advanced**, and add

```
access_log off;
```

to **Custom Nginx Configuration**. With `ADMIN_HOST` set that host serves only the console. Without it, the line turns the log off for the whole Shroud site. If the log stays on, open a link as soon as you get it and finish setup: a used link no longer works. An unused one stops working when it expires (15 minutes for an invite or a reset, 24 hours for the bootstrap link), or when `bootstrap --recover` replaces it.

Turn the console off with the same command. The container goes away. `ADMIN_SECRET_KEY`, the console's database password and the operator token stay in `.env`. Operators, their authenticators and the audit log stay in schema `admin`:

```
./deploy.sh --admin off
```

`./deploy.sh --admin` turns it back on with the same key, so existing authenticators still work.

To stop it for a while without changing `.env`:

```
docker compose --profile admin stop admin
docker compose --profile admin start admin
```

The next `./deploy.sh` starts it again while the `admin` profile is on.

While the profile is off, the API still binds its internal operator port when `OPERATOR_TOKEN` is set. Nothing publishes that port and nothing calls it. Blank the token in `.env` and redeploy if the API should not bind it. That port is also the only place the API's Prometheus counters are served (`GET /operator/metrics` with `Authorization: Bearer <OPERATOR_TOKEN>`): the public port has no `/metrics`, because live counters show when people are active. A scraper of your own needs the token and a place on `shroud-internal`.

## What the console shows

Sign in with a password and an authenticator app. A recovery code stands in for the app. **Admin** can change things. **View only** can read the pages. A change asks for a fresh authenticator code, valid for five minutes. Each code works once: the code that signed in can't also approve a change, so wait for the next one. Sign-ins and changes are kept in the audit log. The log and schema `admin` belong to the database superuser once `grants.sql` has run (deploy waits for the console to create them): `shroud_admin` can add and read entries, never change, remove or drop one. When it takes the log, and again on every later deploy, `grants.sql` checks that the console's role attached nothing to it while it owned it, and stops the deploy if it did (see [If grants.sql refuses the audit log](#if-grantssql-refuses-the-audit-log)). Its integration tests then need `GRANT_TEST_SUPER_URL` to clear entries.

| Page | What it shows |
| --- | --- |
| Overview | Whether Postgres, Redis and the media store answer, which push and call services are configured, and counters since the API last started |
| Privacy checks | What this server keeps that could identify someone, read from the database and the configuration when the page opens. The username-hash row follows `GET /api/v1/auth/username-kdf`: a strong Argon2id answer is "Username hashes are slow to guess"; a missing, failed or cheaper answer is "Username hashes are quick to guess", with the live account count |
| Users | Accounts by id. The username is not stored. An admin can remove a device, sign every device out, or delete the account. Deleting signs the devices out, removes that account's keys, contacts and stored media, and leaves a placeholder so other people's chats say "Deleted account". A device's label comes from its push registration |
| Sign-ups | Unused. Registration is open |
| Operators | Who can sign in. An admin can add an operator, change the role, reset an authenticator, or disable one |
| Storage | Where media lives, how many objects and bytes, and how many objects are not attached to a message |
| Retention | The cleanup rules built into the server |
| Push delivery | How many devices are registered with Apple, the web and UnifiedPush. The tokens themselves stay hidden |
| Calls | The ICE servers handed to clients, without credentials, and whether the TURN relay is on. Call audio and video stay off this server. The call count is since the API last started |
| Client versions | The latest and minimum versions for iOS, Android and the web build, and what the API tells each band |
| Configuration | The environment the console started with. A secret is shown only as set or unset |
| Rate limits | The fixed windows built into the server |
| Audit log | Operator sign-ins and actions. Entries cannot be edited or removed, not even with the console's own database login |

Message text, photos, voice, device names, username hashes and password hashes are not granted to the console. Contact, block, conversation and message counts are not shown on an account. The console also cannot tell which username hashes are still the old SHA-256: those rows move the next time that account signs in.

### The audit log

Each entry keeps the operator's name as it was when the entry was written, so the page shows who did it even after a rename. The console's own database login can still rename an operator: the console has no rename, so it notices a name that differs from the one on that operator's last entry and writes **Name changed outside the console** (`operator.rename`, with the old name) just before the next entry of that operator. That entry has the time it was noticed, not the time of the rename, and a renamed operator who never acts again gets none: nothing new is written under the new name either, and older entries keep the old one. Entries from before this change carry the name each operator had when the console was upgraded.

A fresh code for a change is logged as **Confirmed with a fresh code** (`session.reauth`): `ok` when it opened the five-minute window, `refused` with `Wrong code` or `Code already used` when it didn't. Sign-ins stay `session.sign_in`.

Upgrading a deployment whose audit log already belongs to the superuser: only the log's owner can add the name column, so the console refuses to start (`admin.audit_log needs column operator_name, and only its owner can add it`) until `grants.sql` has added it. `./deploy.sh --admin` (`.\deploy.ps1 -Admin`) runs that file after starting the console, and the console starts on its next restart. After a plain `docker compose up`, run the deploy once. The names come from `admin.operators`, which belongs to `shroud_admin`, so `grants.sql` first locks that table and checks it the same way as the log (see [If grants.sql refuses the audit log](#if-grantssql-refuses-the-audit-log)) and reads it only when nothing is attached.

### Sign-in limits

Sign-in, recovery-code sign-in and the fresh code a change asks for count failures two ways. The console keeps both counts in memory, so a restart clears them.

- **Per address and name:** 10 failures in 15 minutes from one address (an IPv6 client counts as its /64) for one operator name. Then that address waits out the 15 minutes for that name. Nobody elsewhere is affected, so nobody can lock an operator out this way.
- **Per name, from every address:** this count only includes attempts where the password was right but the code was wrong, plus failed fresh codes from a signed-in session. Past `ADMIN_SIGN_IN_CEILING` such failures (default 50) for one name within an hour, those attempts start one at a time, `ADMIN_SIGN_IN_DELAY_SECS` apart (default 30 s). An attempt that comes sooner gets `429 RATE_LIMITED` with `Retry-After`, and it counts as a failure for its address. Signing in does not reset the count. The ceiling lifts once the hour's failures drop below it. At the defaults, guessing an authenticator code by trial takes months instead of hours, however many addresses the guesser has.

A wrong password never counts toward the name and never waits for its turn. Someone who knows only an operator's name can't slow that operator's sign-in down; the per-address limit handles them.

Reaching the ceiling means someone with the operator's password got the code wrong that often in an hour. Unless it was the operator, someone else has the password. The failure that reaches it writes **Sign-in slowed down** to the audit log, and for 24 hours the Overview lists it under **Needs attention**. If those attempts weren't the operator's, replace the password. Until then, whoever has it competes with the operator for each turn, so the operator's own sign-in can take several tries, or fail for as long as the attempts go on. Another admin can use **Reset authenticator** on the Operators page. That clears the password and the authenticator at once, and the operator sets new ones from the setup link. For the only operator, `./deploy.sh --admin bootstrap --recover` does the same. The old password then stops counting toward the name, which ends the slowdown.

Set the two variables in `.env` and run `./deploy.sh --admin` again. A value that isn't a whole number in range stops the console from starting.

## What to back up

Back up schema `admin` with the database. That schema holds:

| Table | What it stores |
| --- | --- |
| `admin.operators` | Name, role, argon2id password hash, encrypted authenticator secret, enabled flag |
| `admin.operator_sessions` | Session id hash, CSRF token, last use, re-auth deadline |
| `admin.recovery_codes` | Recovery code hashes |
| `admin.audit_log` | Operator actions, each with the operator's name at the time |
| `admin.setup_links` | Setup-link hashes and expiry |

`ADMIN_SECRET_KEY` is not in the database. Keep it with the backup. A restored schema `admin` cannot decrypt authenticator secrets without the same key.

Column grants on the API tables are `admin/api/grants.sql`, applied by the superuser (it makes them as the tables' owner, `shroud_api`). They are not rows in schema `admin`. Apply that file again, as the superuser, if you restore onto a database that does not already have them.

## If grants.sql refuses the audit log

`shroud_admin` owns `admin.audit_log` from the console's first start until `grants.sql` runs: on a first deploy, on a deployment from before that file moved the log, after the 60-second wait for the console's tables ran out, and after a plain `docker compose up`. An owner can attach things to its table that keep working once the table belongs to the superuser, and then run with the superuser's rights or change entries. A rule on the log, for example, can copy `users.password_hash` into a table of the console's own on every entry the console writes. So `grants.sql` refuses to take the log, or to keep it on a later deploy, while it has any of these:

- a rule, a trigger other than the foreign key's own, a row-level security policy, or row-level security turned on;
- an index, default, constraint, extended statistic or column type that uses a function, operator or type from outside `pg_catalog` (an index expression runs as the owner whenever `ANALYZE`, `VACUUM` or `REINDEX` does, autovacuum included);
- a privilege for any role but the superuser and `shroud_admin` (a grant to `PUBLIC` survives the owner change), on the table or on a column;
- inheritance or partitions, or something else in the table's place (a view, an unlogged table).

The deploy stops at that step with `admin.audit_log carries objects that would run or act with the rights of …`, and the `DETAIL` line names each one. The log and schema `admin` stay with whoever owned them. `grants.sql` locks the log and moves it first, then checks, and a refusal undoes the move, so nothing the console's role attaches while the deploy waits for the log gets past the check. The console's own migrations make none of these, so treat the console's database login as stolen:

1. Open `psql` as the superuser: `docker compose exec postgres psql -U <POSTGRES_USER> -d <POSTGRES_DB>`. Look at each named object (`\d+ admin.audit_log`, `\dp admin.audit_log`, `\df+ admin.*`) and at where it writes. If the log already belonged to the superuser (an earlier deploy took it over), the object may have run with the superuser's rights: what it could read may have left the database.
2. Remove each one as the superuser, for example `DROP RULE <name> ON admin.audit_log;`, `DROP TRIGGER <name> ON admin.audit_log;`, `DROP POLICY <name> ON admin.audit_log;`, `ALTER TABLE admin.audit_log DISABLE ROW LEVEL SECURITY;`, `DROP INDEX admin.<name>;` or `REVOKE ALL ON admin.audit_log FROM PUBLIC;`.
3. Give `shroud_admin` a new password (`ALTER ROLE shroud_admin PASSWORD '…';`) and put it in `ADMIN_DB_PASSWORD` and `ADMIN_DATABASE_URL` in `.env`.
4. Run `./deploy.sh --admin` (`.\deploy.ps1 -Admin`) again. It checks the log the same way.

The upgrade that adds the operator name to the log reads `admin.operators`, which `shroud_admin` owns, so the same applies there. A row-level security policy, a view or rule in the table's place, or a function from outside `pg_catalog` in one of its indexes, defaults, constraints, statistics or column types would run as the superuser when it is read (the planner evaluates a constant index predicate as whoever reads the table). `grants.sql` then stops with `admin.operators carries objects that would run with the rights of …` and changes nothing. Handle it the same way, with `\d+ admin.operators` and `DROP POLICY <name> ON admin.operators;`, `ALTER TABLE admin.operators DISABLE ROW LEVEL SECURITY, NO FORCE ROW LEVEL SECURITY;` or `DROP INDEX admin.<name>;`. Until then the console does not start.

## A lost ADMIN_SECRET_KEY

The key encrypts authenticator secrets (AES-256-GCM in `admin.operators.totp_secret_enc`). Password hashes and recovery-code hashes do not use it.

There is no way to read a secret sealed with a key you no longer have. Sign-in with an authenticator code fails, and so does the fresh code a write asks for. Put a new 64-hex key in the environment, restart the console, and re-enrol. Re-enrol does not restore the old authenticator.

## bootstrap and bootstrap --recover

`shroud-admin bootstrap` creates the first operator. The name defaults to `operator`; `--name` chooses another. It prints a one-time setup URL and stores only the hash of the token. The link is valid for 24 hours. Open it to choose a password and an authenticator. The URL is shown once.

If an operator already exists, bootstrap stops and tells you to re-enrol.

`shroud-admin bootstrap --recover` re-enrols the only operator. When more than one exists, pass `--name`. For that operator it clears the password hash and the authenticator secret, deletes sessions and recovery codes, and retires unused setup links. The audit log stays. It then prints a new one-time setup URL.

Both commands need `ADMIN_DATABASE_URL` and `ADMIN_PUBLIC_URL`, and the console's profile has to be on. From the repository:

```
./deploy.sh --admin bootstrap
./deploy.sh --admin bootstrap --recover
./deploy.sh --admin bootstrap --recover --name NAME
```

The script runs the `shroud-admin` binary in a one-off container. The image has no shell. After `--recover`, open the new link and enrol again. Windows: `.\deploy.ps1 -Admin bootstrap` and the same flags.
