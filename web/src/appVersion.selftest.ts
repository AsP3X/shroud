/**
 * When the tab asks about a newer build, and when the banner shows. Run: `npx tsx src/appVersion.selftest.ts`.
 */
import { bannerShown, CHECK_GAP_MS, shouldCheck, updateFromAnswer } from "./appVersion";

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
