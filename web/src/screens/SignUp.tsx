import { useMemo, useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { TriangleAlert } from "lucide-react";
import { api, ApiError } from "../api/client";
import { generateMnemonic, PhraseError, WORD_COUNT } from "../crypto/bip39";
import { establish, putBundleRequest } from "../crypto/identity";
import { evaluatePassword } from "../crypto/password";
import { saveIdentity } from "../crypto/store";
import { AuthLayout } from "../components/auth/AuthLayout";
import { PasswordField, RuleList, StrengthMeter, TextField } from "../components/auth/Fields";
import { PhraseDisplay } from "../components/auth/Phrase";
import { hasPin } from "../crypto/vaultAccess";
import { markFreshSignIn } from "../deviceNaming";
import { saveSession } from "../session";

const USERNAME_RE = /^[a-zA-Z0-9_]{3,32}$/;
/** How many words we ask back before the account is created. */
const CHECKS = 3;

function pickCheckIndices(): number[] {
  const all = Array.from({ length: WORD_COUNT }, (_, i) => i);
  for (let i = all.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [all[i], all[j]] = [all[j], all[i]];
  }
  return all.slice(0, CHECKS).sort((a, b) => a - b);
}

export function SignUp() {
  const navigate = useNavigate();
  const words = useMemo(() => generateMnemonic(), []);
  const checkIndices = useMemo(pickCheckIndices, []);
  const [stepIndex, setStepIndex] = useState(0);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [answers, setAnswers] = useState<Record<number, string>>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const strength = evaluatePassword(password);
  const usernameOk = USERNAME_RE.test(username.trim());
  const accountOk = usernameOk && strength.meetsRequirements;
  const checksFilled = checkIndices.every((index) => (answers[index] ?? "").trim().length > 0);
  const checksOk = checkIndices.every(
    (index) => (answers[index] ?? "").trim().toLowerCase() === words[index],
  );

  async function createAccount(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    if (!checksOk) {
      setError("Those words don’t match the phrase you were shown.");
      return;
    }
    setError(null);
    setBusy(true);
    try {
      const session = await api.register(username.trim().toLowerCase(), password);
      saveSession(session);
      markFreshSignIn();
      const material = establish(words, session.user.id);
      saveIdentity(material);
      try {
        await api.putBundle(session.token, putBundleRequest(material));
      } catch {
        // Local keys are stored; the next login phrase step retries the upload.
      }
      navigate(hasPin(session.user.id) ? "/app" : "/unlock", { replace: true });
    } catch (err) {
      if (err instanceof PhraseError) setError(err.message);
      else if (err instanceof ApiError || (err instanceof Error && err.name === "UsernameError")) setError(err.message);
      else setError("Something went wrong.");
      setStepIndex(0);
    } finally {
      setBusy(false);
    }
  }

  if (stepIndex === 0) {
    return (
      <AuthLayout
        title="Create your account"
        subtitle="Pick a username and a password. No phone number, no email."
        step={1}
        steps={3}
        onBack={() => navigate("/")}
        footer={
          <>
            Already have an account? <Link to="/login">Log in</Link>
          </>
        }
      >
        <form
          className="auth-form"
          onSubmit={(event) => {
            event.preventDefault();
            if (accountOk) setStepIndex(1);
          }}
        >
          <TextField
            label="Username"
            name="username"
            autoComplete="username"
            autoCapitalize="none"
            autoCorrect="off"
            spellCheck={false}
            placeholder="yourname"
            value={username}
            onChange={setUsername}
            hint="3–32 characters. Letters, numbers and underscores."
            error={
              username && !/^[a-zA-Z0-9_]*$/.test(username)
                ? "Letters, numbers and underscores only."
                : null
            }
            required
            autoFocus
          />
          <PasswordField
            label="Password"
            name="password"
            autoComplete="new-password"
            value={password}
            onChange={setPassword}
            required
          />
          {password ? <StrengthMeter score={strength.score} level={strength.level} /> : null}
          <RuleList
            rules={[
              { label: "At least 12 characters", met: strength.hasMinimumLength },
              { label: "A number and a symbol", met: strength.hasSymbolAndNumber },
            ]}
          />
          {error ? <p className="auth-error" role="alert">{error}</p> : null}
          <button className="btn btn-primary" type="submit" disabled={!accountOk}>
            Continue
          </button>
        </form>
      </AuthLayout>
    );
  }

  if (stepIndex === 1) {
    return (
      <AuthLayout
        title="Save your encryption phrase"
        subtitle="These 12 words are the only way to restore your messages."
        step={2}
        steps={3}
        onBack={() => setStepIndex(0)}
      >
        <div className="auth-form">
          <PhraseDisplay words={words} />
          <div className="auth-warn">
            <TriangleAlert size={16} aria-hidden="true" />
            <p>
              Write them down and keep them somewhere safe. Shroud has no copy and{" "}
              <strong>cannot recover them for you</strong> — without the phrase your messages are
              gone for good.
            </p>
          </div>
          <button className="btn btn-primary" type="button" onClick={() => setStepIndex(2)}>
            I’ve written it down
          </button>
        </div>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout
      title="Confirm your phrase"
      subtitle={`Type ${CHECKS} of the words back so we know you saved them.`}
      step={3}
      steps={3}
      onBack={() => setStepIndex(1)}
      backLabel="Show my phrase again"
    >
      <form className="auth-form" onSubmit={createAccount}>
        <div className="auth-checks">
          {checkIndices.map((index) => (
            <TextField
              key={index}
              label={`Word ${index + 1}`}
              value={answers[index] ?? ""}
              onChange={(next) => {
                setAnswers((prev) => ({ ...prev, [index]: next }));
                setError(null);
              }}
              autoCapitalize="none"
              autoCorrect="off"
              spellCheck={false}
              autoComplete="off"
              autoFocus={index === checkIndices[0]}
            />
          ))}
        </div>

        {error ? <p className="auth-error" role="alert">{error}</p> : null}
        <button className="btn btn-primary" type="submit" disabled={!checksFilled || busy}>
          {busy ? "Creating account…" : "Create account"}
        </button>
      </form>
    </AuthLayout>
  );
}
