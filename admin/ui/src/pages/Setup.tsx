import { Eye, EyeOff, ExternalLink, Link2Off, TriangleAlert } from "lucide-react";
import { useEffect, useState, type FormEvent } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { ApiError, api } from "../api/client";
import type { Setup as SetupData, SetupEnrolled } from "../api/types";
import { AuthLayout, AuthStep, CopyButton, Field, Spinner, useSteps } from "../components/AuthLayout";
import { CodeInput } from "../components/CodeInput";
import { Button } from "../components/ui";

const MIN_PASSWORD = 12;
const LINK_NOTE = "This link works once and expires 15 minutes after it was made";

function secretOf(totpUri: string): string | null {
  try {
    return new URL(totpUri).searchParams.get("secret");
  } catch {
    return null;
  }
}

const grouped = (secret: string) => (secret.match(/.{1,4}/g) ?? [secret]).join(" ");

type Step = "loading" | "gone" | "password" | "authenticator" | "code" | "codes";
const PROGRESS: Partial<Record<Step, number>> = { password: 1, authenticator: 2, code: 3, codes: 4 };

const USED = { title: "This link was already used", body: "Setup links work once. Ask another operator for a new one." };

/** Frames "Set up · Password", "· Authenticator", "· Code", "· Wrong code", "· Recovery codes",
 *  "· Link used" and "Phone · Set up · Authenticator": the one-time link from
 *  `shroud-admin bootstrap` or an operator invite (contract §3.1), one step per screen. The
 *  password and the code go to the server together on step 3. */
export function Setup() {
  const { token = "" } = useParams();
  const navigate = useNavigate();
  const { step, enter, go } = useSteps<Step>("loading");

  const [setup, setSetup] = useState<SetupData | null>(null);
  const [gone, setGone] = useState(USED);
  const [password, setPassword] = useState("");
  const [repeat, setRepeat] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<{ title: string; body: string } | null>(null);
  const [shake, setShake] = useState(0);
  const [codes, setCodes] = useState<string[]>([]);

  useEffect(() => {
    let cancelled = false;
    api<SetupData>(`/setup/${encodeURIComponent(token)}`)
      .then((loaded) => !cancelled && go("password", "fade", () => setSetup(loaded)))
      .catch((error: unknown) => {
        if (cancelled) return;
        const reason =
          error instanceof ApiError && error.code === "LINK_USED"
            ? USED
            : error instanceof ApiError && error.status === 404
              ? { title: "This link isn't valid", body: "It may have expired. Ask another operator for a new one." }
              : { title: "The console couldn't load this link", body: error instanceof Error ? error.message : "Try again." };
        go("gone", "fade", () => setGone(reason));
      });
    return () => {
      cancelled = true;
    };
  }, [token, go]);

  const passwordProblem =
    password.length > 0 && password.length < MIN_PASSWORD
      ? `Use at least ${MIN_PASSWORD} characters.`
      : repeat.length > 0 && repeat !== password
        ? "The two passwords differ."
        : null;
  const passwordReady = password.length >= MIN_PASSWORD && repeat === password;

  const finish = async (totp: string) => {
    if (busy || !passwordReady) return;
    setBusy(true);
    setFailure(null);
    try {
      const enrolled = await api<SetupEnrolled>(`/setup/${encodeURIComponent(token)}`, {
        method: "POST",
        body: { password, totp },
      });
      go("codes", "forward", () => setCodes(enrolled.recovery_codes));
    } catch (error) {
      if (error instanceof ApiError && error.code === "LINK_USED") {
        go("gone", "fade", () => setGone(USED));
      } else {
        setFailure(
          error instanceof ApiError && error.code === "BAD_CREDENTIALS"
            ? { title: "That code didn't match", body: "Enter the current code from your authenticator app." }
            : { title: "Setup didn't finish", body: error instanceof Error ? error.message : "Try again." },
        );
        setCode("");
        setShake((count) => count + 1);
      }
    } finally {
      setBusy(false);
    }
  };

  const enterCode = (value: string) => {
    setCode(value);
    if (value.length === 6) void finish(value);
  };

  const back = (to: Step) => (
    <Button type="button" onClick={() => go(to, "back", () => setFailure(null))} disabled={busy}>
      Back
    </Button>
  );

  const at = PROGRESS[step];
  const name = setup?.operator_name ?? "";
  const note = step === "gone" ? undefined : step === "codes" ? "Next: sign in with your password and a code" : LINK_NOTE;

  return (
    <AuthLayout progress={at ? { at, of: 4 } : undefined} note={note}>
      {step === "loading" ? (
        <AuthStep key="loading" id="loading" enter={enter} title="Set up your sign-in" sub="Checking the link…" />
      ) : step === "gone" ? (
        <AuthStep
          key="gone"
          id="gone"
          enter={enter}
          icon={<Link2Off aria-hidden="true" />}
          title={gone.title}
          sub={gone.body}
          actions={<Button onClick={() => navigate("/sign-in")}>Go to sign-in</Button>}
        />
      ) : step === "password" ? (
        <AuthStep
          key="password"
          id="password"
          enter={enter}
          title="Choose a password"
          sub={`You'll sign in to ${window.location.hostname} as ${name}.`}
          onSubmit={(event: FormEvent) => {
            event.preventDefault();
            if (passwordReady) go("authenticator");
          }}
          actions={
            <Button variant="primary" type="submit" disabled={!passwordReady}>
              Continue
            </Button>
          }
        >
          <input type="text" name="username" autoComplete="username" value={name} readOnly hidden />
          <Field label="Password">
            <span className="input">
              <input
                type={showPassword ? "text" : "password"}
                name="new-password"
                autoComplete="new-password"
                autoFocus
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
          <Field label="Repeat password">
            <span className="input">
              <input
                type={showPassword ? "text" : "password"}
                name="repeat-password"
                autoComplete="new-password"
                value={repeat}
                onChange={(event) => setRepeat(event.target.value)}
              />
            </span>
          </Field>
          <div className={passwordProblem ? "auth-hint auth-hint--problem" : "auth-hint"} aria-live="polite">
            {passwordProblem ?? `At least ${MIN_PASSWORD} characters.`}
          </div>
        </AuthStep>
      ) : step === "authenticator" && setup ? (
        <AuthStep
          key="authenticator"
          id="authenticator"
          enter={enter}
          title="Connect your authenticator"
          sub={
            <>
              <span className="only-wide">
                Scan the code with your authenticator app. It adds Shroud ({name}) and shows a new 6-digit code every 30 seconds.
              </span>
              <span className="only-compact">
                Add Shroud ({name}) to your authenticator app. It then shows a new 6-digit code every 30 seconds.
              </span>
            </>
          }
          onSubmit={(event: FormEvent) => {
            event.preventDefault();
            go("code");
          }}
          actions={
            <>
              {back("password")}
              <Button variant="primary" type="submit">
                Continue
              </Button>
            </>
          }
        >
          <Enrol setup={setup} />
        </AuthStep>
      ) : step === "code" ? (
        <AuthStep
          key="code"
          id="code"
          enter={enter}
          title="Enter the code"
          sub={`Type the 6-digit code your authenticator app shows for Shroud (${name}).`}
          error={failure}
          shake={shake}
          onSubmit={(event: FormEvent) => {
            event.preventDefault();
            if (code.length === 6) void finish(code);
          }}
          actions={
            <>
              {back("authenticator")}
              <Button variant="primary" type="submit" disabled={code.length !== 6 || busy}>
                {busy ? <Spinner /> : null}
                {busy ? "Finishing…" : "Finish setup"}
              </Button>
            </>
          }
        >
          <CodeInput value={code} onChange={enterCode} disabled={busy} autoFocus />
        </AuthStep>
      ) : (
        <AuthStep
          key="codes"
          id="codes"
          enter={enter}
          title="Save your recovery codes"
          sub="If you lose your authenticator, each code signs you in once. They are shown only now."
          actions={
            <>
              <CopyButton text={codes.join("\n")} label="Copy all" className="button button--secondary" />
              <Button variant="primary" onClick={() => navigate("/sign-in", { replace: true })}>
                I saved them
              </Button>
            </>
          }
        >
          <ol className="codes">
            {codes.map((recoveryCode, index) => (
              <li className="codes__code" key={recoveryCode} style={{ "--i": index } as React.CSSProperties}>
                <span className="codes__index" aria-hidden="true">
                  {index + 1}
                </span>
                {recoveryCode}
              </li>
            ))}
          </ol>
          <div className="auth-warning" role="note">
            <TriangleAlert aria-hidden="true" />
            <div>Keep them where only you can read them. A used code stops working.</div>
          </div>
        </AuthStep>
      )}
    </AuthLayout>
  );
}

