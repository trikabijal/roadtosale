import SwiftUI
import DictationCoreBase

// Uses the shared `JTBrand` palette defined in OnboardingFlow.swift (gold accent on warm paper).

struct ContentView: View {
    var body: some View {
        TabView {
            HomeTab()
                .tabItem { Label("Home", systemImage: "waveform") }
            SetupTab()
                .tabItem { Label("Setup", systemImage: "keyboard") }
        }
        .tint(JTBrand.gold)
    }
}

// MARK: - Home tab (telemetry + history)

struct HomeTab: View {
    @State private var week: WeeklyStats = .empty
    @State private var totals: UsageTotals = .empty
    @State private var recent: [TranscriptRecord] = []
    @State private var isLoading = true
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 22) {
                    if let err = errorMessage {
                        ContentUnavailableView(err, systemImage: "exclamationmark.triangle")
                            .padding(.top, 40)
                    } else if isLoading {
                        ProgressView().controlSize(.large).padding(.top, 60)
                    } else if totals.totalCount == 0 {
                        EmptyHome()
                    } else {
                        HeroCard(totals: totals)
                        WeekSection(week: week)
                        HistorySection(records: recent)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 32)
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Just Talk")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { Task { await load() } } label: { Image(systemName: "arrow.clockwise") }
                }
            }
        }
        .task { await load() }
        .refreshable { await load() }
    }

    private func load() async {
        errorMessage = nil
        do {
            let store = try TelemetryStore(databaseURL: try TelemetryStore.iOSDatabaseURL())
            async let w = store.fetchWeeklyStats()
            async let t = store.fetchUsageTotals()
            async let r = store.fetchRecent(limit: 50)
            (week, totals, recent) = try await (w, t, r)
        } catch {
            errorMessage = "Couldn't load your stats: \(error.localizedDescription)"
        }
        isLoading = false
    }
}

// MARK: - Hero

private struct HeroCard: View {
    let totals: UsageTotals

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 8) {
                Image(systemName: "waveform")
                Text("You've dictated").font(.subheadline.weight(.semibold))
            }
            .foregroundStyle(.white.opacity(0.9))

            Text(totals.totalWords.formatted())
                .font(.system(size: 52, weight: .bold, design: .rounded))
                .foregroundStyle(.white)
                .contentTransition(.numericText())
            Text(totals.totalWords == 1 ? "word" : "words")
                .font(.headline).foregroundStyle(.white.opacity(0.85))
                .offset(y: -12)

            HStack(spacing: 20) {
                HeroStat(value: savedText, caption: "saved vs typing")
                HeroStat(value: totals.totalCount.formatted(), caption: totals.totalCount == 1 ? "session" : "sessions")
                HeroStat(value: talkText, caption: "talking")
            }
            .padding(.top, 2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(20)
        .background(
            LinearGradient(colors: [JTBrand.gold, JTBrand.goldDeep],
                           startPoint: .topLeading, endPoint: .bottomTrailing),
            in: RoundedRectangle(cornerRadius: 22, style: .continuous)
        )
        .shadow(color: JTBrand.gold.opacity(0.25), radius: 12, y: 6)
    }

    private var savedText: String { durationText(minutes: totals.typingMinutesSaved) }
    private var talkText: String { durationText(minutes: totals.totalMinutes) }
}

private struct HeroStat: View {
    let value: String
    let caption: String
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value).font(.title3.weight(.bold)).foregroundStyle(.white)
                .monospacedDigit()
            Text(caption).font(.caption2).foregroundStyle(.white.opacity(0.8))
        }
    }
}

// MARK: - This week

private struct WeekSection: View {
    let week: WeeklyStats

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader("This week")
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)],
                      spacing: 12) {
                MetricCard(icon: "text.bubble.fill", value: week.totalCount.formatted(),
                           label: "Transcripts", tint: JTBrand.gold)
                MetricCard(icon: "checkmark.seal.fill", value: "\(Int((week.avgConfidence * 100).rounded()))%",
                           label: "Avg accuracy", tint: .green)
                MetricCard(icon: "bolt.fill", value: "\(Int(week.avgLatencyMs.rounded())) ms",
                           label: "Avg speed", tint: .blue)
                MetricCard(icon: "clock.fill", value: durationText(minutes: week.totalAudioMs / 60_000),
                           label: "Talk time", tint: .purple)
            }
        }
    }
}

