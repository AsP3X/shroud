// A fake shroud-admin backend for `npm run dev:fixtures`: answers every /api/admin route from
// admin/api/fixtures (docs/admin-plan.md §3.8) so the UI can be built before the backend exists.
//
// Switches, all in memory and reset on restart:
//   GET /__fixtures                       show the current switches
//   GET /__fixtures?latency=1500          delay every answer by that many ms
//   GET /__fixtures?fail=upstream-postgres answer every data route with error.<name>.json
//   GET /__fixtures?state=database-down   pick that `?state=` variant for every route that has it
//   GET /__fixtures?signout               forget the fake session (sign-in page)
//   GET /__fixtures?role=read             the fake operator's role
//   GET /__fixtures?reset                 back to defaults
// A request's own `?state=<name>` wins over the global one; `?state=error:<name>` answers with
// that error file and its status, which is how the frames' "Couldn't load" states are shown.
import { readFileSync } from "node:fs";
import { join } from "node:path";
import type { IncomingMessage, ServerResponse } from "node:http";
import type { Plugin } from "vite";

interface RouteEntry {
  method: string;
  path: string;
  file?: string;
  states?: Record<string, string>;
}

interface RoutesFile {
  routes: RouteEntry[];
  no_body: string[];
}

interface Switches {
  latency: number;
  fail: string | null;
  state: string | null;
  signedIn: boolean;
  role: "read" | "write";
  reauthUntil: number | null;
}

const defaults = (): Switches => ({
  latency: 0,
  fail: null,
  state: null,
  signedIn: true,
  role: "write",
  reauthUntil: null,
});

/** Status for each error fixture, by the `code` the contract gives it (§3). */
const errorStatus: Record<string, number> = {
  "bad-credentials": 401,
  unauthenticated: 401,
  "reauth-required": 403,
  forbidden: 403,
  "link-used": 410,
  "already-done": 409,
  "rate-limited": 429,
  "upstream-api": 502,
  "upstream-postgres": 502,
  validation: 400,
};

const REAUTH_WINDOW_MS = 5 * 60 * 1000;
const API_PREFIX = "/api/admin";

export function fixtureServer(fixturesDir: string): Plugin {
  const routes = JSON.parse(readFileSync(join(fixturesDir, "routes.json"), "utf8")) as RoutesFile;
  const matchers = routes.routes.map((route) => ({
    route,
    regex: new RegExp("^" + route.path.replace(/\{[^}]+\}/g, "[^/]+") + "$"),
  }));
  const noBody = routes.no_body.map((line) => {
    const [method, path] = line.split(" ") as [string, string];
    return { method, regex: new RegExp("^" + path.replace(/\{[^}]+\}/g, "[^/]+") + "$") };
  });
  let switches = defaults();

  const file = (name: string): unknown => JSON.parse(readFileSync(join(fixturesDir, name), "utf8"));

  const send = (res: ServerResponse, status: number, body?: unknown, headers: Record<string, string> = {}) => {
    res.statusCode = status;
    res.setHeader("Cache-Control", "no-store");
    for (const [key, value] of Object.entries(headers)) res.setHeader(key, value);
    if (body === undefined) {
      res.end();
      return;
    }
    res.setHeader("Content-Type", "application/json; charset=utf-8");
    res.end(JSON.stringify(body));
  };

  const sendError = (res: ServerResponse, name: string) => {
    const status = errorStatus[name] ?? 500;
    send(res, status, file(`error.${name}.json`), status === 429 ? { "Retry-After": "60" } : {});
  };

  const handleSwitches = (url: URL, res: ServerResponse) => {
    const q = url.searchParams;
    if (q.has("reset")) switches = defaults();
    if (q.has("latency")) switches.latency = Math.max(0, Number(q.get("latency")) || 0);
    if (q.has("fail")) switches.fail = q.get("fail") || null;
    if (q.has("state")) switches.state = q.get("state") || null;
    if (q.has("signout")) {
      switches.signedIn = false;
      switches.reauthUntil = null;
    }
    if (q.has("signin")) switches.signedIn = true;
    if (q.has("role")) switches.role = q.get("role") === "read" ? "read" : "write";
    send(res, 200, switches);
  };

  const handle = async (req: IncomingMessage, res: ServerResponse) => {
    const url = new URL(req.url ?? "/", "http://fixtures.local");
    const method = (req.method ?? "GET").toUpperCase();
    const path = url.pathname.slice(API_PREFIX.length) || "/";
    if (switches.latency > 0) await new Promise((resolve) => setTimeout(resolve, switches.latency));

    const requested = url.searchParams.get("state");
    if (requested?.startsWith("error:")) return sendError(res, requested.slice("error:".length));
    if (switches.fail) return sendError(res, switches.fail);

    if (path.startsWith("/session")) {
      if (method === "GET" && path === "/session") {
        if (!switches.signedIn) return sendError(res, "unauthenticated");
        const session = file("session.json") as { operator: { role: string }; reauth_until: string | null };
        session.operator.role = switches.role;
        session.reauth_until =
          switches.reauthUntil && switches.reauthUntil > Date.now() ? new Date(switches.reauthUntil).toISOString() : null;
        return send(res, 200, session);
      }
      if (method === "POST" && (path === "/session" || path === "/session/recovery")) {
        switches.signedIn = true;
        return send(res, 204, undefined, { "Set-Cookie": "admin_csrf=fixture; Path=/; SameSite=Strict" });
      }
      if (method === "DELETE" && path === "/session") {
        switches.signedIn = false;
        switches.reauthUntil = null;
        return send(res, 204);
      }
      if (method === "POST" && path === "/session/reauth") {
        if (!switches.signedIn) return sendError(res, "unauthenticated");
        switches.reauthUntil = Date.now() + REAUTH_WINDOW_MS;
        return send(res, 200, { reauth_until: new Date(switches.reauthUntil).toISOString() });
      }
    }

    const isSetup = path.startsWith("/setup/");
    if (!isSetup && !switches.signedIn) return sendError(res, "unauthenticated");

    const write = noBody.find((entry) => entry.method === method && entry.regex.test(path));
    if (write) {
      if (switches.role !== "write") return sendError(res, "forbidden");
      if (!switches.reauthUntil || switches.reauthUntil < Date.now()) return sendError(res, "reauth-required");
      return send(res, 204);
    }

    const match = matchers.find(({ route, regex }) => route.method === method && regex.test(path));
    if (!match?.route.file) return send(res, 404, { code: "NOT_FOUND", message: "No such route in the fixtures." });
    if (method !== "GET" && switches.role !== "write" && path.startsWith("/operators")) return sendError(res, "forbidden");
    const state = requested ?? switches.state;
    const name = (state && match.route.states?.[state]) || match.route.file;
    return send(res, 200, file(name));
  };

  return {
    name: "shroud-admin-fixtures",
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const url = req.url ?? "/";
        if (url.startsWith("/__fixtures")) return handleSwitches(new URL(url, "http://fixtures.local"), res);
        if (!url.startsWith(API_PREFIX)) return next();
        handle(req, res).catch((error: unknown) => {
          send(res, 500, { code: "FIXTURE_ERROR", message: error instanceof Error ? error.message : String(error) });
        });
      });
    },
  };
}
