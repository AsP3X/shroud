import { usePageData } from "../api/usePageData";
import type { AppRelease, ClientVersions as ClientVersionsData, UpdateStatus } from "../api/types";
import { PageFrame } from "../components/PageFrame";
import { Card, CardBody, CardHead, Chip, Notice, PageHeader, StatusPill, type Tone } from "../components/ui";

const TOLD: Record<UpdateStatus, { label: string; tone: Tone }> = {
  update_required: { label: "Update required", tone: "danger" },
  update_available: { label: "Update offered", tone: "warn" },
  current: { label: "Current", tone: "ok" },
};

const VERSIONS_TEXT: Record<string, string> = { unnamed: "Names no version", other: "Another build" };

function versionsLabel(versions: string, platform: "ios" | "android" | "web"): string {
  if (VERSIONS_TEXT[versions]) return VERSIONS_TEXT[versions] ?? versions;
  if (versions.startsWith("<")) return `Below ${versions.slice(1)}`;
  if (versions.startsWith(">=")) return `${versions.slice(2)} and newer`;
  return platform === "web" ? "Same build" : versions;
}

function NOTE(platform: "ios" | "android" | "web", versions: string, status: UpdateStatus): string | null {
  if (versions === "unnamed") return platform === "ios" ? "Builds before 1.1 never ask" : platform === "android" ? "Refused while a minimum is set" : "Refused with 426 until reloaded";
  if (status === "update_required") return "Refused with 426 until it updates";
  if (status === "update_available") return platform === "web" ? "Written by deploy.sh once up succeeded" : "Once per version and launch";
  return null;
}

function EnvRows({ rows }: { rows: { name: string; value: string | null }[] }) {
  return (
    <div className="kv">
      {rows.map((row) => (
        <div className="kv__row" key={row.name} style={{ minHeight: 34, padding: "9px 0" }}>
          <span className="mono small text-secondary">{row.name}</span>
          <span className={row.value === null ? "mono small text-tertiary" : "mono small text-primary"} style={{ marginLeft: "auto", textAlign: "right", overflowWrap: "anywhere" }}>
            {row.value ?? "Not set"}
          </span>
        </div>
      ))}
    </div>
  );
}

function pill(release: AppRelease): { label: string; tone: Tone } {
  if (release.minimum) return { label: "Minimum enforced", tone: "ok" };
  if (release.latest) return { label: "Update offered", tone: "warn" };
  return { label: "Nothing checked", tone: "neutral" };
}

function PlatformCard({ title, platform, rows, status, told }: { title: string; platform: "ios" | "android" | "web"; rows: { name: string; value: string | null }[]; status: { label: string; tone: Tone }; told: { versions: string; status: UpdateStatus }[] }) {
  return (
    <Card>
      <CardHead title={title} />
      <EnvRows rows={rows} />
      <div className="told">
        <div className="told__head">
          <span className="told__title">{platform === "web" ? "WHAT A TAB IS TOLD" : "WHAT AN APP IS TOLD"}</span>
          <StatusPill tone={status.tone}>{status.label}</StatusPill>
        </div>
        {told.map((row) => {
          const note = NOTE(platform, row.versions, row.status);
          const chip = TOLD[row.status];
          return (
            <div className="told__row" key={row.versions}>
              <span className="told__cond">
                <span className="text-primary">{versionsLabel(row.versions, platform)}</span>
                {note ? <span className="small text-tertiary">{note}</span> : null}
              </span>
              <Chip tone={chip.tone}>{chip.label}</Chip>
            </div>
          );
        })}
      </div>
    </Card>
  );
}

