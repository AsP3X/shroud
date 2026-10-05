export const meta = {
  name: 'android-wave0',
  description: 'Revise the port plan for Google-free push, build Wave 0 (scaffold, wire contracts, crypto foundation), merge, verify, review and fix',
  phases: [
    { title: 'Revise', detail: 'update the plan for UnifiedPush + background connection, no Firebase' },
    { title: 'Build', detail: 'W0-A, W0-B, W0-C in parallel worktrees' },
    { title: 'Integrate', detail: 'merge into dev, full build, device acceptance' },
    { title: 'Review', detail: 'adversarial review of the wave diff' },
    { title: 'Fix', detail: 'apply confirmed findings, re-verify' },
  ],
}

const { specDir, toolsDir, baseSha, repo } = args
const GW = `${toolsDir}/gw`

phase('Revise')
const revision = await agent(`You maintain the Shroud Android port plan in ${specDir} (entry point 00-plan.md; area specs alongside). The product owner has now decided (2026-10-01):
1. PUSH: "No Firebase ever — Google is not data-protection safe." No FCM, no Google Play services, no Firebase code or Gradle plugin anywhere, no 'play'/'foss' flavor split: ONE Google-free build. Push = UnifiedPush (RFC 8030/8291 Web Push via a user-installed distributor such as ntfy; the server already sends Web Push + VAPID) as the primary transport, PLUS an opt-in "background connection" fallback for phones with no distributor: a foreground service keeping the existing WebSocket open (permanent low-importance notification), which is how messages, call rings and device-removed wipes arrive without a distributor. Ringing when killed/locked (plan P2) must work through UnifiedPush high-urgency messages (Urgency: high, TTL = ring time) and through the background connection. Decrypt RFC 8291 aes128gcm ourselves with BouncyCastle (already a dependency) rather than pulling Google Tink; verify which UnifiedPush connector artifact (org.unifiedpush.android:connector or similar, check Maven Central metadata for the latest version and its transitive dependencies) is free of Google/Firebase/Play-services dependencies; if it drags in Tink or play-services, plan to implement the small UnifiedPush receiver protocol directly (it is a set of broadcast intents) instead.
2. iOS and web changes listed in the plan: all approved.
3. Transcription: whisper.cpp approved.
4. All other owner decisions (P3–P18): take the plan's recommendation, EXCEPT anything that assumes Google Play/Firebase (e.g. P16a Play App Signing: make the default an own release key + direct APK / F-Droid; Play publishing only as an optional later channel without Google services inside the app). Also make sure no package depends on Google Play services or ML Kit (ZXing for QR is fine; AndroidX/CameraX/Media3 libraries are fine as they run locally).
Edit 00-plan.md in place so it is consistent with these decisions: §0 table and critical path, §1 principles and package layout (no src/play, src/foss; core/push gets unifiedpush/ and backgroundconnection/), §1.3 AppContainer, §1.5 storage rows, the §1.6/§1.7 contract parts touching push, §2 package cards (W0-A: single build, no flavors, no Firebase BuildConfig fields; replace X1-SRV-FCM with X1-SRV-UP: server UnifiedPush support moved into Wave 1 — Android subscriptions via the existing web push route with a client field, distributor host policy (allow-list env such as UNIFIEDPUSH_PUBLIC_HOSTS plus sane defaults like ntfy.sh), Urgency/TTL handling, call rings and call_ended and device_removed wake and badge/read to Android subscriptions regardless of foreground for calls, tests run with --nocapture; W3-PUSH: UnifiedPush receiver + background-connection foreground service + settings UI; drop W4-UP or fold it), §2.6 shared-file ownership, §3 decisions table (mark P1, P2, P7, P10, P11 and the rest as DECIDED with the outcome), §4 cross-component rows (S1 becomes the UnifiedPush server work), §5 dependencies (remove Firebase, add the UnifiedPush choice), §6.5 push/calls e2e (use a local ntfy server in Docker as the distributor for tests: binwiederhier/ntfy image, plus the ntfy Android app or a test distributor stub), §7 risks. Also append a short "Decision record 2026-10-01" section at the top listing the decisions. Update notifications-push.md and calls.md with a clearly marked "REVISION 2026-10-01" section at their top summarising what changes (do not rewrite their bodies). Return a concise summary of what you changed (under 250 words).`, { label: 'revise-plan', phase: 'Revise', effort: 'high' })
log('plan revised')

