import AppKit
import SwiftUI
import DictationCore

// MARK: - HistoryWindow

/// Hosts the History view in a normal titled window, managed in AppKit (like OnboardingWindow).
/// Used so the menu-bar status-item popover can open History directly — `openWindow(id:)` does
/// not reach a view hosted in an NSPopover outside the SwiftUI scene graph.
@MainActor
final class HistoryWindow {
    private var window: NSWindow?

    func show(appState: AppState) {
        if window == nil {
            let w = NSWindow(
                contentRect: NSRect(x: 0, y: 0, width: 480, height: 540),
                styleMask: [.titled, .closable, .miniaturizable],
                backing: .buffered,
                defer: false
            )
            w.title = "Just Talk History"
            w.isReleasedWhenClosed = false
            w.center()
            w.contentView = NSHostingView(rootView: HistoryView().environmentObject(appState))
            window = w
        }
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
    }
}

/// Searchable dictation history. Copy a past transcript back to the clipboard to re-paste.
struct HistoryView: View {
    @EnvironmentObject var appState: AppState
    @State private var query = ""
    @State private var results: [TranscriptRecord] = []

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: Theme.Space.sm) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(Theme.Palette.textTertiary)
                TextField("Search transcripts…", text: $query)
                    .textFieldStyle(.plain)
                    .foregroundStyle(Theme.Palette.textPrimary)
            }
            .padding(.horizontal, Theme.Space.md)
            .padding(.vertical, Theme.Space.sm)
            .background(
                RoundedRectangle(cornerRadius: Theme.Radius.control)
                    .fill(Theme.Palette.surfaceInset)
            )
            .padding(Theme.Space.md)
            .onChange(of: query) { _, _ in Task { await reload() } }

            if results.isEmpty {
                Spacer()
                Text(query.isEmpty ? "No transcripts yet" : "No matches")
                    .foregroundStyle(Theme.Palette.textSecondary)
                Spacer()
            } else {
                List(results) { record in
                    HistoryRow(record: record) { appState.copyToClipboard(record.transcriptText) }
                        .listRowBackground(Theme.Palette.surface)
                        .listRowSeparatorTint(Theme.Palette.stroke)
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
        }
        .frame(width: 480, height: 540)
        .background(Theme.Palette.surface)
        .task { await reload() }
    }

    private func reload() async {
        results = await appState.searchHistory(matching: query)
    }
}

private struct HistoryRow: View {
    let record: TranscriptRecord
    let onCopy: () -> Void
    @State private var copied = false

    var body: some View {
        HStack(alignment: .top, spacing: Theme.Space.sm) {
            VStack(alignment: .leading, spacing: Theme.Space.xs) {
                Text(record.transcriptText)
                    .font(.callout)
                    .foregroundStyle(Theme.Palette.textPrimary)
                    .lineLimit(2)
                    .textSelection(.enabled)
                Text(metaLine)
                    .font(Theme.Font.mono(11))
                    .foregroundStyle(Theme.Palette.textTertiary)
                    .lineLimit(1)
            }
            Spacer()
            Button {
                onCopy()
                withAnimation(.easeInOut(duration: 0.15)) { copied = true }
                Task {
                    try? await Task.sleep(for: .seconds(1.5))
                    withAnimation(.easeInOut(duration: 0.15)) { copied = false }
                }
            } label: {
                Label(copied ? "Copied" : "Copy", systemImage: copied ? "checkmark" : "doc.on.doc")
                    .labelStyle(.titleAndIcon)
                    .font(.caption)
                    .foregroundStyle(copied ? Theme.Palette.success : Theme.Palette.textSecondary)
            }
            .buttonStyle(.borderless)
        }
        .padding(.vertical, Theme.Space.xs)
    }

    /// "4 Jul, 3:49 PM · iTerm2 · 94%" — date+time, app (if any), confidence %.
    private var metaLine: String {
        var parts: [String] = [
            record.recordedAt.formatted(.dateTime.day().month(.abbreviated).hour().minute())
        ]
        if let app = record.frontmostApp { parts.append(app) }
        parts.append("\(Int((record.whisperkitConfidence * 100).rounded()))%")
        return parts.joined(separator: " · ")
    }
}
