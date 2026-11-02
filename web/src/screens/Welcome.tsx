import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { Bell, Image as ImageIcon, Laptop, Lock, Monitor, Phone, Play, Smartphone, Type } from "lucide-react";
import { Avatar } from "../components/Avatar";
import { BrandMark } from "../components/BrandMark";
import { Devices } from "../components/landing/Devices";
import { Veil, Wave } from "../components/landing/Veil";

/*
 * The page for a browser with no account: what the server sees of a chat, what it keeps,
 * the same chats on every device, and the way in, started right here.
 */

const SOURCE_URL = "https://github.com/AsP3X/shroud";
const USERNAME_RE = /^[a-z0-9_]{3,32}$/;
const RULES = "3 to 32 letters, numbers or underscores.";

function host(): string {
  return typeof window === "undefined" ? "" : window.location.host;
}

function Brand({ withHost = false }: { withHost?: boolean }) {
  return (
    <Link className="landing-brand" to="/" aria-label="Shroud">
      <BrandMark size={withHost ? 28 : 20} />
      <b>Shroud</b>
      {withHost ? <span>{host()}</span> : null}
    </Link>
  );
}

/** The hint under the username field: the rules, the problem, or that the name will do. */
function usernameHint(raw: string): { text: string; tone: "" | "ok" | "bad" } {
  const value = raw.trim().toLowerCase();
  if (!value) return { text: RULES, tone: "" };
  if (!/^[a-z0-9_]+$/.test(value)) return { text: "Only letters, numbers and underscores.", tone: "bad" };
  if (value.length < 3) return { text: "At least 3 characters.", tone: "bad" };
  return { text: `@${value} is yours if nobody on this server has it yet.`, tone: "ok" };
}

const CALL_WAVE = [5, 11, 15, 8, 13, 6, 10];
const NOTE_WAVE = [5, 11, 15, 8, 13, 17, 6, 12, 15, 7, 13, 5, 10];
const SAFETY = ["48213", "90714", "22568", "31907", "64120", "07355", "88241", "15096", "73902", "40617", "58233", "96170"];

