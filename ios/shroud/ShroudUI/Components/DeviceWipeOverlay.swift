import SwiftUI

/// Full-screen "Clearing this iPhone" shown while `DeviceWipeController` runs the logout wipe.
///
/// Human: Covers everything — including the switch from the tab shell to Welcome underneath it
/// — so the last thing the user sees of their account is each piece of it being removed. The
/// ring fills as steps finish, dots drift out of the emblem while data is deleted, and each row
/// ticks off with what it removed. Done turns the ring green; the overlay then fades to Welcome.
///
/// Agent: READS DeviceWipeController; CALLS retry / continueAfterFailure. No other side effects.
struct DeviceWipeOverlay: View {
    @Environment(DeviceWipeController.self) private var wipe
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private typealias Step = DeviceDataWipe.Step

    /// The phase this overlay draws: the controller's, except that it keeps the last one while
    /// the overlay fades out.
    ///
    /// Human: The controller is back at `.idle` before the fade ends, and idle used to draw as
    /// "running". That restarted the emblem's repeating breathe and particles inside the removal
    /// transition, which then never finished: the invisible overlay stayed above Welcome and
    /// took every touch until the app was restarted.
    private var phase: DeviceWipeController.Phase {
        wipe.phase == .idle ? lastShownPhase : wipe.phase
    }

    /// The controller's last phase before `.idle`. Only read while fading out: everywhere else
    /// the overlay follows the controller directly, inside the controller's own animation.
    @State private var lastShownPhase: DeviceWipeController.Phase = .running

    private var device: String {
        UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone"
    }

