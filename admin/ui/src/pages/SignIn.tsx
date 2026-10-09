import { Eye, EyeOff } from "lucide-react";
import { useState, type FormEvent } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { ApiError, api } from "../api/client";
import { AuthLayout, AuthStep, Field, Spinner, useSteps } from "../components/AuthLayout";
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
        return { title: "Those details didn't match", body: "Check the operator name and password, then enter a fresh code." };
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

type Step = "credentials" | "code" | "recovery";

/** Frames "Sign in", "Sign in · Code", "Sign in · Recovery code" and "Sign in · Error". The
 *  server checks the name, the password and the code together, so the first screen only collects
 *  them; a wrong name or password comes back after the code and returns to the first screen. */
export function SignIn() {
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: string } | null)?.from ?? "/";
  const { step, enter, go } = useSteps<Step>("credentials");

  const [operator, setOperator] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [code, setCode] = useState("");
  const [recoveryCode, setRecoveryCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [shake, setShake] = useState(0);

  const name = operator.trim();

  const toCode = (event: FormEvent) => {
    event.preventDefault();
    if (!name || !password) return;
    go("code", "forward", () => setFailure(null));
  };

  const signIn = async (secret: { totp: string } | { recovery_code: string }) => {
    if (busy) return;
    setBusy(true);
    setFailure(null);
    try {
      if ("totp" in secret) {
        await api<void>("/session", { method: "POST", body: { operator: name, password, totp: secret.totp } });
      } else {
        await api<void>("/session/recovery", { method: "POST", body: { operator: name, password, recovery_code: secret.recovery_code } });
      }
      navigate(from, { replace: true });
    } catch (error) {
      const next = describe(error);
      if (error instanceof ApiError && error.code === "BAD_CREDENTIALS") {
        go("credentials", "back", () => {
          setFailure(next);
          setCode("");
          setRecoveryCode("");
        });
      } else {
        setFailure(next);
        setCode("");
        setShake((count) => count + 1);
      }
    } finally {
      setBusy(false);
    }
  };

  const enterCode = (value: string) => {
    setCode(value);
    if (value.length === 6) void signIn({ totp: value });
  };

  const back = (
    <Button type="button" onClick={() => go(step === "recovery" ? "code" : "credentials", "back", () => setFailure(null))} disabled={busy}>
      Back
    </Button>
  );
  const submit = (ready: boolean) => (
    <Button variant="primary" type="submit" disabled={!ready || busy}>
      {busy ? <Spinner /> : null}
      {busy ? "Signing in…" : "Sign in"}
    </Button>
  );

  return (
    <AuthLayout note="Sessions end after 30 minutes without activity">
      {step === "credentials" ? (
        <AuthStep
          key="credentials"
          id="credentials"
          enter={enter}
          title="Sign in"
          sub="For server operators only. Chat accounts can't sign in here."
          error={failure}
          onSubmit={toCode}
          actions={
            <Button variant="primary" type="submit" disabled={!name || !password}>
              Continue
            </Button>
          }
        >
          <Field label="Operator">
            <span className="input input--mono">
              <input
                name="username"
                autoComplete="username"
                autoCapitalize="none"
                spellCheck={false}
                autoFocus={!name}
                value={operator}
                onChange={(event) => setOperator(event.target.value)}
              />
            </span>
          </Field>
          <Field label="Password">
            <span className="input">
              <input
                name="password"
                type={showPassword ? "text" : "password"}
                autoComplete="current-password"
                autoFocus={Boolean(name)}
                value={password}
                onChange={(event) => setPassword(event.target.value)}
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
        </AuthStep>
      ) : step === "code" ? (
        <AuthStep
          key="code"
          id="code"
          enter={enter}
          title="Enter your code"
          sub={`Open your authenticator app and type the 6-digit code for Shroud (${name}).`}
          error={failure}
          shake={shake}
          onSubmit={(event) => {
            event.preventDefault();
            if (code.length === 6) void signIn({ totp: code });
          }}
          actions={
            <>
              {back}
              {submit(code.length === 6)}
            </>
          }
          below={
            <button type="button" className="link-button" onClick={() => go("recovery", "forward", () => setFailure(null))}>
              Use a recovery code instead
            </button>
          }
        >
          <CodeInput value={code} onChange={enterCode} disabled={busy} autoFocus />
        </AuthStep>
      ) : (
        <AuthStep
          key="recovery"
          id="recovery"
          enter={enter}
          title="Use a recovery code"
          sub="Enter one of the codes you saved during setup. Each code works once."
          error={failure}
          shake={shake}
          onSubmit={(event) => {
            event.preventDefault();
            if (recoveryCode.trim()) void signIn({ recovery_code: recoveryCode.trim() });
          }}
          actions={
            <>
              {back}
              {submit(Boolean(recoveryCode.trim()))}
            </>
          }
          below={
            <button type="button" className="link-button" onClick={() => go("code", "back", () => setFailure(null))}>
              Use your authenticator app instead
            </button>
          }
        >
          <Field label="Recovery code">
            <span className="input input--mono">
              <input
                name="recovery-code"
                autoComplete="off"
                autoCapitalize="none"
                spellCheck={false}
                placeholder="xxxx-xxxx-xxxx-xxxx"
                autoFocus
                value={recoveryCode}
                onChange={(event) => setRecoveryCode(event.target.value)}
                readOnly={busy}
              />
            </span>
          </Field>
        </AuthStep>
      )}
    </AuthLayout>
  );
}
