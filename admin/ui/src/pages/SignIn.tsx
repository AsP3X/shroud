import { Eye, EyeOff } from "lucide-react";
import { useState, type FormEvent } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { ApiError, api } from "../api/client";
import { AuthCard, AuthFootnote, AuthLayout, ErrorBox, Field } from "../components/AuthLayout";
import { CodeInput } from "../components/CodeInput";
import { Button } from "../components/ui";

interface Failure {
  title: string;
  body: string;
}

function describe(error: unknown): Failure {
  if (error instanceof ApiError) {
    switch (error.code) {
      case "BAD_CREDENTIALS":
        return { title: "Those details didn't match", body: "Check the operator name and password, and use a fresh code." };
      case "RATE_LIMITED": {
        const minutes = Math.max(1, Math.ceil((error.retryAfterSecs ?? 900) / 60));
        return { title: "Too many attempts", body: `Sign-in is locked. Try again in ${minutes} min.` };
      }
      case "UPSTREAM":
        return {
          title: error.upstream === "postgres" ? "The console can't reach its database" : "The console can't reach the API",
          body: error.message,
        };
      default:
        return { title: "Sign-in didn't work", body: error.message };
    }
  }
  return { title: "Sign-in didn't work", body: "Check your connection and try again." };
}

/** Frames "Sign in" and "Sign in · Error". */
export function SignIn() {
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: string } | null)?.from ?? "/";

  const [operator, setOperator] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [code, setCode] = useState("");
  const [recovery, setRecovery] = useState(false);
  const [recoveryCode, setRecoveryCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);

  const host = window.location.hostname;
  const complete = operator.trim() && password && (recovery ? recoveryCode.trim() : code.length === 6);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!complete || busy) return;
    setBusy(true);
    setFailure(null);
    try {
      if (recovery) {
        await api<void>("/session/recovery", {
          method: "POST",
          body: { operator: operator.trim(), password, recovery_code: recoveryCode.trim() },
        });
      } else {
        await api<void>("/session", { method: "POST", body: { operator: operator.trim(), password, totp: code } });
      }
      navigate(from, { replace: true });
    } catch (error) {
      setFailure(describe(error));
      setCode("");
      setRecoveryCode("");
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthLayout>
      <AuthCard title="Server admin" sub={`Operator access to ${host}. Shroud chat accounts can't sign in here.`}>
        {failure ? <ErrorBox title={failure.title} body={failure.body} /> : null}
        <form className="auth-form" onSubmit={submit}>
          <Field label="Operator">
            <span className="input input--mono">
              <input
                name="username"
                autoComplete="username"
                autoCapitalize="none"
                spellCheck={false}
                value={operator}
                onChange={(event) => setOperator(event.target.value)}
                disabled={busy}
              />
            </span>
          </Field>
          <Field label="Password">
            <span className="input">
              <input
                name="password"
                type={showPassword ? "text" : "password"}
                autoComplete="current-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                disabled={busy}
              />
              <button
                type="button"
                className="input__icon"
                onClick={() => setShowPassword((shown) => !shown)}
                aria-label={showPassword ? "Hide password" : "Show password"}
                aria-pressed={showPassword}
              >
                {showPassword ? <EyeOff aria-hidden="true" /> : <Eye aria-hidden="true" />}
              </button>
            </span>
          </Field>
          {recovery ? (
            <Field
              label="Recovery code"
              action={
                <button type="button" onClick={() => setRecovery(false)}>
                  Use the authenticator
                </button>
              }
            >
              <span className="input input--mono">
                <input
                  name="recovery-code"
                  autoComplete="off"
                  autoCapitalize="none"
                  spellCheck={false}
                  placeholder="xxxx-xxxx"
                  value={recoveryCode}
                  onChange={(event) => setRecoveryCode(event.target.value)}
                  disabled={busy}
                />
              </span>
            </Field>
          ) : (
            <div className="field">
              <span className="field__label">
                <span>Authenticator code</span>
                <button type="button" onClick={() => setRecovery(true)}>
                  Use a recovery code
                </button>
              </span>
              <CodeInput value={code} onChange={setCode} disabled={busy} />
            </div>
          )}
          <Button variant="primary" type="submit" disabled={!complete || busy} className="button button--primary button--block">
            {busy ? "Signing in…" : "Sign in"}
          </Button>
        </form>
        <AuthFootnote>Served on the admin port only. Sessions end after 30 minutes without activity.</AuthFootnote>
      </AuthCard>
    </AuthLayout>
  );
}