const PKG_PREAMBLE = (id, branch, wt) => `You implement work package ${id} of the Shroud Android port (1:1 parity with the iOS app in ios/ and the web client in web/; the Rust server in server/ is the wire contract). Your git worktree ALREADY EXISTS at ${wt} (branch ${branch}, based on ${baseSha}) and holds PARTIAL, UNCOMMITTED work from an earlier run of this same package that was interrupted. cd into ${wt} and work only there (use absolute paths; read files from ${wt}, not from the main checkout). Start by reviewing what is already there (git status, git diff, the new files) against your card: keep what is right, fix or finish what is not, then complete the package.

READ FIRST: ${specDir}/00-plan.md — the "Decision record" at the top, §1 (architecture, layout, contracts — especially the parts your card names), §2.0 (rules), §2.6 (shared-file ownership), §5 (dependencies) and YOUR PACKAGE CARD ${id} in §2.1. Then every area-spec section your card's "Follow" line names (area specs are in ${specDir}). The iOS sources are the reference: when the spec and iOS code disagree, check the code and follow it unless the plan resolves it otherwise (§1.6); cite iOS file:line in KDoc for ported behaviour. Existing Android code: android/ (see android/README.md).

GIT: in ${wt} confirm \`git branch --show-current\` is ${branch} and HEAD descends from ${baseSha}. Commit your work on branch ${branch} (one or more commits; message style "ADD: …"/"TASK: …"/"FIX: …" in a plain sentence, with no Co-Authored-By or other AI attribution line (AGENTS.md)). Do NOT push. Write only the paths your card's "Owns" list gives you (plus their tests); every other path is read-only — if you need a change elsewhere, record it as a contract change request in your final report instead.

BUILD/TEST: never call ./gradlew directly; from the android/ directory run \`${GW} <tasks>\` (it rations machine-wide build slots; waiting for a slot is normal). Make the tests your card lists pass, and keep the whole JVM test suite and \`${GW} :app:assembleDebug\` green. Do not start emulators or servers — device acceptance is done by the integration step after the merge; describe in your report what it should check. No Material components, no Google Play services/Firebase/ML Kit dependencies. Never log tokens, keys, names or plaintext.

FINAL REPORT (structured): what you built, files, tests added and their status, deviations from the card with reasons, contract change requests, and precise notes for the integration step (device checks to run, anything it must wire).`

const REPORT = {
  type: 'object',
  properties: {
    package: { type: 'string' }, branch: { type: 'string' }, headSha: { type: 'string' },
    summary: { type: 'string' },
    filesChanged: { type: 'array', items: { type: 'string' } },
    testsAdded: { type: 'array', items: { type: 'string' } },
    unitTestsPassing: { type: 'boolean' }, assembleDebugPassing: { type: 'boolean' },
    deviations: { type: 'array', items: { type: 'string' } },
    contractChangeRequests: { type: 'array', items: { type: 'string' } },
    integrationNotes: { type: 'array', items: { type: 'string' } },
  },
  required: ['package', 'branch', 'headSha', 'summary', 'unitTestsPassing', 'assembleDebugPassing', 'integrationNotes'],
}

phase('Build')
const PKGS = [
  { id: 'W0-A', branch: 'android/w0-a', wt: repo + '/.claude/worktrees/wf_21787de6-4ee-2', extra: 'Note: the main checkout has an untracked android/gradle/gradle-daemon-jvm.properties generated by the owner\'s Android Studio (it pins the Gradle daemon to JDK 25 via foojay URLs); it is not in your worktree. Decide whether the build should pin the daemon JVM (prefer JDK 21, which is installed) and record your decision; the integration step will delete or replace the untracked file accordingly. The e2e scripts to adopt into android/e2e/ are in ' + toolsDir + ' (stack-up.sh, stack-down.sh, ui.py, launch.sh, enter_phrase.sh) — copy and generalise them, do not reference the tools dir from committed files.' },
  { id: 'W0-B', branch: 'android/w0-b', wt: repo + '/.claude/worktrees/wf_21787de6-4ee-3', extra: '' },
  { id: 'W0-C', branch: 'android/w0-c', wt: repo + '/.claude/worktrees/wf_21787de6-4ee-4', extra: '' },
]
const built = await parallel(PKGS.map(p => () => agent(
  `${PKG_PREAMBLE(p.id, p.branch, p.wt)}\n\n${p.extra}`,
  { label: `build:${p.id}`, phase: 'Build', schema: REPORT }
)))
const reports = built.filter(Boolean)
log(`${reports.length}/3 packages reported: ` + reports.map(r => `${r.package} tests=${r.unitTestsPassing} build=${r.assembleDebugPassing}`).join(', '))

