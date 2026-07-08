import SwiftUI

/// The keyboard extension's full UI — status line, mic button, and the globe "next keyboard"
/// button the OS requires. The keyboard hands recording off to the container app (it has no mic
/// access), so there is no live level meter here. Compact layout for the standard 216 pt height.
struct KeyboardView: View {
    @ObservedObject var viewModel: KeyboardViewModel
    let onNextKeyboard: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            statusRow
                .padding(.bottom, 14)
            micRow
            Spacer()
            bottomBar
        }
        .frame(maxWidth: .infinity)
        .background(Color(.systemGroupedBackground))
    }

    // MARK: - Status area

    private var statusRow: some View {
        Text(viewModel.statusMessage)
            .font(.subheadline)
            .foregroundStyle(statusTextColor)
            .animation(.easeInOut(duration: 0.2), value: viewModel.statusMessage)
    }

    // MARK: - Mic row

    private var micRow: some View {
        micButton
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
