import { useEffect } from "react";
import { Navigate, Route, Routes, useNavigate } from "react-router-dom";
import { hasIdentity } from "./crypto/store";
import { AppShell } from "./screens/AppShell";
import { Auth } from "./screens/Auth";
import { SignUp } from "./screens/SignUp";
import { Unlock } from "./screens/Unlock";
import { Welcome } from "./screens/Welcome";
import { hasPin, installAutoLock, isLocked, loadSession } from "./session";

export function App() {
  const navigate = useNavigate();
  const session = loadSession();
  const keyed = Boolean(session && hasIdentity(session.user.id));
  const locked = isLocked();
  const needsPinSetup = Boolean(keyed && session && !hasPin(session.user.id));
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
