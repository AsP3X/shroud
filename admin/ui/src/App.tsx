import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import { Shell } from "./components/Shell";
import { AuditLog } from "./pages/AuditLog";
import { Calls } from "./pages/Calls";
import { ClientVersions } from "./pages/ClientVersions";
import { Configuration } from "./pages/Configuration";
import { ToastProvider } from "./components/Toast";
import { Gallery } from "./pages/Gallery";
import { Operators } from "./pages/Operators";
import { Overview } from "./pages/Overview";
import { PrivacyChecks } from "./pages/PrivacyChecks";
import { Push } from "./pages/Push";
import { RateLimits } from "./pages/RateLimits";
import { Retention } from "./pages/Retention";
import { Setup } from "./pages/Setup";
import { SignIn } from "./pages/SignIn";
import { Storage } from "./pages/Storage";
import { Stub } from "./pages/Stub";
import { UserDetail } from "./pages/UserDetail";
import { Users } from "./pages/Users";

/** One route per frame of design/admin.pen (docs/admin-plan.md §5). Operators arrive in C2. */
export function App() {
  return (
    <BrowserRouter basename="/admin">
      <ToastProvider>
      <Routes>
        <Route path="/sign-in" element={<SignIn />} />
        <Route path="/setup/:token" element={<Setup />} />
        <Route element={<Shell />}>
          <Route index element={<Overview />} />
          <Route path="/privacy-checks" element={<PrivacyChecks />} />
          <Route path="/users" element={<Users />} />
          <Route path="/users/:id" element={<UserDetail />} />
          <Route path="/sign-ups" element={<Stub title="Sign-ups" task="— not on this server: registration is open by design (docs/admin-plan.md §8)" />} />
          <Route path="/operators" element={<Operators />} />
          <Route path="/storage" element={<Storage />} />
          <Route path="/retention" element={<Retention />} />
          <Route path="/push" element={<Push />} />
          <Route path="/calls" element={<Calls />} />
          <Route path="/client-versions" element={<ClientVersions />} />
          <Route path="/configuration" element={<Configuration />} />
          <Route path="/rate-limits" element={<RateLimits />} />
          <Route path="/audit-log" element={<AuditLog />} />
          <Route path="/gallery" element={<Gallery />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
      </ToastProvider>
    </BrowserRouter>
  );
}