/** Frames "Client versions" and "Client versions · Nothing set" (§3.4). */
export function ClientVersions() {
  const page = usePageData<ClientVersionsData>("/client-versions");
  return (
    <PageFrame title="Client versions" page={page}>
      {(data) => {
        const nothing = !data.ios.minimum && !data.ios.latest && !data.android.minimum && !data.android.latest;
        return (
          <>
            <PageHeader
              title="Client versions"
              meta={
                nothing
                  ? `shroud-server ${data.server_version} · no latest or minimum version set · no app is ever asked to update, nothing is refused`
                  : `shroud-server ${data.server_version} · minimums and latest releases from the environment at start · web build re-read on each question`
              }
            />
            <Notice>
              Read-only. Change a minimum or latest version in .env and run ./deploy.sh, which restarts the API. iOS compares its marketing
              version, Android its versionName, a web tab its bundle's build id. The server keeps nothing about which versions ask, so there is
              no count of blocked apps here.
            </Notice>
            <div className="columns">
              <div className="column" style={{ flex: "0 0 400px", width: 400 }}>
                <PlatformCard
                  title="iOS"
                  platform="ios"
                  status={pill(data.ios)}
                  rows={[
                    { name: "IOS_LATEST_VERSION", value: data.ios.latest },
                    { name: "IOS_MIN_VERSION", value: data.ios.minimum },
                    { name: "IOS_UPDATE_URL", value: data.ios.update_url?.replace(/^https?:\/\//, "") ?? null },
                  ]}
                  told={data.told.ios}
                />
                <PlatformCard
                  title="Android"
                  platform="android"
                  status={pill(data.android)}
                  rows={[
                    { name: "ANDROID_LATEST_VERSION", value: data.android.latest },
                    { name: "ANDROID_MIN_VERSION", value: data.android.minimum },
                    { name: "ANDROID_UPDATE_URL", value: data.android.update_url?.replace(/^https?:\/\//, "") ?? null },
                  ]}
                  told={data.told.android}
                />
              </div>
              <div className="column" style={{ flex: "0 0 400px", width: 400 }}>
                <PlatformCard
                  title="Web"
                  platform="web"
                  status={data.web.deployed_build || data.web.fixed_build ? { label: "Reload only", tone: "neutral" } : { label: "Nothing checked", tone: "neutral" }}
                  rows={[
                    { name: "WEB_BUILD_FILE", value: data.web.build_file },
                    { name: "Deployed build", value: data.web.deployed_build ?? (data.web.build_file ? "No file yet" : null) },
                    { name: "WEB_BUILD", value: data.web.fixed_build },
                  ]}
                  told={data.told.web}
                />
                <Card>
                  <CardHead title="Rollout order" sub="Each step is a change to .env, then ./deploy.sh" />
                  <CardBody>
                    {[
                      ["Ship the builds that name themselves", "iOS 1.1 and the Android APK's VERSION_NAME. Before that, a minimum would also refuse the current apps."],
                      ["Set the LATEST_VERSION", "Older apps offer the update once per version and launch; the user may dismiss it."],
                      ["Set the MIN_VERSION", "Older apps block until updated, and the API refuses them. Must not be newer than the latest, or the server won't start."],
                    ].map(([title, detail], index) => (
                      <div className="step" key={title}>
                        <span className="step__number">{index + 1}</span>
                        <span className="step__text">
                          <span className="text-primary" style={{ fontWeight: 500 }}>
                            {title}
                          </span>
                          <span className="small text-tertiary" style={{ lineHeight: 1.45 }}>
                            {detail}
                          </span>
                        </span>
                      </div>
                    ))}
                  </CardBody>
                </Card>
              </div>
              <div className="column">
                <Card>
                  <CardHead title="Enforced on every request" sub={nothing ? "Nothing is checked until a minimum is set" : "Once either minimum is set"} />
                  <CardBody>
                    <div className="text-secondary" style={{ lineHeight: 1.5 }}>
                      {nothing ? "With no minimum set, every request passes, named or not. " : ""}
                      Every route answers 426 UPDATE_REQUIRED to an app below its platform's minimum, and to any request that doesn't name its
                      app in X-Shroud-Client (or ?client= on the sockets): iOS before 1.1, Android from before the header, web tabs loaded before
                      it.
                    </div>
                    <div>
                      <div className="small text-tertiary" style={{ fontWeight: 600, marginBottom: 8 }}>
                        STAYS OPEN TO EVERY BUILD
                      </div>
                      <div style={{ display: "flex", flexDirection: "column", gap: 6, alignItems: "flex-start" }}>
                        {["/health", "/health/live", "/health/ready", "/client-version"].map((path) => (
                          <Chip mono key={path}>
                            {path}
                          </Chip>
                        ))}
                      </div>
                    </div>
                    <div className="text-secondary" style={{ lineHeight: 1.5 }}>
                      A token that no longer authenticates gets its 401 first (UNAUTHORIZED or DEVICE_REMOVED), so a removed device on an old
                      build still wipes itself. A 426 never signs anyone out; a blocked app shows its own Update required screen.
                    </div>
                  </CardBody>
                </Card>
              </div>
            </div>
          </>
        );
      }}
    </PageFrame>
  );
}
