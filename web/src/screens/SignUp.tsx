import { useMemo, useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { Shield, TriangleAlert } from "lucide-react";
import { api, ApiError } from "../api/client";
import { deviceName } from "../config";
import { generateMnemonic, PhraseError } from "../crypto/bip39";
import { establish, putBundleRequest } from "../crypto/identity";
import { evaluatePassword } from "../crypto/password";
import { saveIdentity } from "../crypto/store";
import { PhraseGrid } from "../components/PhraseGrid";
import { hasPin, saveSession } from "../session";

export function SignUp() {
  const navigate = useNavigate();
  const words = useMemo(() => generateMnemonic(), []);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [wroteDown, setWroteDown] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const strength = evaluatePassword(password);
  const canSubmit =
    wroteDown &&
    strength.meetsRequirements &&
    username.trim().length >= 3 &&
    !busy;

  async function copyPhrase() {
    try {
      await navigator.clipboard.writeText(words.join(" "));
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      setError("Couldn’t copy phrase — select the words and copy them yourself.");
    }
  }

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    if (!canSubmit) return;
    setError(null);
    setBusy(true);
    try {
      const session = await api.register(
        username.trim().toLowerCase(),
        password,
        deviceName(),
      );
      saveSession(session);
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
      else if (err instanceof ApiError) setError(err.message);
      else setError("Something went wrong.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="screen screen-top">
      <form className="stack stack-wide stack-left" onSubmit={onSubmit}>
        <div className="auth-nav">
          <Link to="/" className="linkish">
            Back
          </Link>
          <Link to="/login" className="linkish">
            Log In
          </Link>
        </div>
        <div className="mark">
          <Shield size={30} />
        </div>
        <h1>Create Account</h1>

        <section className="auth-section">
          <h2>
            <span>1</span> Choose your identity
          </h2>
          <div className="card-form">
            <input
              className="field"
              autoComplete="username"
              autoCapitalize="none"
              name="username"
              placeholder="Username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              required
              minLength={3}
              maxLength={32}
              pattern="[a-zA-Z0-9_]+"
            />
            <input
              className="field"
              autoComplete="new-password"
              name="password"
              type="password"
              placeholder="Password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
            <div className="strength">
              <div className="strength-track">
                <div
                  className="strength-fill"
                  style={{ width: `${Math.round(strength.score * 100)}%` }}
                />
              </div>
              <span>{strength.level || " "}</span>
            </div>
            <p className="hint">
              At least 12 characters, including a number and a symbol. No phone number or email.
            </p>
          </div>
        </section>

        <section className="auth-section">
          <h2>
            <span>2</span> Save your encryption phrase
            <button type="button" className="copy-pill" onClick={copyPhrase}>
              {copied ? "Copied" : "Copy"}
            </button>
          </h2>
          <PhraseGrid words={words} />
          <div className="warn-card">
            <TriangleAlert size={16} />
            <p>
              These 12 words are the only way to restore your messages. Shroud cannot recover them
              for you. They never leave this device.
            </p>
          </div>
        </section>

        {error ? <p className="err">{error}</p> : null}

        <label className="check-row">
          <input type="checkbox" checked={wroteDown} onChange={(e) => setWroteDown(e.target.checked)} />
          I wrote down my encryption phrase
        </label>

        <button className="btn btn-primary" type="submit" disabled={!canSubmit}>
          {busy ? "Creating…" : "Create Account"}
        </button>
      </form>
    </div>
  );
}
