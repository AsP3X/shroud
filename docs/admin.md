# Admin console operations

The console is the `admin` Compose service (`shroud-admin`). It uses role `shroud_admin` and schema `admin` in the API's Postgres database. `ADMIN_SECRET_KEY` is 64 hex characters. The console will not start while `ADMIN_DATABASE_URL` is set and the key is missing or not that length.

## Turning the console off and on

For a while, without touching `.env`:

```
docker compose --profile admin stop admin
docker compose --profile admin start admin
```

The next `./deploy.sh` starts it again, because the `admin` profile is still on.

Until you want it back, so deploys leave it off:

```
./deploy.sh --init
```

Answer `n` at "Enable the admin console?". The wizard drops the `admin` profile and the next
deploy removes the container, but keeps `ADMIN_SECRET_KEY`, the console's database password
and the operator token in `.env`. Operators, their authenticators and the audit log stay in
schema `admin`. To turn it back on, run `./deploy.sh --init` again and answer `y`: the same key
reads the same secrets, and everyone signs in as before.

While the profile is off, the API still binds its internal operator port, since
`OPERATOR_TOKEN` is set; nothing publishes that port and nothing calls it. Blank the token in
`.env` and redeploy if you want the API not to bind it at all.

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

Both commands need `ADMIN_DATABASE_URL` and `ADMIN_PUBLIC_URL`. From the repository, with the admin profile:

```
docker compose --profile admin run --rm --entrypoint /shroud-admin admin bootstrap
docker compose --profile admin run --rm --entrypoint /shroud-admin admin bootstrap --recover
docker compose --profile admin run --rm --entrypoint /shroud-admin admin bootstrap --recover --name NAME
```

The image has no shell. `--entrypoint` runs the binary. After `--recover`, open the new link and enrol again.
