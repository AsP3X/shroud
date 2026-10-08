import { Check, Copy } from "lucide-react";
import { useEffect, useState, type FormEvent } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { ApiError, api } from "../api/client";
import type { Setup as SetupData, SetupEnrolled } from "../api/types";
import { AuthCard, AuthFootnote, AuthLayout, ErrorBox, Field } from "../components/AuthLayout";
import { CodeInput } from "../components/CodeInput";
import { Button } from "../components/ui";

const MIN_PASSWORD = 12;

function secretOf(totpUri: string): string | null {
  try {
    const secret = new URL(totpUri).searchParams.get("secret");
    return secret ? (secret.match(/.{1,4}/g) ?? [secret]).join(" ") : null;
  } catch {
    return null;
  }
}

type State =
  | { kind: "loading" }
  | { kind: "gone"; title: string; body: string }
  | { kind: "form"; setup: SetupData }
  | { kind: "codes"; codes: string[] };

/** Frames "Set up sign-in" and "Save your recovery codes": the one-time link from
 *  `shroud-admin bootstrap` or an operator invite (contract §3.1). */
export function Setup() {
  const { token = "" } = useParams();
  const navigate = useNavigate();
  const [state, setState] = useState<State>({ kind: "loading" });
  const [password, setPassword] = useState("");
  const [repeat, setRepeat] = useState("");
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<{ title: string; body: string } | null>(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api<SetupData>(`/setup/${encodeURIComponent(token)}`)
      .then((setup) => !cancelled && setState({ kind: "form", setup }))
      .catch((error: unknown) => {
        if (cancelled) return;
        if (error instanceof ApiError && error.code === "LINK_USED") {
          setState({ kind: "gone", title: "This link was already used", body: "Ask another operator for a new one." });
        } else if (error instanceof ApiError && error.status === 404) {
          setState({ kind: "gone", title: "This link isn't valid", body: "It may have expired. Ask another operator for a new one." });
        } else {
          setState({ kind: "gone", title: "The console couldn't load this link", body: error instanceof Error ? error.message : "Try again." });
        }
      });
    return () => {
      cancelled = true;
    };
  }, [token]);

  const passwordProblem =
    password.length > 0 && password.length < MIN_PASSWORD
      ? `Use at least ${MIN_PASSWORD} characters.`
      : repeat.length > 0 && repeat !== password
        ? "The two passwords differ."
        : null;
  const complete = password.length >= MIN_PASSWORD && repeat === password && code.length === 6;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!complete || busy) return;
    setBusy(true);
    setFailure(null);
    try {
      const enrolled = await api<SetupEnrolled>(`/setup/${encodeURIComponent(token)}`, {
        method: "POST",
        body: { password, totp: code },
      });
      setState({ kind: "codes", codes: enrolled.recovery_codes });
    } catch (error) {
      if (error instanceof ApiError && error.code === "LINK_USED") {
        setState({ kind: "gone", title: "This link was already used", body: "Ask another operator for a new one." });
      } else if (error instanceof ApiError && error.code === "BAD_CREDENTIALS") {
        setFailure({ title: "That code didn't match", body: "Enter the current code from your authenticator app." });
        setCode("");
      } else {
        setFailure({ title: "Setup didn't finish", body: error instanceof Error ? error.message : "Try again." });
      }
    } finally {
      setBusy(false);
    }
  };

  const copyCodes = async (codes: string[]) => {
    try {
      await navigator.clipboard.writeText(codes.join("\n"));
      setCopied(true);
    } catch {
      setCopied(false);
    }
  };

  if (state.kind === "loading") {
    return (
      <AuthLayout>
        <AuthCard title="Set up your sign-in" sub="Checking the link…">
          <div />
        </AuthCard>
      </AuthLayout>
    );
  }

  if (state.kind === "gone") {
    return (
      <AuthLayout>
        <AuthCard title={state.title} sub={state.body}>
          <Button className="button button--secondary button--block" onClick={() => navigate("/sign-in")}>
            Go to sign-in
          </Button>
        </AuthCard>
      </AuthLayout>
    );
  }

  if (state.kind === "codes") {
    return (
      <AuthLayout>
        <AuthCard
          title="Save your recovery codes"
          sub="Each code signs you in once if you lose your authenticator. They are shown only now."
        >
          <div className="codes" role="list">
            {state.codes.map((recoveryCode) => (
              <div className="codes__code" role="listitem" key={recoveryCode}>
                {recoveryCode}
              </div>
            ))}
          </div>
          <div className="auth-actions">
            <Button icon={copied ? Check : Copy} onClick={() => void copyCodes(state.codes)}>
              {copied ? "Copied" : "Copy"}
            </Button>
            <Button variant="primary" onClick={() => navigate("/sign-in", { replace: true })}>
              I saved them
            </Button>
          </div>
          <AuthFootnote>Keep them where only you can read them. A used code stops working.</AuthFootnote>
        </AuthCard>
      </AuthLayout>
    );
  }

  const { setup } = state;
  const secret = secretOf(setup.totp_uri);
  const qrSrc = `data:image/svg+xml;utf8,${encodeURIComponent(setup.qr_svg)}`;

  return (
    <AuthLayout>
      <AuthCard
        title="Set up your sign-in"
        sub={`You're ${setup.operator_name}. Choose a password and add Shroud Admin to your authenticator app.`}
      >
        {failure ? <ErrorBox title={failure.title} body={failure.body} /> : null}
        <form className="auth-form" onSubmit={submit}>
          <Field label="Password">
            <span className="input">
              <input
                type="password"
                name="new-password"
                autoComplete="new-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                disabled={busy}
              />
            </span>
          </Field>
          <Field label="Repeat password">
            <span className="input">
              <input
                type="password"
                name="repeat-password"
                autoComplete="new-password"
                value={repeat}
                onChange={(event) => setRepeat(event.target.value)}
                disabled={busy}
              />
            </span>
          </Field>
          {passwordProblem ? <div className="small text-tertiary">{passwordProblem}</div> : null}
          <div className="qr">
            <div className="qr__image">
              <img src={qrSrc} alt="QR code for your authenticator app" width={120} height={120} />
            </div>
            <div className="qr__text">
              <div>Scan this with your authenticator app, then enter the code it shows.</div>
              {secret ? (
                <>
                  <div>Can't scan? Enter this key:</div>
                  <div className="qr__key">{secret}</div>
                </>
              ) : null}
            </div>
          </div>
          <div className="field">
            <span className="field__label">
              <span>Authenticator code</span>
            </span>
            <CodeInput value={code} onChange={setCode} disabled={busy} />
          </div>
          <Button variant="primary" type="submit" disabled={!complete || busy} className="button button--primary button--block">
            {busy ? "Finishing…" : "Finish setup"}
          </Button>
        </form>
        <AuthFootnote>This link works once and expires 15 minutes after it was made.</AuthFootnote>
      </AuthCard>
    </AuthLayout>
  );
}
