export const meta = {
  name: 'android-wave',
  description: 'Build one wave of the Shroud Android port: packages in parallel worktrees, merge into dev, integration package, device acceptance, adversarial review, fixes',
  phases: [
    { title: 'Build', detail: 'one agent per work package, each in its own worktree + branch' },
    { title: 'Merge', detail: 'merge every package branch into dev, build green' },
    { title: 'Integrate', detail: 'the wave INT package on dev' },
    { title: 'Accept', detail: 'device + cross-component acceptance of the wave exit gate' },
    { title: 'Review', detail: 'lens reviewers, each finding adversarially verified' },
    { title: 'Fix', detail: 'apply confirmed findings, re-verify' },
  ],
}

const { wave, baseSha, specDir, toolsDir, repo, packages, intId, exitGate } = args
const GW = `${toolsDir}/gw`
const NO_CO = 'no Co-Authored-By or other AI attribution line (AGENTS.md)'
const PLAN = `${specDir}/00-plan.md`

const COMMON = `Project: the Shroud end-to-end encrypted messenger. The Android app (android/, Kotlin + Compose, no Material, no Google services of any kind) is being ported 1:1 from the iOS app (ios/, the reference) with the web client (web/) as second reference and the Rust server (server/) as wire contract. The master plan is ${PLAN} — read its "Decision record" at the top, §1 (architecture, layout, storage, resolved conflicts, shared contracts), §2.0 (rules), §2.6 (shared-file ownership) and §5 (dependencies) before anything else. Area specs (detailed, with iOS file:line) are next to it in ${specDir}. Never log tokens, keys, names or plaintext; never put secrets in rememberSaveable or logs.`

const COMMIT_RULES = (branch) => `Commit EARLY AND OFTEN on branch ${branch} (at least after every meaningful step — a session can be interrupted and uncommitted work is lost). Message style: one plain sentence starting with "ADD: ", "TASK: " or "FIX: ", with ${NO_CO}. Do NOT push.`

const KIND_RULES = {
  android: `BUILD/TEST: never call ./gradlew directly; from android/ run \`${GW} <tasks>\` (it rations machine-wide build slots; waiting is normal). Keep \`${GW} :app:testDebugUnitTest :app:lintDebug :app:verifyNoMaterial :app:verifyNoGoogleServices :app:assembleDebug\` green. Do not start emulators or servers unless your card's acceptance needs a device AND you are W${wave}-KEYS-like package explicitly allowed below; device acceptance is otherwise done after the merge — describe exactly what it must check in your report.`,
  server: `RUST: work in server/. Use a cloned target dir to avoid a full rebuild: \`cp -Rc ${repo}/server/target server/target\` if it exists (APFS clone). Run \`cargo fmt --check\`, \`cargo clippy --all-targets -- -D warnings\` and the tests against a THROWAWAY Postgres + Redis in Docker on your own free ports (e.g. docker run -d --rm --name shroud-w${wave}-pg -e POSTGRES_USER=shroud -e POSTGRES_PASSWORD=shroud -e POSTGRES_DB=shroud_test -p 127.0.0.1:<port>:5432 postgres:16-alpine; never the shroud-postgres dev DB). Tests silently pass as "skipped" when the DB is unreachable: run with \`-- --nocapture\` and make sure \`grep -ci skipping\` is 0. For Web Push/UnifiedPush end-to-end use a local ntfy (docker image binwiederhier/ntfy, already pulled). Remove your containers when done.`,
  ios: `iOS: Swift/SwiftUI in ios/. Build and test with xcodebuild on a FRESH simulator you create for this run (xcrun simctl create …; iOS 26.5 runtime; not the user's existing simulators), with parallel testing disabled (-parallel-testing-enabled NO), and delete it afterwards; Keychain-backed tests are known to fail with -34018 on reused/parallel sims (memory note keychain-tests-fail-in-sim-host). Prefer adding cases to existing test files; if you must add a file, add it to the Xcode project correctly. Xcode may be open and rewrite the pbxproj — that is not your edit. Do not edit .pen files: list the visible iOS UI changes you made so the wave's design package can mirror them in design/iOS-App.pen.`,
  web: `WEB: TypeScript/React in web/. If web/node_modules is missing in your worktree, symlink the main checkout's: \`ln -s ${repo}/web/node_modules web/node_modules\` (do not commit it; run npm ci in the main checkout first if that directory is missing). Run the relevant selftests with \`npx tsx <file>\` and \`npm run build\`. Do not edit .pen files: list visible web UI changes for the design package.`,
  design: `An earlier, interrupted run of this package may already have made part of these edits in the documents open in Pen (unsaved): inspect the current state first and continue from it instead of duplicating frames or variables. DESIGN (Pencil MCP only — never read or write .pen files directly; load the tools with ToolSearch "select:mcp__pencil__get_app_state,mcp__pencil__execute,mcp__pencil__read_skill" and read the skill + execute.md first). Before editing a file, bring it to the front with \`open -a /Applications/Pen.app ${repo}/design/<File>.pen\` and confirm with get_app_state that it is the active editor (execute's filePath is unreliable with several documents open). Reuse existing components and variables; new root frames get placeholder:true while being built; verify each frame with a screenshot and a layout-problem visitor. You cannot save: the user must press ⌘S in Pen afterwards. Do not commit anything. Report every changed/added frame with its id and file.`,
}