    var body: some View {
        // Scrolls when it doesn't fit (an SE, landscape, a long failed subtitle); the footer
        // stays pinned so Try Again and Continue are always on screen.
        ScrollView {
            VStack(spacing: 28) {
                WipeEmblem(state: emblemState, progress: progress, device: device)
                heading
                steps
            }
            .padding(.horizontal, 20)
            .padding(.top, 44)
            .padding(.bottom, 24)
            .frame(maxWidth: 520)
            .frame(maxWidth: .infinity)
        }
        .scrollBounceBehavior(.basedOnSize)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            footer
                .animation(Motion.respecting(reduceMotion, Motion.standard), value: phase)
                .padding(.horizontal, 20)
                .padding(.bottom, 24)
                .frame(maxWidth: 520)
                .frame(maxWidth: .infinity)
                // Rows scrolled under the pinned buttons don't show through.
                .background(Theme.backgroundGrouped.ignoresSafeArea(edges: .bottom))
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.backgroundGrouped.ignoresSafeArea())
        // A leaving overlay never takes a touch meant for the screen it uncovers.
        .allowsHitTesting(wipe.isPresented)
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isModal)
        .accessibilityHidden(!wipe.isPresented)
        .onChange(of: wipe.phase, initial: true) { _, new in
            if new != .idle { lastShownPhase = new }
        }
    }

    // MARK: - Heading

    private var heading: some View {
        VStack(spacing: 8) {
            Text(title)
                .font(.system(size: 26, weight: .bold))
                .foregroundStyle(Theme.textPrimary)
                .contentTransition(.opacity)
                .accessibilityAddTraits(.isHeader)
            Text(subtitle)
                .font(.system(size: 15))
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
                .lineSpacing(2)
                .contentTransition(.opacity)
                .fixedSize(horizontal: false, vertical: true)
        }
        .animation(Motion.standard, value: phase)
    }

    private var title: String {
        switch phase {
        case .failed: "Couldn’t clear everything"
        case .done: "This \(device) is clear"
        default: "Clearing this \(device)"
        }
    }

    private var subtitle: String {
        switch phase {
        case .failed:
            return "Still here: \(DeviceWipeController.labels(of: wipe.leftovers)). Try again — if it keeps failing, restart your \(device) and open Shroud; it finishes on its own."
        case .done:
            return wipe.handle.isEmpty
                ? "Nothing from your account is left on this \(device)."
                : "Nothing from \(wipe.handle) is left on this device."
        default:
            // Why: nothing for Log Out, "Your session ended. ", "This iPhone was removed from
            // your account. ", or "This account was deleted. ". A removal or an ended session
            // turns into an account deletion once the server's answer says so.
            let lead = DeviceWipeController.lead(for: wipe.reason, device: device)
            let whose = wipe.handle.isEmpty ? "on this \(device)" : "for \(wipe.handle)"
            return "\(lead)Removing everything Shroud stored \(whose)."
        }
    }

    // MARK: - Steps

    private var steps: some View {
        VStack(spacing: 0) {
            ForEach(Array(Step.allCases.enumerated()), id: \.element) { index, step in
                if index > 0 {
                    Rectangle()
                        .fill(Theme.separator)
                        .frame(height: 1)
                }
                row(step)
            }
        }
        .padding(.horizontal, 16)
        .background(Theme.background)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private func row(_ step: Step) -> some View {
        let state = rowState(step)
        let detail = state == .done ? wipe.details[step] : state == .failed && step != .verify ? "Still here" : nil
        return HStack(spacing: 12) {
            WipeStepStatus(state: state, reduceMotion: reduceMotion)
            Text(DeviceWipeController.title(for: step))
                .font(.system(size: 16, weight: state == .pending ? .regular : .medium))
                .foregroundStyle(state == .pending ? Theme.textSecondary : Theme.textPrimary)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let detail {
                Text(detail)
                    .font(.system(size: 14))
                    .foregroundStyle(Theme.textSecondary)
                    .lineLimit(1)
                    // The detail keeps its full width ("Ended here · server offline"); the
                    // short title gives way and may wrap.
                    .layoutPriority(1)
                    .transition(.opacity)
            }
        }
        .frame(height: 50)
        .animation(Motion.snappy, value: state)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(DeviceWipeController.title(for: step))
        .accessibilityValue(accessibilityValue(state: state, detail: detail))
    }

    private func rowState(_ step: Step) -> WipeStepStatus.State {
        if phase == .failed, step == .verify || wipe.leftovers.contains(where: { $0.step == step }) {
            return .failed
        }
        if wipe.active == step || wipe.retrying.contains(step) { return .active }
        return wipe.details[step] != nil ? .done : .pending
    }

    private func accessibilityValue(state: WipeStepStatus.State, detail: String?) -> String {
        switch state {
        case .pending: "Waiting"
        case .active: "In progress"
        case .done, .failed: detail ?? (state == .failed ? "Failed" : "Done")
        }
    }

    private var progress: Double {
        if phase == .done { return 1 }
        let done = Step.allCases.filter { rowState($0) == .done }.count
        return Double(done) / Double(Step.allCases.count)
    }

    private var emblemState: WipeEmblem.State {
        switch phase {
        case .done: .done
        case .failed: .failed
        default: .running
        }
    }

    // MARK: - Footer

    @ViewBuilder
    private var footer: some View {
        if phase == .failed {
            VStack(spacing: 10) {
                PrimaryButton(title: "Try Again", showsArrow: false) { wipe.retry() }
                SecondaryButton(title: "Continue") { wipe.continueAfterFailure() }
            }
            .transition(reduceMotion ? AnyTransition.opacity : .opacity.combined(with: .move(edge: .bottom)))
        } else if phase == .done || DeviceWipeController.showsOtherDevicesFootnote(reason: wipe.reason) {
            VStack(spacing: 6) {
                if phase == .done {
                    ProgressView()
                        .controlSize(.small)
                } else {
                    Image(systemName: "checkmark.shield")
                        .font(.system(size: 15))
                        .accessibilityHidden(true)
                }
                Text(
                    phase == .done
                        ? "Taking you to the welcome screen…"
                        : "Your account and chats on other devices stay as they are."
                )
                .font(.system(size: 13))
                .multilineTextAlignment(.center)
            }
            .foregroundStyle(Theme.textSecondary)
            .transition(.opacity)
        }
    }
}

// MARK: - Emblem

/// The device in a ring that fills as the wipe goes; dots drift out of it while data is removed.
private struct WipeEmblem: View {
    enum State: Equatable {
        case running, done, failed
    }

    let state: State
    let progress: Double
    let device: String

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var tint: Color {
        switch state {
        case .running: Theme.accent
        case .done: Theme.online
        case .failed: Theme.danger
        }
    }

    private var symbol: String {
        switch state {
        case .running: device == "iPad" ? "ipad" : "iphone"
        case .done: "checkmark"
        case .failed: "exclamationmark.triangle.fill"
        }
    }

