import SwiftUI
import DictationCore

// MARK: - MenuBarView

struct MenuBarView: View {
    @EnvironmentObject var appState: AppState

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {

            // Status header
            statusHeader
                .padding(.horizontal, 16)
                .padding(.vertical, 12)

            // Activation-key warning (e.g. Fn leaking to the emoji picker) — persistent, actionable.
            if let warning = appState.hotkeyWarning {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                    Text(warning)
                        .font(.caption)
                        .foregroundStyle(.primary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 10)
            }

            Divider()

            // Recent transcripts
            if appState.recentTranscripts.isEmpty {
                Text("No transcripts yet")
                    .foregroundStyle(.secondary)
                    .font(.caption)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 20)
            } else {
                ForEach(Array(appState.recentTranscripts.enumerated()), id: \.element.id) { index, record in
                    // Re-transcribe acts on the most recent recording, so its control lives on the
                    // top (most-recent) row instead of a standalone menu button.
                    TranscriptRow(
                        record: record,
                        onReTranscribe: index == 0 ? { appState.reTranscribeLastRecording() } : nil
                    )
                    Divider()
                }
            }

            // Stats footer
            statsFooter
                .padding(.horizontal, 16)
                .padding(.vertical, 10)

            Divider()

            // Bottom action buttons
            HStack {
                Button("Settings") {
                    appState.showSettingsWindow()
                }
                .buttonStyle(.plain)
                .foregroundStyle(Color.accentColor)

                Button("History") {
                    appState.showHistoryWindow()
                }
                .buttonStyle(.plain)
                .foregroundStyle(Color.accentColor)

                Button("Setup") { appState.showOnboardingWindow() }
                    .buttonStyle(.plain)
                    .foregroundStyle(Color.accentColor)

                Spacer()

                Button("Quit") { NSApp.terminate(nil) }
                    .buttonStyle(.plain)
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
        }
        .frame(width: 340)
        // Global ⌘⇧Z correction shortcut — active while the popover is visible
        .background(
            KeyEventView { event in
                // Z = keyCode 6
                if event.modifierFlags.contains([.command, .shift]) && event.keyCode == 6 {
                    appState.markLastTranscriptCorrected()
                }
            }
        )
    }

    // MARK: - Sub-views

    private var statusHeader: some View {
        HStack(spacing: 10) {
            Circle()
                .fill(statusColor)
                .frame(width: 8, height: 8)
            Text(appState.statusMessage)
                .font(.subheadline)
                .foregroundStyle(.primary)
            Spacer()
            if !appState.engineLoaded {
                ProgressView().scaleEffect(0.7)
            }
        }
    }

    private var statusColor: Color {
        switch appState.dictationState {
        case .idle:         return appState.engineLoaded ? .green : .gray
        case .recording:    return .red
        case .transcribing: return .orange
        }
    }

    private var statsFooter: some View {
        let s = appState.weeklyStats
        return Group {
            if s.totalCount > 0 {
                Text(
                    "This week: \(s.totalCount) transcripts · " +
                    "Avg \(Int(s.avgConfidence * 100))% confidence · " +
                    "\(String(format: "%.0f", s.correctionRate * 100))% corrected"
                )
                .font(.caption2)
                .foregroundStyle(.secondary)
            } else {
                Text("No transcripts this week")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

// MARK: - TranscriptRow

struct TranscriptRow: View {
    let record: TranscriptRecord
    /// Set only on the most-recent row — re-runs transcription on the last recording.
    var onReTranscribe: (() -> Void)? = nil
    @State private var copied = false

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(record.transcriptText)
                    .lineLimit(2)
                    .font(.callout)
                    .foregroundStyle(record.wasCorrected ? .secondary : .primary)

                HStack(spacing: 6) {
                    Text(record.recordedAt, style: .time)
                        .font(.caption2)
                        .foregroundStyle(.tertiary)

                    if record.wasCorrected {
                        Text("✗ corrected")
                            .font(.caption2)
                            .foregroundStyle(.orange)
                    }

                    Text("\(Int(record.whisperkitConfidence * 100))% conf")
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }
            }

            Spacer()

            // Re-transcribe control — only on the most-recent row. Recovers a garbled result
            // without re-speaking, by re-running STT on the last saved recording.
            if let onReTranscribe {
                Button(action: onReTranscribe) {
                    Image(systemName: "arrow.clockwise")
                        .font(.caption)
                }
                .buttonStyle(.plain)
                .foregroundStyle(Color.secondary)
                .help("Re-transcribe your most recent recording — recovers a garbled result without re-speaking.")
            }

            // Per-row copy button — flips to a green checkmark on copy, then reverts so the
            // click clearly registered.
            Button {
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString(record.transcriptText, forType: .string)
                withAnimation(.easeInOut(duration: 0.15)) { copied = true }
                Task {
                    try? await Task.sleep(for: .seconds(1.5))
                    withAnimation(.easeInOut(duration: 0.15)) { copied = false }
                }
            } label: {
                Image(systemName: copied ? "checkmark" : "doc.on.doc")
                    .font(.caption)
            }
            .buttonStyle(.plain)
            .foregroundStyle(copied ? Color.green : Color.secondary)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }
}
