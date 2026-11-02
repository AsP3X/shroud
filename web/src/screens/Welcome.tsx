import { Link } from "react-router-dom";
import { ArrowRight, KeyRound, Lock, Smartphone, User, type LucideIcon } from "lucide-react";
import { BrandMark } from "../components/BrandMark";
import { LandingPreview } from "../components/LandingPreview";

const FACTS: { Icon: LucideIcon; title: string; body: string }[] = [
  {
    Icon: Lock,
    title: "End-to-end encrypted",
    body: "Messages decrypt only on your devices. Everything in between is ciphertext.",
  },
  {
    Icon: Smartphone,
    title: "A first-class device",
    body: "This browser is one of up to five linked devices, with the same chats as your iPhone.",
  },
  {
    Icon: KeyRound,
    title: "Safety numbers",
    body: "Compare keys in person before you trust a change. A new key is something you see, not something that just happens.",
  },
  {
    Icon: User,
    title: "No phone number, no email",
    body: "A username and a 12-word encryption phrase are the whole account. Write the phrase down; it is the only way back in.",
  },
];

function Brand() {
  return (
    <Link className="landing-brand" to="/">
      <BrandMark size={28} />
      Shroud
    </Link>
  );
}

/** The page for a browser with no account: what Shroud is, a look at it, and the two ways in. */
export function Welcome() {
  return (
    <div className="landing">
      <header className="landing-top landing-wrap">
        <Brand />
        <nav aria-label="Account">
          <Link to="/login">Log in</Link>
        </nav>
      </header>

      <main>
        <section className="landing-hero">
          <div className="landing-wrap">
            <div>
              <h1>Private messaging, fully encrypted.</h1>
              <p className="landing-lede">
                No phone number. No email. Just your username and a 12‑word encryption phrase.
              </p>
              <div className="landing-actions">
                <Link className="btn btn-primary landing-btn" to="/signup">
                  Create an account
                </Link>
                <Link className="landing-link" to="/login">
                  Log in <ArrowRight size={15} aria-hidden="true" />
                </Link>
              </div>
              <p className="landing-note">
                <b>Already using Shroud on iPhone?</b> Log in with the same username and phrase and your chats
                come to this browser.
              </p>
            </div>
            <LandingPreview />
          </div>
        </section>

        <section className="landing-facts" aria-labelledby="landing-facts-title">
          <div className="landing-wrap">
            <div>
              <h2 id="landing-facts-title">How Shroud keeps it private</h2>
              <p className="landing-intro">The browser is the same client as on your iPhone, with the same rules.</p>
            </div>
            <ul className="landing-ledger">
              {FACTS.map(({ Icon, title, body }) => (
                <li key={title}>
                  <Icon size={16} aria-hidden="true" />
                  <strong>{title}</strong>
                  <p>{body}</p>
                </li>
              ))}
            </ul>
          </div>
        </section>

        <section className="landing-start" aria-label="Get started">
          <div className="landing-wrap">
            <section>
              <p className="landing-kicker">New here</p>
              <h2>Create an account in under a minute</h2>
              <p>
                Pick a username, write down the 12 words you are given, and start chatting. There is nothing to
                verify and no inbox to check.
              </p>
              <div className="landing-act">
                <Link className="btn btn-primary landing-btn" to="/signup">
                  Create an account
                </Link>
              </div>
            </section>
            <section>
              <p className="landing-kicker">Already on iPhone</p>
              <h2>Bring your chats to this browser</h2>
              <p>
                Log in with the username and encryption phrase from your iPhone. This browser becomes one of
                your linked devices.
              </p>
              <div className="landing-act">
                <Link className="landing-link" to="/login">
                  Log in <ArrowRight size={15} aria-hidden="true" />
                </Link>
              </div>
            </section>
          </div>
        </section>
      </main>

      <footer className="landing-foot">
        <div className="landing-wrap">
          <Brand />
          <nav aria-label="Footer">
            <Link to="/signup">Create an account</Link>
            <Link to="/login">Log in</Link>
          </nav>
        </div>
      </footer>
    </div>
  );
}
