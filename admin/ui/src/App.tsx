import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import { Shell } from "./components/Shell";
import { Gallery } from "./pages/Gallery";
import { Setup } from "./pages/Setup";
import { SignIn } from "./pages/SignIn";
import { Stub } from "./pages/Stub";

/** One route per frame of design/admin.pen (docs/admin-plan.md §5). Pages fill in through C1 and C2. */
export function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/sign-in" element={<SignIn />} />
        <Route path="/setup/:token" element={<Setup />} />
        <Route element={<Shell />}>
          <Route index element={<Stub title="Overview" task="C1.2" />} />
          <Route path="/privacy-checks" element={<Stub title="Privacy checks" task="C1.4" />} />
          <Route path="/users" element={<Stub title="Users" task="C1.3" />} />
          <Route path="/users/:id" element={<Stub title="User detail" task="C1.3" />} />
          <Route path="/sign-ups" element={<Stub title="Sign-ups" task="C1.4" />} />
          <Route path="/operators" element={<Stub title="Operators" task="C2.3" />} />
          <Route path="/storage" element={<Stub title="Storage" task="C1.4" />} />
          <Route path="/retention" element={<Stub title="Data retention" task="C1.4" />} />
          <Route path="/push" element={<Stub title="Push delivery" task="C1.4" />} />
          <Route path="/calls" element={<Stub title="Calls" task="C1.4" />} />
          <Route path="/client-versions" element={<Stub title="Client versions" task="C1.4" />} />
          <Route path="/configuration" element={<Stub title="Configuration" task="C1.4" />} />
          <Route path="/rate-limits" element={<Stub title="Rate limits" task="C1.4" />} />
          <Route path="/audit-log" element={<Stub title="Audit log" task="C1.4" />} />
          <Route path="/gallery" element={<Gallery />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
