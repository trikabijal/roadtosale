import SwiftUI

/// The keyboard extension's full UI — mic button, level meter, status line, and the
/// globe "next keyboard" button the OS requires. Mirrors the macOS RecordingHUD design
/// in a compact layout that fits the standard keyboard height (216 pt).
struct KeyboardView: View {
    @ObservedObject var viewModel: KeyboardViewModel
    let onNextKeyboard: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            statusArea
                .padding(.bottom, 14)
            micRow
            Spacer()
            bottomBar
        }
        .frame(maxWidth: .infinity)
        .background(Color(.systemGroupedBackground))
    }

    // MARK: - Status area

    private var statusArea: some View {
        Group {
            if viewModel.showCorrectionPrompt {
                correctionRow
            } else {
                statusRow
            }
        }
        .animation(.easeInOut(duration: 0.2), value: viewModel.showCorrectionPrompt)
    }

    private var correctionRow: some View {
        HStack(spacing: 10) {
            Image(systemName: "checkmark.circle.fill")
                .foregroundStyle(.green)
            Text("Inserted")
                .font(.subheadline)
            Button("Mark wrong") {
                viewModel.markLastTranscriptCorrected()
            }
            .font(.caption)
            .buttonStyle(.bordered)
            .controlSize(.small)
            .tint(.orange)
        }
        .transition(.opacity.combined(with: .scale(scale: 0.95)))
    }

    private var statusRow: some View {
        HStack(spacing: 6) {
            if viewModel.state == .recording && viewModel.lowInput {
                Image(systemName: "mic.slash.fill")
                    .foregroundStyle(.orange)
                    .imageScale(.small)
                    .transition(.opacity)
            }
            Text(viewModel.statusMessage)
                .font(.subheadline)
                .foregroundStyle(statusTextColor)
                .animation(.easeInOut(duration: 0.2), value: viewModel.statusMessage)
        }
    }

    // MARK: - Mic row

    private var micRow: some View {
        HStack(spacing: 20) {
            if viewModel.state == .recording {
                LevelMeter(level: viewModel.micLevel)
                    .frame(width: 60, height: 24)
                    .transition(.opacity.combined(with: .scale(scale: 0.9)))
            }

            micButton

            // Mirror spacer so the mic button stays centred when the level meter appears.
            if viewModel.state == .recording {
                Color.clear.frame(width: 60, height: 24)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: viewModel.state)
    }

    private var micButton: some View {
        Button(action: viewModel.toggleRecording) {
            ZStack {
                Circle()
                    .fill(micButtonColor)
                    .frame(width: 64, height: 64)
                    .shadow(color: .black.opacity(0.12), radius: 4, x: 0, y: 2)
                micIcon
            }
        }
        .buttonStyle(.plain)
        .disabled(viewModel.state == .transcribing)
        .animation(.spring(response: 0.3, dampingFraction: 0.7), value: viewModel.state)
    }

    @ViewBuilder
    private var micIcon: some View {
        switch viewModel.state {
        case .idle:
            Image(systemName: "mic.fill")
                .font(.system(size: 24, weight: .semibold))
                .foregroundStyle(.primary)
        case .recording:
            Image(systemName: "stop.fill")
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(.white)
        case .transcribing:
            ProgressView()
                .scaleEffect(0.85)
                .tint(.secondary)
        }
    }

    // MARK: - Bottom bar

    /// Globe button is required by iOS for all third-party keyboards — lets the user
    /// cycle back to the system keyboard.
    private var bottomBar: some View {
        HStack {
            Button(action: onNextKeyboard) {
                Image(systemName: "globe")
                    .font(.system(size: 20))
                    .foregroundStyle(.secondary)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Spacer()
        }
        .padding(.horizontal, 8)
        .padding(.bottom, 8)
    }

    // MARK: - Helpers

    private var micButtonColor: Color {
        switch viewModel.state {
        case .idle:         return Color(.secondarySystemGroupedBackground)
        case .recording:    return .red
        case .transcribing: return Color(.secondarySystemGroupedBackground)
        }
    }

    private var statusTextColor: Color {
        switch viewModel.state {
        case .idle:         return .secondary
        case .recording:    return .red
        case .transcribing: return .orange
        }
    }
}

// MARK: - Level Meter

/// Seven bars whose heights track the live mic RMS. Same design as the macOS RecordingHUD.
private struct LevelMeter: View {
    let level: Float
    private let bars = 7

    var body: some View {
        HStack(spacing: 3) {
            ForEach(0..<bars, id: \.self) { i in
                Capsule()
                    .fill(Color.red.opacity(0.85))
                    .frame(height: barHeight(i))
            }
        }
    }

    private func barHeight(_ index: Int) -> CGFloat {
        let normalized = min(1, CGFloat(level) / 0.3)
        let center = Double(bars - 1) / 2
        let distance = abs(Double(index) - center) / center
        let weight = 1.0 - 0.6 * distance
        return max(4, 20 * normalized * weight)
    }
}
