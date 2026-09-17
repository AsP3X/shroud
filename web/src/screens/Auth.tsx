import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { KeyRound, Shield } from "lucide-react";
import { api, ApiError } from "../api/client";
import { deviceName } from "../config";
import { generateMnemonic, PhraseError, validateMnemonic } from "../crypto/bip39";
import { establish, matchesMnemonic, putBundleRequest } from "../crypto/identity";
import { PhraseGrid } from "../components/PhraseGrid";
import { hasIdentity, loadIdentity, saveIdentity } from "../crypto/store";
import { hasPin, loadDeviceAnchor, loadSession, saveSession } from "../session";

export function Auth() {
  const navigate = useNavigate();
  const existing = loadSession();
  const [phase, setPhase] = useState<"credentials" | "phrase">(
    existing && !hasIdentity(existing.user.id) ? "phrase" : "credentials",
  );
  const [username, setUsername] = useState(existing?.user.username ?? "");
  const [password, setPassword] = useState("");
  const [words, setWords] = useState<string[]>(Array.from({ length: 12 }, () => ""));
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

  const isPhrase = phase === "phrase";

  return (
    <div className="screen screen-top">
      <form className="stack stack-wide stack-left" onSubmit={isPhrase ? submitPhrase : submitCredentials}>
        <div className="auth-nav">
          {isPhrase ? (
            <button type="button" className="linkish" onClick={() => setPhase("credentials")}>
              Back
            </button>
          ) : (
            <Link to="/" className="linkish">
              Back
            </Link>
          )}
          {!isPhrase ? (
            <Link to="/signup" className="linkish">
              Sign Up
            </Link>
          ) : (
            <span />
          )}
        </div>
        <div className="mark">
          {isPhrase ? <KeyRound size={30} /> : <Shield size={30} />}
        </div>
        <div>
          <h1>{isPhrase ? "Encryption phrase" : "Log in"}</h1>
          <p className="lede">
            {isPhrase
              ? "Enter the 12 words from this account. They never leave this device."
              : "Same username and password as on your phone. You’ll enter your encryption phrase next."}
          </p>
        </div>

        {isPhrase && loadSession() ? (
          <div className="ok-banner">Signed in as @{loadSession()!.user.username}</div>
        ) : null}

        {isPhrase ? (
          <>
            <PhraseGrid
              words={words}
              editable={!creatingPhrase}
              onChange={(index, value) => {
                const parts = value.trim().split(/\s+/).filter(Boolean);
                if (parts.length === 12) {
                  setWords(parts.map((w) => w.toLowerCase()));
                  return;
                }
                const next = [...words];
                next[index] = value.toLowerCase();
                setWords(next);
              }}
            />
            {creatingPhrase ? (
              <div className="warn-card">
                <p>
                  Write these 12 words down. They become this browser’s identity. If you already
                  use Shroud on iPhone, go back and enter that phrase instead — a new one will not
                  decrypt those chats.
                </p>
              </div>
            ) : (
              <button
                type="button"
                className="linkish"
                onClick={() => {
                  setCreatingPhrase(true);
                  setWords(generateMnemonic());
                  setError(null);
                }}
              >
                I never got a 12-word phrase
              </button>
            )}
          </>
        ) : (
          <>
            <input
              className="field"
              autoComplete="username"
              autoCapitalize="none"
              name="username"
              placeholder="username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              required
              minLength={3}
              maxLength={32}
              pattern="[a-zA-Z0-9_]+"
            />
            <input
              className="field"
              autoComplete="current-password"
              name="password"
              type="password"
              placeholder="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
          </>
        )}

        {error ? <p className="err">{error}</p> : null}
        <div className="actions">
          <button className="btn btn-primary" type="submit" disabled={busy}>
            {busy ? "Please wait…" : isPhrase ? "Unlock chats" : "Continue"}
          </button>
        </div>
      </form>
    </div>
  );
}