phase('Integrate')
const integ = await agent(`You are the Wave 0 integration engineer for the Shroud Android port, working in the MAIN checkout at ${repo} (branch dev, base ${baseSha}). Read ${specDir}/00-plan.md (decision record, §1, §2.0, §2.1 cards, §2.6, §6.3) first.

The three Wave 0 packages committed to these branches (reports below). 1) Merge them into dev in the order W0-A, W0-B, W0-C (\`git merge --no-ff <branch>\` with a message like "TASK: Merge W0-A …", with no Co-Authored-By or other AI attribution line (AGENTS.md)); resolve conflicts faithfully to each package's intent and the plan. 2) Apply the contract change requests that are justified, and the integration notes. Handle the untracked android/gradle/gradle-daemon-jvm.properties per W0-A's decision (delete it, or replace it with W0-A's pinned version). 3) Build and test from android/ with \`${GW} <tasks>\` (never ./gradlew directly): all JVM unit tests, lint, the no-Material check if W0-A added one, assembleDebug and assembleRelease. Fix integration breakages (you may edit any file now). 4) Device acceptance per the W0 cards and plan §6.3: start the throwaway local stack with the committed android/e2e/stack-up.sh (or ${toolsDir}/stack-up.sh if not committed), boot the AVDs shroud_api37 (Android 17) and shroud_api30 (Android 11) with ~/Library/Android/sdk/emulator/emulator -avd <name> -no-audio -no-snapshot -no-boot-anim -port 5560/5562 (one at a time if memory is tight; keep the screen on with \`adb shell svc power stayon true\`; the PIN 1234 lock is already set on both or set it with locksettings set-pin 1234; Android 17 needs ACCESS_LOCAL_NETWORK — grant with pm grant or tap Allow), install the debug APK and verify: app launches to Welcome; two-step Sign Up of a fresh test account works; Log Out; Log In (credentials + phrase) works; no crash in \`adb logcat -b crash\`. Drive the UI with an adb driver (android/e2e/ui.py or ${toolsDir}/ui.py: dump / tap <text> / type / key / wait / shot) — remember: adb input text needs shell quoting for '!', taps after an install can race the launch (force-stop + am start -W), the PIN lock re-engages if the screen sleeps. Test credentials are throwaway values you generate; never print them in your final report. 5) After the merges succeed, remove the three package worktrees (\`git worktree remove --force <path>\` for ${repo}/.claude/worktrees/wf_21787de6-4ee-2, -3, -4; keep the branches). Never commit anything under .claude/. Stop the stack and the emulators when done (android/e2e/stack-down.sh; adb -s emulator-5560 emu kill). Leave the Pixel_10_Pro_XL emulator and any process you did not start alone. 6) Commit any integration fixes on dev (do NOT push).

Package reports:\n${JSON.stringify(reports, null, 1)}\n\nReturn a structured report.`, {
  label: 'integrate', phase: 'Integrate', effort: 'high',
  schema: { type: 'object', properties: {
    mergedSha: { type: 'string' }, merged: { type: 'array', items: { type: 'string' } },
    unitTests: { type: 'string' }, lint: { type: 'string' }, releaseBuild: { type: 'string' },
    deviceAcceptance: { type: 'array', items: { type: 'object', properties: { check: { type: 'string' }, api: { type: 'string' }, result: { type: 'string' } }, required: ['check', 'result'] } },
    fixesApplied: { type: 'array', items: { type: 'string' } }, openIssues: { type: 'array', items: { type: 'string' } } },
    required: ['mergedSha', 'merged', 'unitTests', 'deviceAcceptance', 'openIssues'] },
})

