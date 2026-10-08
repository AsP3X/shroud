# Shroud admin console — frontend

The operator console's UI (docs/admin-plan.md §5). A Vite + React + TypeScript single-page app
that the `shroud-admin` binary serves from `dist/`. It shares no code with `web/`.

```bash
npm install
npm run dev:fixtures   # the UI on http://localhost:5174 against admin/api/fixtures
npm run dev            # against a running shroud-admin (ADMIN_API_URL, default http://127.0.0.1:8082)
npm run build          # tsc -b, then dist/
```

## Working against the fixtures

`dev-server.ts` answers every `/api/admin` route from `admin/api/fixtures` (the contract's
examples). Switches live in memory:

| URL | Effect |
| --- | ------ |
| `/__fixtures` | show the switches |
| `/__fixtures?latency=1500` | delay every answer (the frames' loading states) |
| `/__fixtures?fail=upstream-postgres` | answer every data route with that error fixture |
| `/__fixtures?state=database-down` | pick that variant wherever a route has one |
| `/__fixtures?signout` · `?signin` | forget or restore the fake session |
| `/__fixtures?role=read` | the fake operator's role |
| `/__fixtures?reset` | defaults |

A page's own `?state=` reaches the fixtures too, so `/users?state=empty` renders the "No results"
frame and `/users?state=error:upstream-postgres` the "Couldn't load" one. Production builds never
send it.

## Layout

- `src/api/` — the contract's types and the one `api()` call that every page uses.
- `src/components/` — the shell (sidebar, phone top bar, drawer) and the components of the
  design's Components frame.
- `src/pages/` — one route per frame. `/gallery` shows each component beside its frame's values.
- `src/styles/tokens.css` — the `.pen` variables as custom properties, dark and light.
