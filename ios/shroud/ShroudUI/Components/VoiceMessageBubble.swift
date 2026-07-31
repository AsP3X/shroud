import AVFoundation
import SwiftUI

/// Voice message bubble with play/pause, duration, and optional on-device transcript.
struct VoiceMessageBubble: View {
    let message: MessagingController.ChatMessage
    let time: String
    var onAppearLoad: () -> Void = {}
    var onRequestTranscript: (() async -> String?)? = nil

    @State private var player: AVAudioPlayer?
    @State private var isPlaying = false
    @State private var progress: Double = 0
    @State private var tick: Timer?
    @State private var localTranscript: String?
    /// Drives the inline spinner while on-device transcription runs.
    @State private var isTranscribing = false

    private var isMine: Bool { message.isMine }
    private var durationMs: Int { message.voiceDurationMs ?? 0 }
    private var transcript: String? {
        localTranscript ?? message.transcript
    }

    var body: some View {
        HStack {
            if isMine { Spacer(minLength: 48) }

            VStack(alignment: isMine ? .trailing : .leading, spacing: 4) {
                HStack(spacing: 10) {
                    Button(action: togglePlay) {
                        Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                            // Play ⇄ pause morph rather than a hard glyph swap.
                            .contentTransition(.symbolEffect(.replace.downUp))
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(isMine ? Color.white : Theme.accent)
                            .frame(width: 36, height: 36)
                            .background(isMine ? Color.white.opacity(0.2) : Theme.accent.opacity(0.12))
                            .clipShape(Circle())
                    }
                    .pressable(scale: 0.88, dimming: 0)
                    .disabled(message.voiceData == nil && message.mediaObjectId != nil)
                    .animation(Motion.snappy, value: isPlaying)

                    VStack(alignment: .leading, spacing: 4) {
                        GeometryReader { geo in
                            ZStack(alignment: .leading) {
                                Capsule()
                                    .fill(isMine ? Color.white.opacity(0.25) : Theme.separator)
                                    .frame(height: 4)
                                Capsule()
                                    .fill(isMine ? Color.white : Theme.accent)
                                    .frame(width: max(4, geo.size.width * progress), height: 4)
                                    // Bridges the 50 ms polling interval into a continuous fill.
                                    .animation(.linear(duration: 0.05), value: progress)
                            }
                        }
                        .frame(height: 4)

                        Text(durationLabel)
                            .font(.system(size: 11, weight: .medium))
                            .foregroundStyle(isMine ? Color.white.opacity(0.85) : Theme.textSecondary)
                            .monospacedDigit()
                    }
                    .frame(minWidth: 120)

                    meta
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .background(isMine ? Theme.accent : Theme.bubbleIncoming)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))

                if let transcript, !transcript.isEmpty {
                    Text(transcript)
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.textSecondary)
                        .padding(.horizontal, 4)
                        .frame(maxWidth: 260, alignment: isMine ? .trailing : .leading)
                        // Transcription lands async — unfold it instead of snapping the row taller.
                        .transition(.opacity.combined(with: .move(edge: .top)))
                } else if onRequestTranscript != nil, message.voiceData != nil {
                    Button {
                        isTranscribing = true
                        Task {
                            let result = await onRequestTranscript?()
                            isTranscribing = false
                            withAnimation(Motion.standard) {
                                localTranscript = result
                            }
                        }
                    } label: {
                        HStack(spacing: 5) {
                            if isTranscribing {
                                ProgressView()
                                    .controlSize(.mini)
                                    .tint(Theme.accent)
                            }
                            Text(isTranscribing ? "Transcribing…" : "Transcribe on device")
                                .font(.system(size: 12, weight: .medium))
                                .contentTransition(.opacity)
                        }
                        .foregroundStyle(Theme.accent)
                        .padding(.horizontal, 4)
                        .padding(.vertical, 4)
                        .contentShape(Rectangle())
                    }
                    .pressable(scale: 0.94)
                    .disabled(isTranscribing)
                    .animation(Motion.snappy, value: isTranscribing)
                    .transition(.opacity)
                }
            }

            if !isMine { Spacer(minLength: 48) }
        }
        .animation(Motion.standard, value: transcript)
        .onAppear(perform: onAppearLoad)
        .onDisappear { stopPlayback() }
    }

    private var meta: some View {
        HStack(spacing: 4) {
            Text(time)
                .font(.system(size: 11))
                .foregroundStyle(isMine ? Color.white.opacity(0.8) : Theme.textSecondary)
            if isMine {
                MessageReceiptIcon(
                    receipt: message.receipt,
                    metaColor: Color.white.opacity(0.75),
                    readColor: Color.white,
                    failedColor: Color.white
                )
            }
        }
    }

    private var durationLabel: String {
        let total = max(durationMs, 1)
        let seconds = total / 1000
        return String(format: "%d:%02d", seconds / 60, seconds % 60)
    }

    private func togglePlay() {
        if isPlaying {
            stopPlayback()
            return
        }
        guard let data = message.voiceData else {
            onAppearLoad()
            return
        }
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
            try AVAudioSession.sharedInstance().setActive(true)
            let audioPlayer = try AVAudioPlayer(data: data)
            audioPlayer.prepareToPlay()
            audioPlayer.play()
            player = audioPlayer
            isPlaying = true
            progress = 0
            tick?.invalidate()
            tick = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { _ in
                Task { @MainActor in
                    guard let active = player else { return }
                    if !active.isPlaying {
                        stopPlayback()
                        return
                    }
                    progress = active.duration > 0 ? active.currentTime / active.duration : 0
                }
            }
        } catch {
            stopPlayback()
        }
    }

    private func stopPlayback() {
        tick?.invalidate()
        tick = nil
        player?.stop()
        player = nil
        isPlaying = false
        progress = 0
    }
}
