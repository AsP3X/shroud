import { Lock, Smartphone, KeyRound, Shield } from "lucide-react";
import { Link } from "react-router-dom";

export function Welcome() {
  return (
    <div className="screen">
      <div className="stack">
        <div className="mark">
          <Shield size={30} />
        </div>
        <div>
          <h1>Private messaging, fully encrypted</h1>
          <p className="lede">
            No phone number. No email. Just your username and a 12-word encryption phrase.
          </p>
        </div>
        <div className="tiles">
          <div className="tile">
            <div className="tile-icon">
              <Lock size={18} />
            </div>
            <div>
              <strong>End-to-end encrypted</strong>
              <span>Messages decrypt only on your devices</span>
            </div>
          </div>
          <div className="tile">
            <div className="tile-icon">
              <Smartphone size={18} />
            </div>
            <div>
              <strong>A first-class device</strong>
              <span>This browser is one of up to five linked devices</span>
            </div>
          </div>
          <div className="tile">
            <div className="tile-icon">
              <KeyRound size={18} />
            </div>
            <div>
              <strong>Safety numbers</strong>
              <span>Compare keys in person before you trust a change</span>
            </div>
          </div>
        </div>
        <div className="actions">
          <Link className="btn btn-primary" to="/signup">
            Start Messaging
          </Link>
          <Link className="btn btn-secondary" to="/login">
            Log In
          </Link>
        </div>
      </div>
    </div>
  );
}