/** The authenticator step's body: a large QR code and a one-line key on wide screens; on phones,
 *  where the app usually lives on the same device, an `otpauth:` link first and the QR beside the
 *  key below it (only one of the two is displayed). */
function Enrol({ setup }: { setup: SetupData }) {
  const secret = secretOf(setup.totp_uri);
  const qrSrc = `data:image/svg+xml;utf8,${encodeURIComponent(setup.qr_svg)}`;
  return (
    <>
      <div className="enrol enrol--wide">
        <div className="qr-tile qr-tile--lg">
          <img src={qrSrc} alt="QR code for your authenticator app" />
        </div>
        {secret ? (
          <div className="enrol__key">
            <span>Can't scan?</span>
            <code>{grouped(secret)}</code>
            <CopyButton text={secret} />
          </div>
        ) : null}
      </div>
      <div className="enrol enrol--compact">
        <a className="button button--secondary" href={setup.totp_uri}>
          <ExternalLink aria-hidden="true" />
          Open in authenticator app
        </a>
        <div className="enrol__or">or from another device</div>
        <div className="enrol__panel">
          <div className="qr-tile qr-tile--sm">
            <img src={qrSrc} alt="QR code for your authenticator app" />
          </div>
          <div className="enrol__text">
            <div className="enrol__title">Scan with your authenticator app</div>
            {secret ? (
              <>
                <div className="enrol__divider" />
                <div className="enrol__key-head">
                  <span>Or enter this key</span>
                  <CopyButton text={secret} />
                </div>
                <code>{grouped(secret)}</code>
              </>
            ) : null}
          </div>
        </div>
      </div>
    </>
  );
}
