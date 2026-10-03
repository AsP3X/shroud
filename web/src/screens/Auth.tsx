import { useState, type FormEvent } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { TriangleAlert } from "lucide-react";
import { api, ApiError } from "../api/client";
import { generateMnemonic, PhraseError, validateMnemonic, WORD_COUNT } from "../crypto/bip39";
import { b64ToBytes, bytesEqual } from "../crypto/bytes";
import {
  establish,
  historyKeyFromMnemonic,
  matchesMnemonic,
  putBundleRequest,
  type IdentityMaterial,
} from "../crypto/identity";
import { AuthLayout } from "../components/auth/AuthLayout";
import { PasswordField, TextField } from "../components/auth/Fields";
import { PhraseDisplay, PhraseEntry } from "../components/auth/Phrase";
import { hasIdentity, loadIdentity, loadPlaintextIdentity, saveIdentity } from "../crypto/store";
import { hasVault, isVaultOpen, openVaultWithPhrase } from "../crypto/vault";
import { hasPin, needsPhrase, sealLegacyStorage } from "../crypto/vaultAccess";
import { markFreshSignIn } from "../deviceNaming";
import { clearSession, loadDeviceAnchor, loadSession, saveSession } from "../session";

/**
 * The account's published identity key is determined by the phrase. Refuse a phrase
 * that derives a different key, and allow the first device, which has no key yet.
 */
async function assertMatchesPublishedIdentity(
  token: string,
  userId: string,
  material: IdentityMaterial,
): Promise<void> {
  try {
    const published = await api.peerIdentity(token, userId);
    const theirs = b64ToBytes(published.identity_key);
    if (!bytesEqual(theirs, material.agreementPublic)) {
      throw new PhraseError("invalid_checksum", "mismatch");
    }
  } catch (err) {
    if (err instanceof PhraseError) throw err;
    if (err instanceof ApiError && err.code === "KEYS_REQUIRED") return;
    throw err;
  }
}

export function Auth() {
  const navigate = useNavigate();
  const location = useLocation();
  const existing = loadSession();
  const [phase, setPhase] = useState<"credentials" | "phrase">(
    // The phrase step talks to the server, so it needs a token this page can read.
    existing?.token && (!hasIdentity(existing.user.id) || needsPhrase(existing.user.id))
      ? "phrase"
      : "credentials",
  );
  const [username, setUsername] = useState(
    existing?.user.username ?? (location.state as { username?: string } | null)?.username ?? "",
  );
  const [password, setPassword] = useState("");
  const [words, setWords] = useState<string[]>(Array.from({ length: WORD_COUNT }, () => ""));
  const [busy, setBusy] = useState(false);
  // Set by the unlock screen when the server has stopped accepting this browser's PIN.
  const notice = (location.state as { notice?: string } | null)?.notice ?? null;
  const [error, setError] = useState<string | null>(notice);
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
      const session = await api.login(username.trim().toLowerCase(), password, loadDeviceAnchor(username));
      saveSession(session);
      // The app asks for this browser's name once it opens (it is sealed with the phrase).
      markFreshSignIn();
      setPhase("phrase");
    } catch (err) {
      setError(err instanceof ApiError || err instanceof Error && err.name === "UsernameError" ? err.message : "Something went wrong.");
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
      const userId = session.user.id;
      // The phrase opens this browser's vault too — how "Forgot PIN" gets the data back.
      // A phrase that does not open it simply leaves it closed; the checks below decide.
      if (!creatingPhrase && hasVault(userId) && !isVaultOpen(userId)) {
        if (openVaultWithPhrase(userId, historyKeyFromMnemonic(normalized))) {
          await sealLegacyStorage(userId);
        }
      }
      const stored = loadIdentity(userId) ?? loadPlaintextIdentity(userId);
      const sameLocal = stored !== null && !creatingPhrase && matchesMnemonic(stored, normalized);
      if (stored && !creatingPhrase && !sameLocal) {
        throw new PhraseError("invalid_checksum", "mismatch");
      }
      const material = sameLocal && stored ? stored : establish(normalized, session.user.id);
      // A new device must not publish a different key. Peers use the newest device's
      // identity, so a wrong phrase here would replace the account's key.
      await assertMatchesPublishedIdentity(session.token, session.user.id, material);
      const status = await api.keysStatus(session.token);
      if (!status.has_identity || !sameLocal) {
        await api.putBundle(session.token, putBundleRequest(material));
        saveIdentity(material);
      }
      afterUnlock();
    } catch (err) {
      // One outcome for a bad word, a bad checksum, or the wrong phrase. Naming
      // the failing word, or saying the checksum passed, would confirm a guess.
      if (err instanceof PhraseError) {
        setError("That phrase doesn’t unlock this account. Check the words and their order.");
      } else if (err instanceof ApiError) setError(err.message);
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
                This is only for an account that has never had a phrase. If you already set one
                on your phone, go back and enter that phrase. A different one is not saved.
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