private struct MetricCard: View {
    let icon: String
    let value: String
    let label: String
    let tint: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Image(systemName: icon).font(.system(size: 17, weight: .semibold)).foregroundStyle(tint)
            Text(value).font(.title3.weight(.bold)).monospacedDigit()
                .foregroundStyle(.primary).minimumScaleFactor(0.7).lineLimit(1)
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Color(.secondarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

// MARK: - History

private struct HistorySection: View {
    let records: [TranscriptRecord]

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader("History")
            if records.isEmpty {
                Text("Your recent transcriptions will show up here.")
                    .font(.callout).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 8)
            } else {
                VStack(spacing: 10) {
                    ForEach(records) { HistoryCell(record: $0) }
                }
            }
        }
    }
}

private struct HistoryCell: View {
    let record: TranscriptRecord

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(record.transcriptText)
                .font(.callout)
                .foregroundStyle(.primary)
                .lineLimit(4)
                .frame(maxWidth: .infinity, alignment: .leading)
            HStack(spacing: 8) {
                Label(record.recordedAt.formatted(.relative(presentation: .named)), systemImage: "clock")
                Text("·")
                Text("\(record.wordCount) words")
                if record.wasCorrected {
                    Text("·"); Label("corrected", systemImage: "pencil").foregroundStyle(.orange)
                }
                Spacer()
                Button {
                    UIPasteboard.general.string = record.transcriptText
                } label: { Image(systemName: "doc.on.doc") }
                    .buttonStyle(.plain).foregroundStyle(JTBrand.gold)
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
        .padding(14)
        .background(Color(.secondarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

// MARK: - Empty state

private struct EmptyHome: View {
    var body: some View {
        VStack(spacing: 16) {
            ZStack {
                Circle().fill(JTBrand.gold.opacity(0.15)).frame(width: 96, height: 96)
                Image(systemName: "waveform").font(.system(size: 40, weight: .semibold))
                    .foregroundStyle(JTBrand.gold)
            }
            .padding(.top, 60)
            Text("No dictations yet").font(.title2.bold())
            Text("Switch to the Just Talk keyboard in any app and tap the mic. Your words, time saved, and history will appear here.")
                .font(.callout).foregroundStyle(.secondary)
                .multilineTextAlignment(.center).padding(.horizontal, 24)
        }
        .frame(maxWidth: .infinity)
    }
}

// MARK: - Shared bits

private struct SectionHeader: View {
    let title: String
    init(_ t: String) { title = t }
    var body: some View {
        Text(title).font(.headline).frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// "5h 8m" / "42m" / "18s" from a minutes value.
private func durationText(minutes: Double) -> String {
    let totalSeconds = Int((minutes * 60).rounded())
    if totalSeconds < 60 { return "\(totalSeconds)s" }
    let h = totalSeconds / 3600
    let m = (totalSeconds % 3600) / 60
    if h > 0 { return m > 0 ? "\(h)h \(m)m" : "\(h)h" }
    return "\(m)m"
}

// MARK: - Setup tab

struct SetupTab: View {
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("Follow these steps to enable the Just Talk keyboard on your iPhone or iPad.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .listRowBackground(Color.clear)
                }

                Section("Enable the keyboard") {
                    SetupStep(number: 1, text: "Open the **Settings** app")
                    SetupStep(number: 2, text: "Go to **General → Keyboard → Keyboards**")
                    SetupStep(number: 3, text: "Tap **Add New Keyboard…**")
                    SetupStep(number: 4, text: "Select **Just Talk** from the list")
                    SetupStep(number: 5, text: "Tap **Just Talk** in the keyboard list, then enable **Allow Full Access**")
                    SetupStep(number: 6, text: "In any app, tap the 🌐 globe key to switch to Just Talk")
                }

                Section("Using the keyboard") {
                    Text("Tap the **mic** to start, talk as long as you like (pauses are fine), then tap again to finish. The text is cleaned up on-device and inserted straight into any text field.")
                        .font(.callout)
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
                .background(JTBrand.gold)
                .clipShape(Circle())
            Text(text)
                .font(.callout)
        }
        .padding(.vertical, 4)
    }
}
