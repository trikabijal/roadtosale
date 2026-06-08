import SwiftUI
import DictationCore

/// Searchable dictation history. Copy a past transcript back to the clipboard to re-paste.
struct HistoryView: View {
    @EnvironmentObject var appState: AppState
    @State private var query = ""
    @State private var results: [TranscriptRecord] = []

    var body: some View {
        VStack(spacing: 0) {
            TextField("Search transcripts…", text: $query)
                .textFieldStyle(.roundedBorder)
                .padding(12)
                .onChange(of: query) { _, _ in Task { await reload() } }

            Divider()

            if results.isEmpty {
                Spacer()
                Text(query.isEmpty ? "No transcripts yet" : "No matches")
                    .foregroundStyle(.secondary)
                Spacer()
            } else {
                List(results) { record in
                    HistoryRow(record: record) { appState.copyToClipboard(record.transcriptText) }
                }
                .listStyle(.inset)
            }
        }
        .frame(width: 480, height: 540)
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
        HStack(alignment: .top, spacing: 8) {
            VStack(alignment: .leading, spacing: 3) {
                Text(record.transcriptText)
                    .lineLimit(4)
                    .textSelection(.enabled)
                HStack(spacing: 8) {
                    Text(record.recordedAt, format: .dateTime.month().day().hour().minute())
                        .font(.caption2).foregroundStyle(.tertiary)
                    if let app = record.frontmostApp {
                        Text(app).font(.caption2).foregroundStyle(.tertiary).lineLimit(1)
                    }
                    if record.wasCorrected {
                        Text("✗ corrected").font(.caption2).foregroundStyle(.orange)
                    }
                }
            }
            Spacer()
            Button {
                onCopy()
                copied = true
            } label: {
                Label(copied ? "Copied" : "Copy", systemImage: copied ? "checkmark" : "doc.on.doc")
                    .labelStyle(.titleAndIcon)
                    .font(.caption)
            }
            .buttonStyle(.borderless)
        }
        .padding(.vertical, 4)
    }
}
