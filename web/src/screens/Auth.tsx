import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { Shield } from "lucide-react";
import { api, ApiError } from "../api/client";
import { deviceName } from "../config";
import { hasPin, loadDeviceAnchor, saveSession } from "../session";

export function Auth({ mode }: { mode: "login" | "signup" }) {
  const navigate = useNavigate();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const name = deviceName();
      const session =
        mode === "signup"
          ? await api.register(username.trim().toLowerCase(), password, name)
          : await api.login(
              username.trim().toLowerCase(),
              password,
              name,
              loadDeviceAnchor(username),
            );
      saveSession(session);
      navigate(hasPin(session.user.id) ? "/app" : "/unlock", { replace: true });
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Something went wrong.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="screen">
      <form className="stack" onSubmit={onSubmit}>
        <div className="mark">
          <Shield size={30} />
        </div>
        <div>
          <h1>{mode === "signup" ? "Create account" : "Log in"}</h1>
          <p className="lede">
            {mode === "signup"
              ? "Pick a username. This browser is a first-class device — chats decrypt only here, not on the server."
              : "Same username and password as on your phone. This browser becomes another device."}
          </p>
        </div>
        <input
          className="field"
          autoComplete="username"
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
          autoComplete={mode === "signup" ? "new-password" : "current-password"}
          name="password"
          type="password"
          placeholder="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          required
          minLength={8}
        />
        {error ? <p className="err">{error}</p> : null}
        <div className="actions">
          <button className="btn btn-primary" type="submit" disabled={busy}>
            {busy ? "Please wait…" : mode === "signup" ? "Create account" : "Log in"}
          </button>
          <Link className="btn btn-secondary" to={mode === "signup" ? "/login" : "/signup"}>
            {mode === "signup" ? "I already have an account" : "Create an account"}
          </Link>
          <Link className="linkish" to="/" style={{ textAlign: "center" }}>
            Back
          </Link>
        </div>
      </form>
    </div>
  );
}
