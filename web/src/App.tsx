import { useCallback, useEffect, useRef, useState } from "react";
import { Navigate, Route, Routes, useNavigate } from "react-router-dom";
import { hasIdentity } from "./crypto/store";
import { isVaultOpen } from "./crypto/vault";
import { hasPin, needsPhrase } from "./crypto/vaultAccess";
import { DeviceWipeDialog } from "./components/DeviceWipeDialog";
import { clearRemovalMarker, onDeviceRemoved, removalPending, watchForRemoval } from "./deviceRemoval";
import { finishWipeOnLoad } from "./deviceWipe";
import { AppShell } from "./screens/AppShell";
import { Auth } from "./screens/Auth";
import { SignUp } from "./screens/SignUp";
import { Unlock } from "./screens/Unlock";
import { Welcome } from "./screens/Welcome";
import { storageSealed } from "./storageSeal";
import { installAutoLock, isLocked, loadSession, storedSessionMeta } from "./session";
import type { Session } from "./api/client";

export function App() {
  const navigate = useNavigate();
  const loaded = loadSession();
  // The wipe drops the token before its first step, so a closed tab cannot come back signed
  // in. Hold the session we already had: a re-render in the middle would otherwise read an
  // empty store and unmount the wipe onto the welcome screen.
  const held = useRef<Session | null>(loaded);
  if (loaded) held.current = loaded;
  const sealing = storageSealed();
  const session = loaded ?? (sealing ? held.current : null);
  const keyed = sealing
    ? Boolean(session)
    : Boolean(session && hasIdentity(session.user.id) && !needsPhrase(session.user.id));
  // Nothing on disk is readable without the vault key, so a closed vault is a locked app —
  // a reload included: the key only ever lives in this page's memory.
  const locked = sealing ? false : isLocked() || Boolean(session && !isVaultOpen(session.user.id));
  const needsPinSetup = sealing ? false : Boolean(keyed && session && !hasPin(session.user.id));
  const sessionToken = session?.token;

  /*
   * This browser was removed from the account on another device. The chat shell handles that
   * itself while it is mounted; anywhere else — locked, choosing a PIN, entering the phrase,
   * or a load that found the worker's marker — the wipe runs here, in place of every screen.
   * The snapshot names the account (the token, if this page has one, ends the server session).
   */
  const [removal, setRemoval] = useState<Session | null>(() =>
    removalPending() ? (loadSession() ?? storedSessionMeta()) : null,
  );
  const removed = useCallback(() => {
    const snapshot = loadSession() ?? storedSessionMeta();
    if (!snapshot) {
      // Nobody is signed in here. Data without a session (a sign-in screen reached after the
      // removal cleared the session) goes the way an interrupted wipe does.
      void (finishWipeOnLoad() ?? Promise.resolve()).then(clearRemovalMarker);
      return;
    }
    setRemoval((current) => current ?? snapshot);
  }, []);
  useEffect(() => onDeviceRemoved("app", removed), [removed]);

  // Locked, the token is sealed: ask the server with its hash whether this browser was removed.
  const inShell = Boolean(session && keyed && !locked && !needsPinSetup);
  const probing = Boolean(session) && !inShell && !sealing && !removal;
  useEffect(() => {
    if (!probing) return;
    return watchForRemoval(removed);
  }, [probing, removed]);

  useEffect(() => {
    if (!sessionToken || !keyed || needsPinSetup || removal) return;
    return installAutoLock(() => {
      navigate("/unlock", { replace: true });
    });
  }, [sessionToken, keyed, needsPinSetup, navigate, removal]);

  // No routes behind it: they would follow the emptied store into the shell or the welcome
  // screen mid-wipe. The dialog ends with a reload onto the welcome screen.
  if (removal) return <DeviceWipeDialog session={removal} reason="removed" />;

  return (
    <Routes>
      <Route
        path="/"
        element={
          session ? (
            <Navigate to={!keyed ? "/login" : locked || needsPinSetup ? "/unlock" : "/app"} replace />
          ) : (
            <Welcome />
          )
        }
      />
      <Route
        path="/login"
        element={
          !session || !keyed ? (
            <Auth />
          ) : locked || needsPinSetup ? (
            <Navigate to="/unlock" replace />
          ) : (
            <Navigate to="/app" replace />
          )
        }
      />
      <Route
        path="/signup"
        element={
          keyed ? (
            <Navigate to="/app" replace />
          ) : session ? (
            <Navigate to="/login" replace />
          ) : (
            <SignUp />
          )
        }
      />
      <Route
        path="/unlock"
        element={session && keyed ? <Unlock /> : <Navigate to={session ? "/login" : "/"} replace />}
      />
      <Route
        path="/app"
        element={
          !session ? (
            <Navigate to="/" replace />
          ) : !keyed ? (
            <Navigate to="/login" replace />
          ) : locked || needsPinSetup ? (
            <Navigate to="/unlock" replace />
          ) : (
            <AppShell session={session} />
          )
        }
      />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
