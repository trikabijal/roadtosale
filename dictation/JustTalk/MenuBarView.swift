import SwiftUI
import DictationCore

// MARK: - MenuBarView

struct MenuBarView: View {
    @EnvironmentObject var appState: AppState

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {

            // Status header
            statusHeader
                .padding(.horizontal, Theme.Space.lg)
                .padding(.vertical, Theme.Space.md)

            // Activation-key warning (e.g. Fn leaking to the emoji picker) — persistent, actionable.
            if let warning = appState.hotkeyWarning {
                HStack(alignment: .top, spacing: Theme.Space.sm) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(Theme.Palette.warning)
                    Text(warning)
                        .font(.caption)
                        .foregroundStyle(Theme.Palette.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(Theme.Space.md)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    RoundedRectangle(cornerRadius: Theme.Radius.card)
                        .fill(Theme.Palette.warning.opacity(0.12))
                )
                .padding(.horizontal, Theme.Space.lg)
                .padding(.bottom, Theme.Space.sm)
            }

            divider

            // Recent transcripts
            if appState.recentTranscripts.isEmpty {
                Text("No transcripts yet")
                    .foregroundStyle(Theme.Palette.textSecondary)
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
                    divider
                }
            }

            // Stats footer
            statsFooter
                .padding(.horizontal, Theme.Space.lg)
                .padding(.vertical, Theme.Space.sm)

            divider

            // Bottom action buttons
            HStack(spacing: Theme.Space.md) {
                Button("Settings") {
                    appState.showSettingsWindow()
                }
                .buttonStyle(.plain)
                .foregroundStyle(Theme.Palette.accent)

                Button("History") {
                    appState.showHistoryWindow()
                }
                .buttonStyle(.plain)
                .foregroundStyle(Theme.Palette.accent)

                Button("Setup") { appState.showOnboardingWindow() }
                    .buttonStyle(.plain)
                    .foregroundStyle(Theme.Palette.accent)

                // Account / licensing. "Account" when unlocked, "Sign in" otherwise. The popover is
                // transient (rebuilt on open), so reading the state here reflects the latest verdict.
                Button(appState.licensing.state.isUnlocked ? "Account" : "Sign in") {
                    LicenseWindowController.shared.present(licensing: appState.licensing)
                }
                .buttonStyle(.plain)
                .foregroundStyle(Theme.Palette.accent)

                Spacer()

                Button("Quit") { NSApp.terminate(nil) }
                    .buttonStyle(.plain)
                    .foregroundStyle(Theme.Palette.textSecondary)
            }
            .padding(.horizontal, Theme.Space.lg)
            .padding(.vertical, Theme.Space.sm)
        }
        .frame(width: 320)
        .background(Theme.Palette.surface)
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

    /// Thin hairline divider using the theme stroke.
    private var divider: some View {
        Rectangle()
            .fill(Theme.Palette.stroke)
            .frame(height: 1)
    }

    private var statusHeader: some View {
        HStack(spacing: 10) {
            Circle()
                .fill(statusColor)
                .frame(width: 8, height: 8)
            Text(appState.statusMessage)
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(Theme.Palette.textPrimary)
            Spacer()
            if appState.availability == .warmingUp {
                ProgressView().scaleEffect(0.7)
            }
        }
    }

    private var statusColor: Color {
        switch appState.phase {
        case .idle:      return appState.availability == .ready ? Theme.Palette.success : Theme.Palette.textTertiary
        case .capturing: return Theme.Palette.recording
        case .finishing: return Theme.Palette.warning
        case .inserted:  return Theme.Palette.success
        case .failed:    return Theme.Palette.warning
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
                .font(Theme.Font.mono(11))
                .foregroundStyle(Theme.Palette.textSecondary)
            } else {
                Text("No transcripts this week")
                    .font(Theme.Font.mono(11))
                    .foregroundStyle(Theme.Palette.textSecondary)
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
        HStack(alignment: .top, spacing: Theme.Space.sm) {
            VStack(alignment: .leading, spacing: Theme.Space.xs) {
                Text(record.transcriptText)
                    .lineLimit(2)
                    .font(.callout)
                    .foregroundStyle(record.wasCorrected ? Theme.Palette.textSecondary : Theme.Palette.textPrimary)

                HStack(spacing: 6) {
                    // Meta line: time · app · confidence (e.g. "3:49 PM · iTerm2 · 94%").
                    Text(record.recordedAt, style: .time)

                    if let app = record.frontmostApp {
                        Text("·")
                        Text(app)
                    }

                    Text("·")
                    Text("\(Int(record.whisperkitConfidence * 100))%")

                    if record.wasCorrected {
                        Text("✗ corrected")
                            .foregroundStyle(Theme.Palette.warning)
                    }
                }
                .font(Theme.Font.mono(11))
                .foregroundStyle(Theme.Palette.textTertiary)
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
                .foregroundStyle(Theme.Palette.textSecondary)
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
            .foregroundStyle(copied ? Theme.Palette.success : Theme.Palette.textSecondary)
        }
        .padding(.horizontal, Theme.Space.lg)
        .padding(.vertical, Theme.Space.sm)
    }
}
