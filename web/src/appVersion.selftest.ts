/**
 * When the tab asks about a newer build, when the banner shows, and what Settings → About Shroud
 * says. Run: `npx tsx src/appVersion.selftest.ts`.
 */
import {
  bannerShown,
  CHECK_GAP_MS,
  serverVersionFromAnswer,
  shouldCheck,
  updateFromAnswer,
  updateStatus,
} from "./appVersion";

function check(cond: boolean, message: string): void {
  if (!cond) throw new Error(message);
}

const now = 1_000_000_000;

// When to ask.
check(shouldCheck("startup", now, null, false), "load asks");
check(shouldCheck("startup", now, now - 1000, true), "load asks even hidden, right after another question");
check(shouldCheck("reconnect", now, now - 1000, false), "a reconnected socket always asks");
check(shouldCheck("confirm", now, now - 1000, false), "waiting for the new page to be served asks again");
check(shouldCheck("visible", now, null, false), "shown without a question yet asks");
check(!shouldCheck("visible", now, now - CHECK_GAP_MS + 1, false), "shown again within ten minutes does not ask");
check(shouldCheck("visible", now, now - CHECK_GAP_MS, false), "shown again after ten minutes asks");
check(!shouldCheck("visible", now, null, true), "a still-hidden tab does not ask");
check(shouldCheck("interval", now, now - 30 * 60 * 1000, false), "the half-hourly timer asks in front");
check(!shouldCheck("interval", now, now - 30 * 60 * 1000, true), "the timer skips a hidden tab");
check(!shouldCheck("interval", now, now - 60 * 1000, false), "the timer skips right after another question");
check(shouldCheck("manual", now, now - 1000, false), "Check for Updates asks right after another question");
check(shouldCheck("manual", now, now - 1000, true), "Check for Updates asks whatever the tab says about being hidden");

// Reading the answer.
{
  const current = updateFromAnswer({ status: "current", latest_version: "abc", update_url: null });
  check(current?.status === "current" && current.latest === "abc", "current is kept");
  const available = updateFromAnswer({ status: "update_available", latest_version: "def", update_url: null });
  check(available?.status === "update_available" && available.latest === "def", "update_available is kept");
  const required = updateFromAnswer({ status: "update_required", latest_version: null, update_url: null });
  check(required?.status === "update_required" && required.latest === null, "update_required is kept");
  check(
    updateFromAnswer({ status: "update_available", latest_version: "", update_url: null })?.latest === null,
    "an empty latest version is none",
  );
  check(
    updateFromAnswer({ status: "later" as never, latest_version: "x", update_url: null }) === null,
    "an unknown status is ignored",
  );
  check(updateFromAnswer(null) === null, "no answer is ignored");
}

// The server's version.
{
  const answer = { status: "current" as const, latest_version: "abc", update_url: null };
  check(serverVersionFromAnswer({ ...answer, server_version: "0.1.0" }) === "0.1.0", "the server's version is kept");
  check(serverVersionFromAnswer(answer) === null, "an older server sends none");
  check(serverVersionFromAnswer({ ...answer, server_version: " " }) === null, "a blank version is none");
  check(serverVersionFromAnswer({ ...answer, server_version: 7 as never }) === null, "a number is not a version");
  check(serverVersionFromAnswer(null) === null, "no answer, no version");
}

// What the About page's row says.
{
  const current = { status: "current" as const, latest: "b1" };
  const newer = { status: "update_available" as const, latest: "b2" };
  check(updateStatus(false, false, false, null) === "idle", "a build without an id never asks");
  check(updateStatus(false, true, true, newer) === "idle", "whatever else is known");
  check(updateStatus(true, false, false, null) === "idle", "nothing asked yet");
  check(updateStatus(true, true, false, null) === "checking", "a question out");
  check(updateStatus(true, true, false, newer) === "checking", "a question out, even with a newer build known");
  check(updateStatus(true, false, false, current) === "current", "up to date");
  check(updateStatus(true, false, false, newer) === "available", "a newer build, served");
  check(
    updateStatus(true, false, false, { status: "update_required", latest: "b2" }) === "available",
    "a required one reads the same",
  );
  check(updateStatus(true, false, true, null) === "failed", "the first question failed");
  check(updateStatus(true, false, true, current) === "failed", "the last question failed");
  check(updateStatus(true, false, true, newer) === "available", "a failed question doesn't hide a newer build");
}

// When the banner shows.
const available = { status: "update_available" as const, latest: "b2" };
check(!bannerShown(null, null), "nothing known, nothing shown");
check(!bannerShown({ status: "current", latest: "b1" }, null), "current shows nothing");
check(bannerShown(available, null), "a newer build shows the banner");
check(bannerShown({ status: "update_required", latest: "b2" }, null), "so does a required one");
check(!bannerShown(available, { latest: "b2" }), "closed for that build, it stays closed");
check(bannerShown({ status: "update_available", latest: "b3" }, { latest: "b2" }), "a different build shows it again");
check(!bannerShown({ status: "current", latest: "b3" }, { latest: "b2" }), "back on the deployed build shows nothing");
check(
  !bannerShown({ status: "update_available", latest: null }, { latest: null }),
  "closed without a build name stays closed while the server names none",
);

console.log("appVersion selftest ok");
