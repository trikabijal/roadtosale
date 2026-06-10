import SwiftUI
import DictationCore

struct ContentView: View {
    var body: some View {
        TabView {
            SetupTab()
                .tabItem { Label("Setup", systemImage: "keyboard") }

            StatsTab()
                .tabItem { Label("Stats", systemImage: "chart.bar") }
        }
    }
}

// MARK: - Setup tab

struct SetupTab: View {
    var body: some View {
        NavigationView {
            List {
                Section {
                    Text("Follow these steps to enable the Dictation keyboard on your iPhone or iPad.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .listRowBackground(Color.clear)
                }

                Section("Enable the keyboard") {
                    SetupStep(number: 1, text: "Open the **Settings** app")
                    SetupStep(number: 2, text: "Go to **General → Keyboard → Keyboards**")
                    SetupStep(number: 3, text: "Tap **Add New Keyboard…**")
                    SetupStep(number: 4, text: "Select **Dictation** from the list")
                    SetupStep(number: 5, text: "Tap **Dictation** in the keyboard list, then enable **Allow Full Access**")
                    SetupStep(number: 6, text: "In any app, tap the 🌐 globe key to switch to Dictation keyboard")
                }

                Section("Using the keyboard") {
                    Text("Tap the **mic button** to start recording, talk as long as you like (pauses are fine), then tap again to transcribe. The text is cleaned up on-device and inserted directly into any text field.")
                        .font(.callout)

                    Text("Tap the **✗** next to the last transcript to mark it as incorrect. This data feeds into WhisperKit calibration for Road to Sale.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Setup")
        }
    }
}

struct SetupStep: View {
    let number: Int
    let text: LocalizedStringKey

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Text("\(number)")
                .font(.headline)
                .foregroundStyle(.white)
                .frame(width: 28, height: 28)
                .background(Color.accentColor)
                .clipShape(Circle())
            Text(text)
                .font(.callout)
        }
        .padding(.vertical, 4)
    }
}

// MARK: - Stats tab

struct StatsTab: View {
    @State private var stats: WeeklyStats = .empty
    @State private var recentTranscripts: [TranscriptRecord] = []
    @State private var isLoading = true
    @State private var errorMessage: String?

    var body: some View {
        NavigationView {
            Group {
                if isLoading {
                    ProgressView("Loading…")
                } else if let err = errorMessage {
                    ContentUnavailableView(err, systemImage: "exclamationmark.triangle")
                } else {
                    List {
                        Section("This Week") {
                            StatRow(label: "Transcripts", value: "\(stats.totalCount)")
                            StatRow(label: "Avg confidence", value: "\(Int(stats.avgConfidence * 100))%")
                            StatRow(label: "Correction rate", value: "\(String(format: "%.1f", stats.correctionRate * 100))%")
                            StatRow(label: "Avg latency", value: "\(Int(stats.avgLatencyMs)) ms")
                            StatRow(label: "Avg audio length", value: "\(String(format: "%.1f", stats.avgAudioDurationMs / 1000)) s")
                        }

                        if !recentTranscripts.isEmpty {
                            Section("Recent Transcripts") {
                                ForEach(recentTranscripts) { record in
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(record.transcriptText)
                                            .font(.callout)
                                            .lineLimit(2)
                                        HStack {
                                            Text(record.recordedAt, style: .relative)
                                            Text("·")
                                            Text("\(Int(record.whisperkitConfidence * 100))% conf")
                                            if record.wasCorrected {
                                                Text("· corrected")
                                                    .foregroundStyle(.orange)
                                            }
                                        }
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                    }
                                    .padding(.vertical, 2)
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Telemetry")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Refresh") { Task { await loadData() } }
                }
            }
        }
        .task { await loadData() }
    }

    private func loadData() async {
        isLoading = true
        errorMessage = nil
        do {
            let url = try TelemetryStore.iOSDatabaseURL()
            let store = try TelemetryStore(databaseURL: url)
            stats = try await store.fetchWeeklyStats()
            recentTranscripts = try await store.fetchRecent(limit: 20)
        } catch {
            errorMessage = "Could not load telemetry: \(error.localizedDescription)"
        }
        isLoading = false
    }
}

struct StatRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack {
            Text(label)
            Spacer()
            Text(value).foregroundStyle(.secondary)
        }
    }
}