const PKG_PROMPT = (p) => `${COMMON}

You implement work package ${p.id} (wave ${wave}). Find its card in ${PLAN} §2 and follow it exactly: its "Follow" sections in the area specs, its "Owns" list (write only those paths plus their tests; every other path is read-only — record needed changes elsewhere as contract change requests), its tests and its acceptance criteria. When a spec and the iOS code disagree, check the code; follow it unless the plan resolves the point (§1.6). Cite iOS file:line in KDoc for ported behaviour. Copy test vectors byte for byte from the iOS/web test sources.

${p.kind === 'design' ? '' : `GIT: you are in a fresh isolated worktree. If branch ${p.branch} already exists (\`git rev-parse --verify --quiet ${p.branch}\`), it holds work from an earlier, interrupted run of this same package (commits may be titled "WIP: …"): check it out with \`git checkout ${p.branch}\` (NEVER \`checkout -B\`, which would throw that work away), review what is there against your card, keep what is right, fix what is not and finish the package. Otherwise create it with \`git checkout -b ${p.branch} ${baseSha}\`. The wave base is ${baseSha}. ${COMMIT_RULES(p.branch)}`}

${KIND_RULES[p.kind]}

${p.extra || ''}

FINAL REPORT (structured): what you built, files, tests and their status, deviations with reasons, contract change requests (file, old → new, why, consumers), and precise notes for the merge/integration/acceptance steps.`

const REPORT = {
  type: 'object',
  properties: {
    package: { type: 'string' }, branch: { type: 'string' }, headSha: { type: 'string' },
    summary: { type: 'string' },
    filesChanged: { type: 'array', items: { type: 'string' } },
    testsAdded: { type: 'array', items: { type: 'string' } },
    testsPassing: { type: 'boolean' }, buildPassing: { type: 'boolean' },
    deviations: { type: 'array', items: { type: 'string' } },
    contractChangeRequests: { type: 'array', items: { type: 'string' } },
    integrationNotes: { type: 'array', items: { type: 'string' } },
    designChangesNeeded: { type: 'array', items: { type: 'string' } },
  },
  required: ['package', 'branch', 'headSha', 'summary', 'testsPassing', 'buildPassing', 'integrationNotes'],
}

phase('Build')
const built = await parallel(packages.map(p => () => agent(PKG_PROMPT(p), {
  label: `build:${p.id}`, phase: 'Build', schema: REPORT,
  ...(p.kind === 'design' ? {} : { isolation: 'worktree' }),
})))
const reports = built.filter(Boolean)
const missing = packages.filter(p => !reports.some(r => r.package && r.package.startsWith(p.id))).map(p => p.id)
log(`${reports.length}/${packages.length} packages reported` + (missing.length ? `; missing: ${missing.join(', ')}` : ''))

if (reports.length === 0) { log('no package reported — stopping before the merge'); return { wave, reports, missing } }

phase('Merge')
const codeReports = reports.filter(r => !packages.find(p => p.id === r.package && p.kind === 'design'))
const merge = await agent(`${COMMON}

