import { useEffect } from "react";
import { Navigate, Route, Routes, useNavigate } from "react-router-dom";
import { AppShell } from "./screens/AppShell";
import { Auth } from "./screens/Auth";
import { Unlock } from "./screens/Unlock";
import { Welcome } from "./screens/Welcome";
import { hasPin, installAutoLock, isLocked, loadSession } from "./session";

export function App() {
  const navigate = useNavigate();
  const session = loadSession();
  const locked = isLocked();
  const needsPinSetup = Boolean(session && !hasPin(session.user.id));
  const sessionToken = session?.token;

  useEffect(() => {
    if (!sessionToken) return;
    return installAutoLock(() => {
      navigate("/unlock", { replace: true });
    });
  }, [sessionToken, navigate]);

  return (
    <Routes>
      <Route
        path="/"
        element={
          session ? (
            <Navigate to={locked || needsPinSetup ? "/unlock" : "/app"} replace />
          ) : (
            <Welcome />
          )
        }
      />
      <Route
        path="/login"
        element={session ? <Navigate to="/app" replace /> : <Auth mode="login" />}
      />
      <Route
        path="/signup"
        element={session ? <Navigate to="/app" replace /> : <Auth mode="signup" />}
      />
      <Route path="/unlock" element={session ? <Unlock /> : <Navigate to="/" replace />} />
      <Route
        path="/app"
        element={
          !session ? (
            <Navigate to="/" replace />
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
