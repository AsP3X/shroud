import { ShieldCheck } from "lucide-react";
import { useCallback, useState, type FormEvent } from "react";
import { ApiError, api } from "../api/client";
import type { Reauth as ReauthData, Session } from "../api/types";
import { useSession } from "./Shell";
import { CodeInput } from "./CodeInput";
import { Dialog } from "./Dialog";
import { Button } from "./ui";

/** Frame "User detail · Confirm it's you": a fresh code before a write (contract §3.1, R4). */
export function ReauthDialog({ what, onDone, onCancel }: { what: string; onDone: () => void; onCancel: () => void }) {
  const { session, refresh } = useSession();
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const minutesSince = sinceLastCode(session);
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (code.length !== 6 || busy) return;
    setBusy(true);
    setError(null);
    try {
      await api<ReauthData>("/session/reauth", { method: "POST", body: { totp: code } });
      await refresh();
      onDone();
    } catch (failure) {
      setCode("");
      setError(failure instanceof ApiError && failure.code === "BAD_CREDENTIALS" ? "That code didn't match. Enter the current one." : failure instanceof Error ? failure.message : "Try again.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog
      icon={ShieldCheck}
      tone="accent"
      title="Confirm it's you"
      body={`${what} needs a fresh code.${minutesSince === null ? "" : ` Your last one was entered ${minutesSince} minutes ago.`}`}
      onClose={onCancel}
      actions={
        <>
          <Button onClick={onCancel} disabled={busy}>
            Cancel
          </Button>
          <Button variant="primary" icon={ShieldCheck} type="submit" form="reauth-form" disabled={code.length !== 6 || busy}>
            {busy ? "Checking…" : "Confirm"}
          </Button>
        </>
      }
    >
      <form id="reauth-form" className="field" onSubmit={submit}>
        <span className="field__label">
          <span>Authenticator code</span>
        </span>
        <CodeInput value={code} onChange={setCode} disabled={busy} />
        {error ? (
          <div className="small" style={{ color: "var(--danger)" }} role="alert">
            {error}
          </div>
        ) : null}
      </form>
    </Dialog>
  );
}

function sinceLastCode(session: Session | null): number | null {
  if (!session) return null;
  // reauth_until is five minutes after the last code; before that window started is unknown.
  if (!session.reauth_until) return null;
  const last = new Date(session.reauth_until).getTime() - 5 * 60_000;
  return Math.max(0, Math.round((Date.now() - last) / 60_000));
}

export type WriteOutcome = { ok: true } | { ok: false; title: string; body: string };

/** Runs a write; a 403 REAUTH_REQUIRED asks for a code and retries once. */
export function useWrite() {
  const [pending, setPending] = useState<{ what: string; run: () => Promise<void>; resolve: (outcome: WriteOutcome) => void } | null>(null);

  const attempt = useCallback(async (run: () => Promise<void>): Promise<WriteOutcome> => {
    try {
      await run();
      return { ok: true };
    } catch (error) {
      if (error instanceof ApiError) {
        if (error.code === "REAUTH_REQUIRED") throw error;
        if (error.code === "ALREADY_DONE") return { ok: false, title: "Already done", body: error.message };
        if (error.code === "FORBIDDEN") return { ok: false, title: "Your role can't do this", body: error.message };
        if (error.code === "UPSTREAM") return { ok: false, title: error.upstream === "api" ? "The API didn't take the change" : "The console can't reach its database", body: `${error.message} The audit log records the attempt as failed.` };
        return { ok: false, title: "That didn't work", body: error.message };
      }
      return { ok: false, title: "That didn't work", body: "Check your connection and try again." };
    }
  }, []);

  const write = useCallback(
    (what: string, run: () => Promise<void>): Promise<WriteOutcome> =>
      attempt(run).catch(
        () =>
          new Promise<WriteOutcome>((resolve) => {
            setPending({ what, run, resolve });
          }),
      ),
    [attempt],
  );

  const dialog = pending ? (
    <ReauthDialog
      what={pending.what}
      onCancel={() => {
        pending.resolve({ ok: false, title: "Cancelled", body: "Nothing was changed." });
        setPending(null);
      }}
      onDone={() => {
        const { run, resolve } = pending;
        setPending(null);
        void attempt(run)
          .catch(() => ({ ok: false, title: "Still not confirmed", body: "The code was accepted but the write asked for another one. Try again." }) as WriteOutcome)
          .then(resolve);
      }}
    />
  ) : null;

  return { write, reauthDialog: dialog };
}
