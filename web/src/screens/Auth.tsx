import { useState, type FormEvent } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { TriangleAlert } from "lucide-react";
import { api, ApiError, type Session } from "../api/client";
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
import { DeviceLimitDialog, DevicePickerDialog } from "../components/auth/DeviceLimitDialog";
import { hasIdentity, loadIdentity, loadPlaintextIdentity, saveIdentity } from "../crypto/store";
import { hasVault, isVaultOpen, openVaultWithPhrase } from "../crypto/vault";
import { hasPin, needsPhrase, sealLegacyStorage } from "../crypto/vaultAccess";
import {
  attemptLogin,
  isOldestSelected,
  nextDeviceLimitState,
  phraseOpensAccount,
  replaceSelectedDevice,
  selectedDevice,
  type DeviceLimitEvent,
  type DeviceLimitState,
} from "../deviceLimit";
import { markFreshSignIn } from "../deviceNaming";
import { clearSession, loadDeviceAnchor, loadSession, saveSession } from "../session";

const WRONG_PHRASE = "That phrase doesn’t unlock this account. Check the words and their order.";

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
  // A login that found every device slot signed in, waiting on the phrase step without a
  // session. Only a phrase that derives the account's key opens the question.
  const [limit, setLimit] = useState<DeviceLimitState>(null);
  const updateLimit = (event: DeviceLimitEvent) => setLimit((state) => nextDeviceLimitState(state, event));

  function afterUnlock() {
    const session = loadSession();
    if (!session) {
      navigate("/", { replace: true });
      return;
    }
    navigate(hasPin(session.user.id) ? "/app" : "/unlock", { replace: true });
  }

  function startSession(session: Session) {
    saveSession(session);
    // The app asks for this browser's name once it opens (it is sealed with the phrase).
    markFreshSignIn();
  }

  function showLoginError(err: unknown) {
    setError(err instanceof ApiError || err instanceof Error && err.name === "UsernameError" ? err.message : "Something went wrong.");
  }

  function showPhraseError(err: unknown) {
    // One outcome for a bad word, a bad checksum, or the wrong phrase. Naming
    // the failing word, or saying the checksum passed, would confirm a guess.
    if (err instanceof PhraseError) setError(WRONG_PHRASE);
    else if (err instanceof ApiError) setError(err.message);
    else setError("Something went wrong.");
  }

  async function submitCredentials(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const attempt = { username: username.trim().toLowerCase(), password, deviceId: loadDeviceAnchor(username) };
      const outcome = await attemptLogin(attempt);
      updateLimit({ type: "login", attempt, outcome });
      if (outcome.kind === "error") {
        showLoginError(outcome.error);
        return;
      }
      if (outcome.kind === "session") startSession(outcome.session);
      // A full account goes on too, with no session yet: the phrase comes before the question.
      setPhase("phrase");
    } finally {
      setBusy(false);
    }
  }

  /**
   * The phrase step after a session exists: open the vault, check the phrase against this
   * browser's and the account's published identity, publish the key bundle, and move on. Both
   * a normal login and one that first logged out the oldest device end here, the latter with
   * the words already checked, so the phrase is never asked twice.
   */
  async function completePhrase(session: Session, normalized: string[], creating: boolean) {
    const userId = session.user.id;
    // The phrase opens this browser's vault too — how "Forgot PIN" gets the data back.
    // A phrase that does not open it simply leaves it closed; the checks below decide.
    if (!creating && hasVault(userId) && !isVaultOpen(userId)) {
      if (openVaultWithPhrase(userId, historyKeyFromMnemonic(normalized))) {
        await sealLegacyStorage(userId);
      }
    }
    const stored = loadIdentity(userId) ?? loadPlaintextIdentity(userId);
    const sameLocal = stored !== null && !creating && matchesMnemonic(stored, normalized);
    if (stored && !creating && !sameLocal) {
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
  }

  async function submitPhrase(event: FormEvent) {
    event.preventDefault();
    setError(null);
    if (limit) {
      // No session yet: the phrase is checked against the key the 409 carried, here, before
      // anything is asked. A wrong one gets the usual error and costs no device.
      const normalized = creatingPhrase ? null : phraseOpensAccount(words, limit.pending.identityKey);
      if (normalized) updateLimit({ type: "phrase", words: normalized });
      else setError(WRONG_PHRASE);
      return;
    }
    const session = loadSession();
    if (!session) {
      setPhase("credentials");
      setError("Session expired. Log in again.");
      return;
    }
    setBusy(true);
    try {
      await completePhrase(session, validateMnemonic(words), creatingPhrase);
    } catch (err) {
      showPhraseError(err);
    } finally {
      setBusy(false);
    }
  }

  async function confirmReplace() {
    if (!limit?.words || limit.busy || limit.picking) return;
    updateLimit({ type: "confirm" });
    const result = await replaceSelectedDevice(limit.pending, limit.words);
    updateLimit({ type: "replaced", result });
    if (result.kind === "wrong-phrase") {
      setError(WRONG_PHRASE);
    } else if (result.kind === "error") {
      showLoginError(result.error);
    } else if (result.kind === "session") {
      startSession(result.session);
      setBusy(true);
      try {
        await completePhrase(result.session, result.words, false);
      } catch (err) {
        showPhraseError(err);
      } finally {
        setBusy(false);
      }
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
    updateLimit({ type: "reset" });
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
        {session && !limit ? (
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
      {limit?.words ? (
        limit.picking ? (
          <DevicePickerDialog
            devices={limit.pending.devices}
            labels={limit.labels}
            selectedId={limit.pending.selectedId}
            onSelect={(id) => updateLimit({ type: "select", id })}
            onCancel={() => updateLimit({ type: "unpick" })}
          />
        ) : (
          <DeviceLimitDialog
            device={selectedDevice(limit.pending)}
            label={limit.labels[selectedDevice(limit.pending).id] ?? null}
            oldest={isOldestSelected(limit.pending)}
            canChoose={limit.pending.devices.length > 1}
            busy={limit.busy}
            onChoose={() => updateLimit({ type: "pick" })}
            onCancel={() => updateLimit({ type: "cancel" })}
            onConfirm={() => void confirmReplace()}
          />
        )
      ) : null}
    </AuthLayout>
  );
}