phase('Review')
const LENSES = [
  { key: 'correctness', prompt: 'correctness and robustness: bugs, wrong wire formats vs the server/iOS/web, wrong crypto constants or byte layouts, broken lifecycle/threading, crash paths, tests that do not actually test what they claim (vectors retyped instead of copied, assertions that cannot fail)' },
  { key: 'security', prompt: 'security and privacy invariants (docs/architecture.md 1-13, plan §1.1 principle 7, §1.5 storage table): plaintext or secrets on disk/logs/saved state/backups, missing zeroing, Keystore misuse, permission/manifest exposure (exported components, intent filters, providers), dependencies that phone home or pull Google Play services/Firebase' },
  { key: 'plan-conformance', prompt: 'conformance to 00-plan.md: every item of the W0-A/W0-B/W0-C cards delivered (files, tests, acceptance), shared contracts in §1.7 exactly as specified (signatures that W1 packages will code against), ownership rules respected, nothing from later waves half-built in a way that will conflict, build config/manifest per §5' },
]
const FINDINGS = { type: 'object', properties: { findings: { type: 'array', items: { type: 'object', properties: {
  title: { type: 'string' }, file: { type: 'string' }, line: { type: 'number' }, severity: { type: 'string', enum: ['blocker', 'major', 'minor'] },
  evidence: { type: 'string' }, fix: { type: 'string' } }, required: ['title', 'file', 'severity', 'evidence', 'fix'] } } }, required: ['findings'] }
const VERDICT = { type: 'object', properties: { real: { type: 'boolean' }, reason: { type: 'string' } }, required: ['real', 'reason'] }

const reviewed = await pipeline(LENSES,
  l => agent(`Review the Wave 0 changes of the Shroud Android port in ${repo}: \`git diff ${baseSha}..HEAD\` on branch dev (read the full files, not just hunks). Plan: ${specDir}/00-plan.md (decision record, §1, §2.1 cards, §5). Lens: ${l.prompt}. Read-only: do not edit or commit. Report only findings you verified in the code, each with file:line evidence and a concrete fix; no style nits.`, { label: `review:${l.key}`, phase: 'Review', schema: FINDINGS }),
  r => parallel((r?.findings || []).filter(f => f.severity !== 'minor' || true).map(f => () =>
    agent(`Adversarially verify this code-review finding about the Shroud Android repo at ${repo} (branch dev). Try hard to REFUTE it by reading the actual code, tests and the plan ${specDir}/00-plan.md. Default to real=false if the evidence does not hold up.\nFinding: ${JSON.stringify(f)}`, { label: `verify:${f.file.split('/').pop()}`, phase: 'Review', schema: VERDICT, effort: 'medium' })
      .then(v => v && v.real ? f : null))).then(xs => xs.filter(Boolean)))
const confirmed = reviewed.filter(Boolean).flat()
log(`${confirmed.length} confirmed findings`)

phase('Fix')
let fixReport = null
if (confirmed.length) {
  fixReport = await agent(`Apply these verified review findings to the Shroud Android port in the MAIN checkout ${repo} (branch dev). Read ${specDir}/00-plan.md as needed. For each finding make the fix (or explain precisely why not), add or adjust tests that would have caught it, then from android/ run \`${GW} <tasks>\` for all JVM unit tests, lint, assembleDebug and assembleRelease until green. Commit on dev with a message per logical fix ("FIX: …", no Co-Authored-By or other AI attribution line (AGENTS.md)). Do NOT push. If a fix changes app behaviour on device, re-run the relevant device check (local stack + emulator as described in android/e2e/) and report it.\nFindings:\n${JSON.stringify(confirmed, null, 1)}`, {
    label: 'fix', phase: 'Fix', effort: 'high',
    schema: { type: 'object', properties: { fixed: { type: 'array', items: { type: 'string' } }, notFixed: { type: 'array', items: { type: 'string' } }, finalSha: { type: 'string' }, testsGreen: { type: 'boolean' } }, required: ['fixed', 'notFixed', 'finalSha', 'testsGreen'] },
  })
}
return { revision, reports, integ, confirmed, fixReport }