import { Link } from "react-router-dom";
import { AuthLayout } from "../components/auth/AuthLayout";

export function Welcome() {
  return (
    <AuthLayout
      title="Get started"
      subtitle="Create an account in under a minute — or log in if you already use Shroud on iPhone."
    >
      <div className="auth-form">
        <Link className="btn btn-primary" to="/signup">
          Create an account
        </Link>
        <Link className="btn btn-secondary" to="/login">
          Log in
        </Link>
        <p className="auth-note">
          Logging in with the same username and encryption phrase brings your existing chats to
          this browser.
        </p>
      </div>
    </AuthLayout>
  );
}