    var body: some View {
        ZStack {
            if state == .running && !reduceMotion {
                WipeParticles()
            }
            Circle()
                .stroke(Theme.separator, lineWidth: 4)
            Circle()
                .trim(from: 0, to: progress)
                .stroke(tint, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .opacity(progress > 0 ? 1 : 0)
                .animation(reduceMotion ? Motion.reduced : Motion.gentle, value: progress)
            Circle()
                .fill(state == .running ? Theme.accentSoft : tint.opacity(0.14))
                .frame(width: 88, height: 88)
            Image(systemName: symbol)
                .font(.system(size: state == .running ? 38 : 36, weight: state == .done ? .bold : .regular))
                .foregroundStyle(tint)
                .contentTransition(.symbolEffect(.replace))
                .symbolEffect(.breathe, options: .repeating, isActive: state == .running && !reduceMotion)
        }
        .frame(width: 128, height: 128)
        .animation(Motion.standard, value: state)
        .accessibilityHidden(true)
    }
}

/// Six dots leaving the emblem on a loop, staggered so there is always one in flight.
private struct WipeParticles: View {
    private struct Particle {
        let dx: CGFloat
        let dy: CGFloat
        let size: CGFloat
        let delay: Double
    }

    private static let particles = [
        Particle(dx: 62, dy: -40, size: 8, delay: 0),
        Particle(dx: 72, dy: 12, size: 5, delay: 0.5),
        Particle(dx: -64, dy: -30, size: 6, delay: 0.25),
        Particle(dx: -70, dy: 24, size: 5, delay: 0.9),
        Particle(dx: 40, dy: 60, size: 6, delay: 1.2),
        Particle(dx: -18, dy: -70, size: 4, delay: 0.7),
    ]
    private static let period = 1.8

    var body: some View {
        TimelineView(.animation) { context in
            let now = context.date.timeIntervalSinceReferenceDate
            ZStack {
                ForEach(Self.particles.indices, id: \.self) { index in
                    let particle = Self.particles[index]
                    let phase = ((now + Self.period - particle.delay).truncatingRemainder(dividingBy: Self.period)) / Self.period
                    let eased = 1 - pow(1 - phase, 2)
                    Circle()
                        .fill(Theme.accent)
                        .frame(width: particle.size, height: particle.size)
                        .scaleEffect(1 - 0.65 * eased)
                        .offset(x: particle.dx * eased, y: particle.dy * eased)
                        .opacity(phase < 0.2 ? phase / 0.2 * 0.9 : 0.9 * (1 - (phase - 0.2) / 0.8))
                }
            }
        }
        .allowsHitTesting(false)
    }
}

// MARK: - Step status

/// Hollow while waiting, a spinning arc while running, a filled check when done.
private struct WipeStepStatus: View {
    enum State: Equatable {
        case pending, active, done, failed
    }

    let state: State
    let reduceMotion: Bool

    var body: some View {
        ZStack {
            switch state {
            case .pending:
                Circle()
                    .strokeBorder(Theme.separator, lineWidth: 1.5)
            case .active:
                WipeSpinner(reduceMotion: reduceMotion)
            case .done:
                Circle()
                    .fill(Theme.accent)
                    .overlay {
                        Image(systemName: "checkmark")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundStyle(.white)
                    }
                    .transition(.scale(scale: 0.5).combined(with: .opacity))
            case .failed:
                Circle()
                    .fill(Theme.danger.opacity(0.14))
                    .overlay {
                        Image(systemName: "exclamationmark")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(Theme.danger)
                    }
                    .transition(.scale(scale: 0.5).combined(with: .opacity))
            }
        }
        .frame(width: 24, height: 24)
    }
}

private struct WipeSpinner: View {
    let reduceMotion: Bool

    var body: some View {
        TimelineView(.animation(paused: reduceMotion)) { context in
            let turns = context.date.timeIntervalSinceReferenceDate / 0.8
            ZStack {
                Circle()
                    .stroke(Theme.separator, lineWidth: 2.2)
                Circle()
                    .trim(from: 0, to: 0.28)
                    .stroke(Theme.accent, style: StrokeStyle(lineWidth: 2.2, lineCap: .round))
                    .rotationEffect(.degrees(turns.truncatingRemainder(dividingBy: 1) * 360))
            }
            .padding(1.1)
        }
    }
}

#Preview {
    DeviceWipeOverlay()
        .environment(DeviceWipeController())
}
