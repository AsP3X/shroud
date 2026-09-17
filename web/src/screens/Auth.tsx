import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { TriangleAlert } from "lucide-react";
import { api, ApiError } from "../api/client";
import { deviceName } from "../config";
import { generateMnemonic, PhraseError, validateMnemonic, WORD_COUNT } from "../crypto/bip39";
import { establish, matchesMnemonic, putBundleRequest } from "../crypto/identity";
import { AuthLayout } from "../components/auth/AuthLayout";
import { PasswordField, TextField } from "../components/auth/Fields";
import { PhraseDisplay, PhraseEntry } from "../components/auth/Phrase";
import { hasIdentity, loadIdentity, saveIdentity } from "../crypto/store";
import { hasPin, clearSession, loadDeviceAnchor, loadSession, saveSession } from "../session";

export function Auth() {
  const navigate = useNavigate();
  const existing = loadSession();
  const [phase, setPhase] = useState<"credentials" | "phrase">(
    existing && !hasIdentity(existing.user.id) ? "phrase" : "credentials",
  );
  const [username, setUsername] = useState(existing?.user.username ?? "");
  const [password, setPassword] = useState("");
  const [words, setWords] = useState<string[]>(Array.from({ length: WORD_COUNT }, () => ""));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [creatingPhrase, setCreatingPhrase] = useState(false);

  function afterUnlock() {
    const session = loadSession();
    if (!session) {
      navigate("/", { replace: true });
      return;
    }
    navigate(hasPin(session.user.id) ? "/app" : "/unlock", { replace: true });
  }

  async function submitCredentials(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const session = await api.login(
        username.trim().toLowerCase(),
        password,
        deviceName(),
        loadDeviceAnchor(username),
      );
      saveSession(session);
      setPhase("phrase");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Something went wrong.");
    } finally {
      setBusy(false);
    }
  }

  async function submitPhrase(event: FormEvent) {
    event.preventDefault();
    setError(null);
    const session = loadSession();
    if (!session) {
      setPhase("credentials");
      setError("Session expired. Log in again.");
      return;
    }
    setBusy(true);
    try {
      const normalized = validateMnemonic(words);
      const stored = loadIdentity(session.user.id);
      if (stored && !creatingPhrase && !matchesMnemonic(stored, normalized)) {
        throw new PhraseError("invalid_checksum", "That phrase does not match this account.");
      }
      const material =
        stored && !creatingPhrase && matchesMnemonic(stored, normalized)
          ? stored
          : establish(normalized, session.user.id);
      const status = await api.keysStatus(session.token);
      if (!status.has_identity || creatingPhrase || material !== stored) {
        await api.putBundle(session.token, putBundleRequest(material));
        saveIdentity(material);
      }
      afterUnlock();
    } catch (err) {
      if (err instanceof PhraseError) setError(err.message);
      else if (err instanceof ApiError) setError(err.message);
      else setError("Something went wrong.");
    } finally {
      setBusy(false);
    }
  }

  function backToCredentials() {
    const session = loadSession();
    if (session) {
      void api.logout(session.token).catch(() => {
        /* still drop the local token */
      });
      clearSession();
    }
    setPhase("credentials");
    setCreatingPhrase(false);
    setWords(Array.from({ length: WORD_COUNT }, () => ""));
    setPassword("");
    setError(null);
  }

  if (phase === "credentials") {
    return (
      <AuthLayout
        title="Welcome back"
        subtitle="Use the same username and password as on your phone."
        step={1}
        steps={2}
        onBack={() => navigate("/")}
        footer={
          <>
            New to Shroud? <Link to="/signup">Create an account</Link>
          </>
        }
      >
        <form className="auth-form" onSubmit={submitCredentials}>
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
            required
            minLength={3}
            maxLength={32}
            pattern="[a-zA-Z0-9_]+"
            autoFocus
          />
          <PasswordField
            label="Password"
            name="password"
            autoComplete="current-password"
            value={password}
            onChange={setPassword}
            required
          />
          {error ? <p className="auth-error" role="alert">{error}</p> : null}
          <button
            className="btn btn-primary"
            type="submit"
            disabled={busy || !username.trim() || !password}
          >
            {busy ? "Checking…" : "Continue"}
          </button>
          <p className="auth-note">
            Next you’ll enter your 12-word encryption phrase. It never leaves this device.
          </p>
        </form>
      </AuthLayout>
    );
  }

  const session = loadSession();

  return (
    <AuthLayout
      title={creatingPhrase ? "Your new phrase" : "Encryption phrase"}
      subtitle={
        creatingPhrase
          ? "Write these 12 words down. They become this account’s encryption phrase."
          : "Enter the 12 words for this account to decrypt your chats on this browser."
      }
      step={2}
      steps={2}
      onBack={backToCredentials}
      backLabel="Use a different account"
    >
      <form className="auth-form" onSubmit={submitPhrase}>
        {session ? (
          <p className="auth-identity">
            Signed in as <strong>@{session.user.username}</strong>
          </p>
        ) : null}

        {creatingPhrase ? (
          <>
            <PhraseDisplay words={words} />
            <div className="auth-warn">
              <TriangleAlert size={16} aria-hidden="true" />
              <p>
                This creates a <strong>brand-new</strong> phrase. It will not decrypt chats you
                already have on iPhone — go back and enter that phrase instead if you have one.
                Shroud cannot recover a lost phrase.
              </p>
            </div>
            <button
              type="button"
              className="auth-link"
              onClick={() => {
                setCreatingPhrase(false);
                setWords(Array.from({ length: WORD_COUNT }, () => ""));
                setError(null);
              }}
            >
              I do have a phrase — let me type it
            </button>
          </>
        ) : (
          <>
            <PhraseEntry words={words} onChange={setWords} autoFocus />
            <button
              type="button"
              className="auth-link"
              onClick={() => {
                setCreatingPhrase(true);
                setWords(generateMnemonic());
                setError(null);
              }}
            >
              I never got a 12-word phrase
            </button>
          </>
        )}

        {error ? <p className="auth-error" role="alert">{error}</p> : null}
        <button
          className="btn btn-primary"
          type="submit"
          disabled={busy || (!creatingPhrase && words.filter(Boolean).length !== WORD_COUNT)}
        >
          {busy ? "Unlocking…" : creatingPhrase ? "Save phrase and continue" : "Unlock chats"}
        </button>
      </form>
    </AuthLayout>
  );
}