/** `signedIn`: the page at /about in a browser that has an account. The actions lead into the app. */
export function Welcome({ signedIn = false }: { signedIn?: boolean } = {}) {
  const navigate = useNavigate();
  const [username, setUsername] = useState("");
  const hint = usernameHint(username);

  function start(event: FormEvent) {
    event.preventDefault();
    const value = username.trim().toLowerCase();
    navigate("/signup", USERNAME_RE.test(value) ? { state: { username: value } } : undefined);
  }

  return (
    <div className="landing">
      <header className="landing-top landing-wrap">
        <Brand withHost />
        <nav aria-label="Account">
          {signedIn ? <Link to="/app">Open Shroud</Link> : <Link to="/login">Log in</Link>}
        </nav>
      </header>

      <main>
        <section className="landing-hero" aria-labelledby="landing-title">
          <div className="landing-wrap">
            <div>
              <h1 id="landing-title">The server carries your messages. It cannot read them.</h1>
              <p className="landing-lede">
                Every message is sealed on your device. What reaches the server is an envelope: who it is for, when,
                and how big. No phone number, no email, and your whole account is twelve words.
              </p>
              <div className="landing-actions">
                {signedIn ? (
                  <Link className="btn btn-primary landing-btn" to="/app">
                    Open Shroud
                  </Link>
                ) : (
                  <>
                    <Link className="btn btn-primary landing-btn" to="/signup">
                      Create an account
                    </Link>
                    <Link className="landing-link" to="/login">
                      Log in
                    </Link>
                  </>
                )}
              </div>
              <p className="landing-fine">Drag the veil across the chat to see what the server sees.</p>
            </div>
            <Veil />
          </div>
        </section>

        <section className="landing-section" aria-labelledby="landing-keeps">
          <div className="landing-wrap landing-grid wide">
            <div>
              <h2 id="landing-keeps">What this server keeps</h2>
              <p className="landing-intro">
                Shroud is built so the server can pass messages along without reading them, or saying who you are.
              </p>
            </div>
            <ul className="lp-keeps">
              <li>
                <span className="lp-thing">Messages</span>
                <div className="lp-pair" aria-hidden="true">
                  <span className="lp-mine">
                    <span className="lp-mini out">
                      On my way <span className="lp-t">17:53</span>
                    </span>
                  </span>
                  <span className="lp-line" />
                  <span className="lp-srv">
                    <span className="lp-env">
                      <Lock size={12} />
                      0.2 KB <span>17:53</span>
                    </span>
                  </span>
                </div>
                <p className="lp-why">
                  <b>A sealed envelope</b>, delivered and then forgotten. It opens only on your devices.
                </p>
              </li>
              <li>
                <span className="lp-thing">Username</span>
                <div className="lp-pair" aria-hidden="true">
                  <span className="lp-mine">
                    <span className="lp-who">
                      <Avatar name="Mara Lindqvist" seed="welcome-mara-5" size="xs" />
                      mara
                    </span>
                  </span>
                  <span className="lp-line" />
                  <span className="lp-srv">
                    <span className="lp-env">3f9a 1c0e b7d4 …</span>
                  </span>
                </div>
                <p className="lp-why">
                  <b>A slow, salted hash.</b> Nobody looks you up by name, the operator included.
                </p>
              </li>
              <li>
                <span className="lp-thing">Contacts</span>
                <div className="lp-pair" aria-hidden="true">
                  <span className="lp-mine">
                    <span className="lp-who">
                      <Avatar name="Mara Lindqvist" seed="welcome-mara-5" size="xs" />
                      Mara Lindqvist
                    </span>
                  </span>
                  <span className="lp-line" />
                  <span className="lp-srv">
                    <span className="lp-link">
                      <i />
                      <b />
                      <i />
                    </span>
                  </span>
                </div>
                <p className="lp-why">
                  <b>Two accounts that may message each other.</b> The names you give people travel sealed.
                </p>
              </li>
              <li>
                <span className="lp-thing">Devices</span>
                <div className="lp-pair" aria-hidden="true">
                  <span className="lp-mine">
                    <span className="lp-devs">
                      <span>
                        <Smartphone size={12} />
                        iPhone
                      </span>
                      <span>
                        <Laptop size={12} />
                        Work laptop
                      </span>
                    </span>
                  </span>
                  <span className="lp-line" />
                  <span className="lp-srv">
                    <span className="lp-slots">
                      <i className="on" />
                      <i className="on" />
                      <i />
                      <i />
                      <i />
                    </span>
                  </span>
                </div>
                <p className="lp-why">
                  <b>Up to five slots</b>, so you can revoke one. Their names are sealed too.
                </p>
              </li>
              <li>
                <span className="lp-thing">Calls</span>
                <div className="lp-pair" aria-hidden="true">
                  <span className="lp-mine">
                    <span className="lp-callbar">
                      <Phone size={12} />
                      12:04
                      <Wave bars={CALL_WAVE} />
                    </span>
                  </span>
                  <span className="lp-line" />
                  <span className="lp-srv">
                    <span className="lp-env">
                      <Bell size={12} />
                      rang, answered
                    </span>
                  </span>
                </div>
                <p className="lp-why">
                  <b>A ring and a handshake.</b> Audio and video go between your devices, or through a relay that
                  cannot listen.
                </p>
              </li>
              <li>
                <span className="lp-thing">Phone, email</span>
                <div className="lp-pair" aria-hidden="true">
                  <span className="lp-mine">
                    <span className="lp-fieldx">Phone number</span>
                  </span>
                  <span className="lp-line" />
                  <span className="lp-srv">
                    <span className="lp-env nothing">nothing</span>
                  </span>
                </div>
                <p className="lp-why">
                  <b>Never asked for.</b> There is nothing here to link to a person.
                </p>
              </li>
            </ul>
          </div>
        </section>

        <section className="landing-section" aria-labelledby="landing-chats">
          <div className="landing-wrap landing-grid wide">
            <div>
              <h2 id="landing-chats">The same chats on your phone and in this browser</h2>
              <p className="landing-intro">
                An iPhone app, an Android app and the browser share one account. Open a chat on one, and it is there on
                the others.
              </p>
            </div>
            <div>
              <Devices />
              <ul className="lp-does">
                <li>
                  <div className="lp-spec" aria-hidden="true">
                    <span className="lp-callbar">
                      <Monitor size={13} />
                      Sharing your screen <span className="lp-dim">12:04</span>
                      <Wave bars={CALL_WAVE} />
                    </span>
                  </div>
                  <p>
                    <b>Voice and video calls</b>, with screen sharing from a computer.
                  </p>
                </li>
                <li>
                  <div className="lp-spec" aria-hidden="true">
                    <span className="lp-mini stack">
                      <span className="lp-voice">
                        <span className="lp-play">
                          <Play size={11} fill="currentColor" />
                        </span>
                        <Wave bars={NOTE_WAVE} />
                        <span className="lp-len">0:42</span>
                      </span>
                      <span className="lp-tr">
                        <Type size={12} />
                        Perfect, platform four at twenty to seven
                      </span>
                    </span>
                  </div>
                  <p>
                    <b>Voice notes with transcripts</b>, written by your own device rather than a server.
                  </p>
                </li>
                <li>
                  <div className="lp-spec" aria-hidden="true">
                    <span className="lp-shot">
                      <span className="lp-pic">
                        <ImageIcon size={20} />
                      </span>
                      <span className="lp-env">
                        <s>Location 59.33, 18.07</s>
                        <s>iPhone 15 Pro, 24 mm</s>
                        <span>2.1 MB</span>
                      </span>
                    </span>
                  </div>
                  <p>
                    <b>Photos, videos and files</b>, with location and camera data stripped before they leave.
                  </p>
                </li>
                <li>
                  <div className="lp-spec" aria-hidden="true">
                    <span className="lp-lpv">
                      <span className="lp-thumb" />
                      <span>
                        <b>Ridge Trail Loop</b>
                        <span>komoot.com, fetched through the relay</span>
                      </span>
                    </span>
                  </div>
                  <p>
                    <b>Link previews</b> fetched through the relay, so the site never sees you.
                  </p>
                </li>
                <li>
                  <div className="lp-spec" aria-hidden="true">
                    <span className="lp-sn">
                      {SAFETY.map((group, index) => (index < 4 ? <b key={group}>{group}</b> : <span key={group}>{group}</span>))}
                    </span>
                  </div>
                  <p>
                    <b>Safety numbers</b> to compare in person. A changed key is something you see, never something
                    that just happens.
                  </p>
                </li>
                <li>
                  <div className="lp-spec" aria-hidden="true">
                    <span className="lp-toggles">
                      <span>
                        Read receipts
                        <i className="on" />
                      </span>
                      <span>
                        Typing indicators
                        <i />
                      </span>
                      <span>
                        Online and last seen
                        <i />
                      </span>
                    </span>
                  </div>
                  <p>
                    <b>Read receipts, typing and last seen</b> you can switch off, in both directions.
                  </p>
                </li>
              </ul>
            </div>
          </div>
        </section>

        <section className="landing-section" aria-labelledby="landing-start">
          <div className="landing-wrap">
            <div className="landing-start-grid">
              <div>
                <h2 id="landing-start">Pick a username. Everything else is twelve words.</h2>
                <p className="landing-intro">
                  No phone number, no email, nothing to verify. Sign-up takes about a minute, and the words it gives
                  you are the only way back in.
                </p>
              </div>
              {signedIn ? (
                <div className="landing-start">
                  <p className="landing-start-else">
                    This browser already has an account.{" "}
                    <Link className="landing-link" to="/app">
                      Open Shroud
                    </Link>
                  </p>
                </div>
              ) : (
              <form className="landing-start" onSubmit={start} autoComplete="off" noValidate>
                <label htmlFor="landing-username">Username</label>
                <div className="landing-start-field">
                  <span className="landing-at" aria-hidden="true">
                    @
                  </span>
                  <input
                    id="landing-username"
                    name="username"
                    type="text"
                    placeholder="yourname"
                    maxLength={32}
                    spellCheck={false}
                    autoCapitalize="none"
                    value={username}
                    onChange={(event) => setUsername(event.target.value)}
                  />
                  <button className="btn btn-primary landing-btn" type="submit">
                    Create<span>&nbsp;an account</span>
                  </button>
                </div>
                <p className={`landing-start-hint${hint.tone ? ` ${hint.tone}` : ""}`} aria-live="polite">
                  {hint.text}
                </p>
                <p className="landing-start-else">
                  Already on your phone?{" "}
                  <Link className="landing-link" to="/login">
                    Log in
                  </Link>{" "}
                  with the same username and your twelve words, and this browser joins your devices.
                </p>
              </form>
              )}
            </div>
            <div className="landing-host">
              <div className="landing-host-id">
                <BrandMark size={32} />
                <div>
                  <b>{host()}</b>
                  <span>An independent Shroud server</span>
                </div>
              </div>
              <p>
                Run by the people who invited you. Accounts live on this server only, and{" "}
                <a className="landing-link" href={SOURCE_URL} target="_blank" rel="noopener noreferrer">
                  the code is public
                </a>
                , so anyone can check that it does what this page says.
              </p>
            </div>
          </div>
        </section>
      </main>

      <footer className="landing-foot">
        <div className="landing-wrap">
          <Brand />
          <nav aria-label="Footer">
            {signedIn ? (
              <Link to="/app">Open Shroud</Link>
            ) : (
              <>
                <Link to="/signup">Create an account</Link>
                <Link to="/login">Log in</Link>
              </>
            )}
            <a href={SOURCE_URL} target="_blank" rel="noopener noreferrer">
              Source code
            </a>
          </nav>
        </div>
      </footer>
    </div>
  );
}
