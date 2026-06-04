import SwiftUI
import DictationCore

struct KeyboardView: View {
    @ObservedObject var viewModel: KeyboardViewModel
    let onSwitchKeyboard: () -> Void

    var body: some View {
        VStack(spacing: 0) {

            // Top utility row
            utilityRow
                .frame(height: 44)
                .background(Color(.systemGray6))

            Divider()

            // Centre: mic button + status
            micSection
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color(.systemBackground))

            // Transcript preview strip
            transcriptStrip
                .frame(height: 44)
                .background(Color(.systemGray6))

            // Bottom: globe (keyboard switcher)
            bottomRow
                .frame(height: 44)
                .background(Color(.systemGray6))
        }
        .animation(.easeInOut(duration: 0.15), value: viewModel.state)
    }

    // MARK: - Sub-views

    private var utilityRow: some View {
        HStack(spacing: 0) {
            // Backspace
            Button {
                viewModel.deleteBackCallback?()
            } label: {
                Image(systemName: "delete.backward")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .buttonStyle(.plain)
            .foregroundStyle(.primary)

            Divider().frame(height: 28)

            // Space
            Button {
                viewModel.insertTextCallback?(" ")
            } label: {
                Text("space")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .buttonStyle(.plain)
            .foregroundStyle(.primary)

            Divider().frame(height: 28)

            // Return
            Button {
                viewModel.insertTextCallback?("\n")
            } label: {
                Image(systemName: "return")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .buttonStyle(.plain)
            .foregroundStyle(.primary)
        }
    }

    private var micSection: some View {
        VStack(spacing: 8) {
            if !viewModel.engineLoaded {
                ProgressView()
                Text(viewModel.statusMessage)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                Button {
                    viewModel.toggleRecording()
                } label: {
                    ZStack {
                        Circle()
                            .fill(micButtonColor)
                            .frame(width: 80, height: 80)
                            .scaleEffect(viewModel.state == .recording ? 1.1 : 1.0)
                            .animation(
                                viewModel.state == .recording
                                    ? .easeInOut(duration: 0.6).repeatForever(autoreverses: true)
                                    : .default,
                                value: viewModel.state
                            )

                        if viewModel.state == .transcribing {
                            ProgressView()
                                .tint(.white)
                        } else {
                            Image(systemName: micIconName)
                                .font(.system(size: 32))
                                .foregroundStyle(.white)
                        }
                    }
                }
                .buttonStyle(.plain)

                Text(viewModel.statusMessage)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var micButtonColor: Color {
        switch viewModel.state {
        case .idle: return .accentColor
        case .recording: return .red
        case .transcribing: return .orange
        }
    }

    private var micIconName: String {
        switch viewModel.state {
        case .idle: return "mic"
        case .recording: return "mic.fill"
        case .transcribing: return "waveform"
        }
    }

    private var transcriptStrip: some View {
        HStack {
            if viewModel.lastTranscript.isEmpty {
                Text("Transcript will appear here")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
                    .lineLimit(2)
            } else {
                Text(viewModel.lastTranscript)
                    .font(.caption)
                    .foregroundStyle(viewModel.lastWasCorrected ? .secondary : .primary)
                    .lineLimit(2)
                    .strikethrough(viewModel.lastWasCorrected)

                Spacer()

                // Tap to mark as corrected
                Button {
                    viewModel.markLastCorrected()
                } label: {
                    Image(systemName: viewModel.lastWasCorrected ? "xmark.circle.fill" : "xmark.circle")
                        .foregroundStyle(viewModel.lastWasCorrected ? AnyShapeStyle(.orange) : AnyShapeStyle(.tertiary))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 12)
    }

    private var bottomRow: some View {
        HStack {
            // Globe — keyboard switcher
            Button {
                onSwitchKeyboard()
            } label: {
                Image(systemName: "globe")
                    .frame(maxHeight: .infinity)
                    .padding(.horizontal, 16)
            }
            .buttonStyle(.plain)
            .foregroundStyle(.secondary)

            Spacer()
        }
    }
}
