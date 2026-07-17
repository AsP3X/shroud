# Server test fixtures

Archived protocol / schema artifacts for regression backtests (`regression-testing.mdc`).

| Path | Purpose |
| --- | --- |
| *(future)* `envelopes/vN-*.json` | Serialized ciphertext envelopes / API payloads from released protocol versions |
| *(future)* `schema/previous-release.sql` | Seeded dump of the prior release schema for migration forward-tests |

The `migrations_backtest` integration test applies all current forward migrations on a fresh Postgres and asserts core tables exist. When cutting a release that changes schema semantics, archive a dump of the previous schema here and extend that test to migrate from the dump.
