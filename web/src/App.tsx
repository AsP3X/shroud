import { useEffect, useRef } from "react";
import { Navigate, Route, Routes, useNavigate } from "react-router-dom";
import { hasIdentity } from "./crypto/store";
import { AppShell } from "./screens/AppShell";
import { Auth } from "./screens/Auth";
import { SignUp } from "./screens/SignUp";
import { Unlock } from "./screens/Unlock";
import { Welcome } from "./screens/Welcome";
import { storageSealed } from "./storageSeal";
import { hasPin, installAutoLock, isLocked, loadSession } from "./session";
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
  const keyed = sealing ? Boolean(session) : Boolean(session && hasIdentity(session.user.id));
  const locked = sealing ? false : isLocked();
  const needsPinSetup = sealing ? false : Boolean(keyed && session && !hasPin(session.user.id));
  const sessionToken = session?.token;

  useEffect(() => {
    if (!sessionToken || !keyed || needsPinSetup) return;
    return installAutoLock(() => {
      navigate("/unlock", { replace: true });
    });
  }, [sessionToken, keyed, needsPinSetup, navigate]);

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