You are the wave ${wave} MERGE engineer in the MAIN checkout ${repo} (branch dev, HEAD should be ${baseSha}). Merge every package branch below into dev with \`git merge --no-ff <branch>\` (message "TASK: Merge <package>: <one-line what it adds>.", with ${NO_CO}), in dependency order (core before ui; INT is not among them). Resolve conflicts faithfully to each package's intent and the plan. Apply the contract change requests that are justified (others: list them for the INT package). Then from android/ run \`${GW} :app:testDebugUnitTest :app:lintDebug :app:verifyNoMaterial :app:verifyNoGoogleServices :app:assembleDebug :app:assembleRelease\` and fix integration breakages until green; for merged server/iOS/web changes run their checks too (server: fmt, clippy, tests against a throwaway Postgres with --nocapture and zero "skipping"; web: npm run build + selftests; iOS: build only — tests are the acceptance step's). Commit integration fixes on dev ("TASK: Integrate wave ${wave} …", ${NO_CO}). Afterwards remove the package worktrees under ${repo}/.claude/worktrees that belong to these branches (\`git worktree list\`, \`git worktree remove --force <path>\`; keep the branches). Never commit anything under .claude/. Do NOT push.
${missing.length ? `These packages did NOT report and may have partial work on their branches or worktrees: ${missing.join(', ')} — inspect their branches/worktrees (git worktree list), merge only coherent committed work and list what is missing.` : ''}

Package reports:\n${JSON.stringify(codeReports, null, 1)}`, {
  label: 'merge', phase: 'Merge', effort: 'high',
  schema: { type: 'object', properties: { headSha: { type: 'string' }, merged: { type: 'array', items: { type: 'string' } }, deferredCRs: { type: 'array', items: { type: 'string' } }, checks: { type: 'string' }, openIssues: { type: 'array', items: { type: 'string' } } }, required: ['headSha', 'merged', 'checks', 'openIssues'] },
})

phase('Integrate')
const integ = intId ? await agent(`${COMMON}

You implement the INTEGRATION package ${intId} directly on dev in the MAIN checkout ${repo} (no worktree; the wave's packages are already merged — merge report below). Follow its card in ${PLAN} §2 exactly: shared files it owns this wave (§2.6), wiring into AppContainer/di, the seams it must publish for the next wave with their final signatures (§1.7), docs it updates, its tests. Also apply these deferred contract change requests where justified: ${JSON.stringify(merge?.deferredCRs || [])}. Build/test from android/ with \`${GW} …\` (all unit tests, lint, both guard tasks, assembleDebug, assembleRelease) until green. ${COMMIT_RULES('dev')}

Merge report: ${JSON.stringify(merge)}`, {
  label: `int:${intId}`, phase: 'Integrate', effort: 'high',
  schema: { type: 'object', properties: { headSha: { type: 'string' }, summary: { type: 'string' }, seamsPublished: { type: 'array', items: { type: 'string' } }, testsGreen: { type: 'boolean' }, openIssues: { type: 'array', items: { type: 'string' } } }, required: ['headSha', 'summary', 'testsGreen', 'openIssues'] },
}) : null

phase('Accept')
const accept = await agent(`${COMMON}

You are the wave ${wave} ACCEPTANCE engineer in the MAIN checkout ${repo} (branch dev). Verify the wave exit gate and every package acceptance criterion that needs a device, a server or another client:
EXIT GATE: ${exitGate}
Package cards: ${packages.map(p => p.id).join(', ')}${intId ? ', ' + intId : ''} in ${PLAN} §2; verification strategy §6.

Tools: android/e2e/ (stack-up.sh / stack-down.sh start a throwaway Postgres + Redis + ntfy + cargo-built API on 127.0.0.1:8080; emulator-setup.sh; ui.py adb driver: dump / tap <text-or-desc> [n] / type / key / del / wait <text> / shot; launch.sh; enter_phrase.sh; read android/e2e/README or the scripts). AVDs: shroud_api37 (Android 17) and shroud_api30 (Android 11) — start with ~/Library/Android/sdk/emulator/emulator -avd <name> -no-audio -no-snapshot -no-boot-anim -port 5560 / 5562 (one at a time if RAM is tight), keep the screen on (adb shell svc power stayon true), PIN 1234 (locksettings set-pin 1234), Android 17 needs ACCESS_LOCAL_NETWORK (pm grant or tap Allow). Gotchas: adb input text needs shell quoting for '!'; after install force-stop + am start -W; the emulator is software-rendered (cold start several seconds). Install with \`${GW} :app:installDebug\` or adb install. iOS tests (if the wave changed iOS): xcodebuild test on a fresh simulator with parallel testing off, delete it afterwards. Web (if changed): run its selftests and build; for cross-client checks run the web dev server (cd web && npx vite --port 5173, it proxies /api to 127.0.0.1:8080) and drive it with the built-in browser tools if available. Test credentials are throwaway values you generate; never print them. Stop everything you started (stack-down.sh, adb emu kill) and leave the Pixel_10_Pro_XL emulator and anything else you did not start alone.

If something fails, diagnose it; fix it if the fix is small and clearly within the wave (commit "FIX: …" on dev with ${NO_CO}, rerun the unit tests via gw), otherwise report it precisely. Do NOT push.`, {
  label: 'accept', phase: 'Accept', effort: 'high',
  schema: { type: 'object', properties: { headSha: { type: 'string' }, checks: { type: 'array', items: { type: 'object', properties: { check: { type: 'string' }, where: { type: 'string' }, result: { type: 'string' } }, required: ['check', 'result'] } }, fixesApplied: { type: 'array', items: { type: 'string' } }, failures: { type: 'array', items: { type: 'string' } } }, required: ['headSha', 'checks', 'failures'] },
})

