# Admin console operations

The console is the `admin` Compose service (`shroud-admin`). It uses role `shroud_admin` and schema `admin` in the API's Postgres database. `ADMIN_SECRET_KEY` is 64 hex characters. The console will not start while `ADMIN_DATABASE_URL` is set and the key is missing or not that length.

## Deploying the console

The API and web client come up with `./deploy.sh`. The console is its own command, and it does not run the setup wizard:

```
./deploy.sh --admin
```

That turns the console on and deploys it. A secret already in `.env` is kept. Open it at the Shroud site's address plus `/admin`. If the site is `https://shroud-app.com`, the console is `https://shroud-app.com/admin`. Local mode is the same rule: the web URL in `.env`, plus `/admin`. There is no separate admin host and no extra port to publish.

The web client proxies `/admin` and `/api/admin` to the console container. While the console is off, those paths do not answer.

The sign-in cookie is `__Host-admin`. Browsers require that name to use `Path=/` and to omit `Domain`, so it is sent to the whole site. It is `HttpOnly`. The messenger cannot read it, and the API ignores it.

Create the first operator after that. The link is shown once:

```
./deploy.sh --admin bootstrap
```

Open it and choose a password and an authenticator.

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

While the profile is off, the API still binds its internal operator port when `OPERATOR_TOKEN` is set. Nothing publishes that port and nothing calls it. Blank the token in `.env` and redeploy if the API should not bind it.

## What to back up

Back up schema `admin` with the database. That schema holds:

| Table | What it stores |
| --- | --- |
| `admin.operators` | Name, role, argon2id password hash, encrypted authenticator secret, enabled flag |
| `admin.operator_sessions` | Session id hash, CSRF token, last use, re-auth deadline |
| `admin.recovery_codes` | Recovery code hashes |
| `admin.audit_log` | Operator actions |
| `admin.setup_links` | Setup-link hashes and expiry |

`ADMIN_SECRET_KEY` is not in the database. Keep it with the backup. A restored schema `admin` cannot decrypt authenticator secrets without the same key.

Column grants on the API tables are `admin/api/grants.sql`, applied by the table owner. They are not rows in schema `admin`. Apply that file again, as the table owner, if you restore onto a database that does not already have them.

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