phase('Review')
const LENSES = [
  { key: 'correctness', prompt: 'correctness and robustness: bugs, wire formats vs the server/iOS/web (field names, nullability, encodings), crypto constants and byte layouts, lifecycle/threading/coroutine cancellation, crash paths, error handling, tests that cannot fail or retype vectors instead of copying them' },
  { key: 'security', prompt: 'security and privacy (docs/architecture.md invariants 1-13, plan §1.1 and the §1.5 storage table): plaintext or secrets on disk, in logs, saved state or backups; missing zeroing; Keystore/BiometricPrompt misuse; exported components/intent filters/providers; anything that contacts third parties or pulls Google services' },
  { key: 'parity', prompt: 'behavioural parity with iOS (the reference) and the web: compare the ported code with the iOS sources it cites — copy strings, rules, edge cases, timings, error mapping, state transitions; missing behaviours count' },
  { key: 'contracts', prompt: 'conformance to the plan: every item of each package card delivered (files, tests, acceptance), shared contracts and published seams exactly as §1.7 specifies (the next wave codes against them), ownership rules, nothing half-built that will conflict with later packages; for server/iOS/web changes: their own checks, tests and backwards compatibility with existing clients' },
]
const FINDINGS = { type: 'object', properties: { findings: { type: 'array', items: { type: 'object', properties: {
  title: { type: 'string' }, file: { type: 'string' }, line: { type: 'number' }, severity: { type: 'string', enum: ['blocker', 'major', 'minor'] },
  evidence: { type: 'string' }, fix: { type: 'string' } }, required: ['title', 'file', 'severity', 'evidence', 'fix'] } } }, required: ['findings'] }
const VERDICT = { type: 'object', properties: { real: { type: 'boolean' }, reason: { type: 'string' } }, required: ['real', 'reason'] }
const reviewed = await pipeline(LENSES,
  l => agent(`${COMMON}\n\nReview wave ${wave} of the Android port in ${repo}: \`git diff ${baseSha}..HEAD\` on dev (read whole files where needed, not just hunks). Lens: ${l.prompt}. Read-only: do not edit, commit or start long-running servers. Report only findings you verified in the code, each with file:line evidence and a concrete fix. No style nits.`, { label: `review:${l.key}`, phase: 'Review', schema: FINDINGS }),
  r => parallel((r?.findings || []).map(f => () =>
    agent(`Adversarially verify this code-review finding about ${repo} (branch dev; plan ${PLAN}). Try hard to REFUTE it by reading the actual code, tests, the iOS reference and the plan. Default to real=false if the evidence does not hold up.\nFinding: ${JSON.stringify(f)}`, { label: `verify:${(f.file || '').split('/').pop()}`, phase: 'Review', schema: VERDICT, effort: 'medium' })
      .then(v => (v && v.real ? f : null)))).then(xs => xs.filter(Boolean)))
const confirmed = reviewed.filter(Boolean).flat()
log(`${confirmed.length} confirmed findings`)

phase('Fix')
let fix = null
if (confirmed.length || (accept && accept.failures && accept.failures.length)) {
  fix = await agent(`${COMMON}\n\nFix wave ${wave} on dev in the MAIN checkout ${repo}. Apply each verified review finding (or explain precisely why not) and resolve the acceptance failures; add tests that would have caught each problem. From android/ run \`${GW} :app:testDebugUnitTest :app:lintDebug :app:verifyNoMaterial :app:verifyNoGoogleServices :app:assembleDebug :app:assembleRelease\` until green (and the server/web/iOS checks for changes there). Re-run the device checks a fix touches (android/e2e tools; emulators shroud_api37/shroud_api30; stop everything afterwards). One commit per logical fix ("FIX: …", ${NO_CO}). Do NOT push.\nFindings:\n${JSON.stringify(confirmed, null, 1)}\nAcceptance failures:\n${JSON.stringify(accept?.failures || [])}${args.fixNotes ? `\nNotes from the lead on these failures:\n${args.fixNotes}` : ''}`, {
    label: 'fix', phase: 'Fix', effort: 'high',
    schema: { type: 'object', properties: { fixed: { type: 'array', items: { type: 'string' } }, notFixed: { type: 'array', items: { type: 'string' } }, headSha: { type: 'string' }, testsGreen: { type: 'boolean' } }, required: ['fixed', 'notFixed', 'headSha', 'testsGreen'] },
  })
}
return { wave, reports, missing, merge, integ, accept, confirmed, fix }
