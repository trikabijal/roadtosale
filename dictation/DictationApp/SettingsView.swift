import SwiftUI
import DictationCore

// MARK: - SettingsView

struct SettingsView: View {
    @EnvironmentObject var appState: AppState

    var body: some View {
        Form {

            // MARK: Recording section
            Section("Recording") {
                HStack {
                    Text("Hotkey")
                    Spacer()
                    Text("Fn (Globe)")
                        .foregroundStyle(.secondary)
                }

                Picker("Activation", selection: Binding(
                    get: { appState.hotkeyMode },
                    set: { appState.setHotkeyMode($0) }
                )) {
                    ForEach(HotkeyMode.allCases, id: \.self) { mode in
                        Text(mode.displayName).tag(mode)
                    }
                }

                Toggle("Auto-paste after transcription", isOn: Binding(
                    get: { appState.autoPaste },
                    set: { appState.setAutoPaste($0) }
                ))

                Toggle("Play start/stop sounds", isOn: Binding(
                    get: { appState.soundEnabled },
                    set: { appState.setSoundEnabled($0) }
                ))
            }

            // MARK: Startup section
            Section("Startup") {
                Toggle("Launch at login", isOn: Binding(
                    get: { appState.launchAtLogin },
                    set: { appState.setLaunchAtLogin($0) }
                ))
            }

            // MARK: Speech-to-text section
            Section("Speech-to-text") {
                // Provider — the voice-understanding model, swappable behind a contract.
                Picker("Provider", selection: Binding(
                    get: { appState.sttConfig.provider },
                    set: { newProvider in
                        let model = newProvider == .whisperKit
                            ? (ModelTier(rawValue: appState.sttConfig.model)?.rawValue
                               ?? ModelTier.largeV3Turbo.rawValue)
                            : "default"
                        appState.setSTTConfig(STTConfig(provider: newProvider, model: model))
                    }
                )) {
                    ForEach(STTProvider.selectable, id: \.self) { provider in
                        Text(provider.isAvailable
                             ? provider.displayName
                             : "\(provider.displayName) — coming soon")
                            .tag(provider)
                    }
                }

                // Model — only WhisperKit exposes selectable tiers today.
                if appState.sttConfig.provider == .whisperKit {
                    Picker("Model", selection: Binding(
                        get: { ModelTier(rawValue: appState.sttConfig.model) ?? .largeV3Turbo },
                        set: { tier in
                            appState.setSTTConfig(STTConfig(provider: .whisperKit, model: tier.rawValue))
                        }
                    )) {
                        ForEach(ModelTier.allCases, id: \.self) { tier in
                            Text(tier.displayName).tag(tier)
                        }
                    }
                    .pickerStyle(.radioGroup)
                }

                if !appState.engineLoaded {
                    HStack(spacing: 6) {
                        ProgressView().scaleEffect(0.7)
                        Text(appState.statusMessage)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }

            // MARK: AI cleanup section
            Section("AI Cleanup") {
                // Level — how aggressively to rewrite dictated speech.
                Picker("Cleanup", selection: Binding(
                    get: { appState.cleanupConfig.level },
                    set: { level in
                        appState.setCleanupConfig(
                            CleanupConfig(provider: appState.cleanupConfig.provider, level: level))
                    }
                )) {
                    ForEach(CleanupLevel.allCases, id: \.self) { level in
                        Text(level.displayName).tag(level)
                    }
                }
                .pickerStyle(.radioGroup)

                // Provider — the cleanup model, swappable behind a contract.
                Picker("Engine", selection: Binding(
                    get: { appState.cleanupConfig.provider },
                    set: { provider in
                        appState.setCleanupConfig(
                            CleanupConfig(provider: provider, level: appState.cleanupConfig.level))
                    }
                )) {
                    ForEach(CleanupProvider.allCases, id: \.self) { provider in
                        Text(provider.isAvailable
                             ? provider.displayName
                             : "\(provider.displayName) — unavailable")
                            .tag(provider)
                    }
                }

                Text("On-device only. Nothing you say or type leaves this Mac.")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            // MARK: Per-app cleanup section
            Section("Per-App Cleanup") {
                AppProfilesEditor()
                Text("Override the cleanup level for specific apps — e.g. Off in your terminal or code editor.")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            // MARK: Custom vocabulary section
            Section("Custom Vocabulary") {
                VocabularyEditor()
                Text("Names and jargon — biases transcription and forces spelling after cleanup.")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            // MARK: Weekly stats section
            Section("This Week") {
                let s = appState.weeklyStats
                LabeledContent("Transcripts", value: "\(s.totalCount)")
                LabeledContent("Avg confidence", value: "\(Int(s.avgConfidence * 100))%")
                LabeledContent("Correction rate",
                               value: "\(String(format: "%.1f", s.correctionRate * 100))%")
                LabeledContent("Avg latency", value: "\(Int(s.avgLatencyMs)) ms")
                LabeledContent("Avg audio length",
                               value: "\(String(format: "%.1f", s.avgAudioDurationMs / 1000.0)) s")
            }
        }
        .formStyle(.grouped)
        .frame(width: 420, height: 560)
        .padding()
        .navigationTitle("Dictation Settings")
        .onAppear { Task { await appState.refreshStats() } }
        // ⌘⇧Z correction shortcut — active while the Settings window is key
        .background(
            KeyEventView { event in
                if event.modifierFlags.contains([.command, .shift]) && event.keyCode == 6 {
                    appState.markLastTranscriptCorrected()
                }
            }
        )
    }
}

// MARK: - AppProfilesEditor

/// Map specific apps to a cleanup level override. New overrides default to Off
/// (the common case: don't rewrite commands/code in a terminal or editor).
struct AppProfilesEditor: View {
    @EnvironmentObject var appState: AppState

    private var runningApps: [NSRunningApplication] {
        NSWorkspace.shared.runningApplications
            .filter { $0.activationPolicy == .regular && $0.bundleIdentifier != nil }
            .sorted { ($0.localizedName ?? "") < ($1.localizedName ?? "") }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Menu("Add app…") {
                ForEach(runningApps, id: \.bundleIdentifier) { app in
                    Button(app.localizedName ?? app.bundleIdentifier ?? "App") {
                        appState.setAppProfile(
                            bundleId: app.bundleIdentifier ?? "",
                            name: app.localizedName ?? app.bundleIdentifier ?? "App",
                            level: .off
                        )
                    }
                }
            }
            .fixedSize()

            if appState.appProfiles.isEmpty {
                Text("No per-app overrides")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(appState.appProfiles) { profile in
                    HStack {
                        Text(profile.name).lineLimit(1)
                        Spacer()
                        Picker("", selection: Binding(
                            get: { profile.level },
                            set: { appState.setAppProfile(bundleId: profile.bundleId, name: profile.name, level: $0) }
                        )) {
                            ForEach(CleanupLevel.allCases, id: \.self) { level in
                                Text(level.displayName).tag(level)
                            }
                        }
                        .labelsHidden()
                        .frame(width: 150)

                        Button {
                            appState.removeAppProfile(bundleId: profile.bundleId)
                        } label: {
                            Image(systemName: "minus.circle.fill").foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }
}

// MARK: - VocabularyEditor

/// Add/remove custom vocabulary terms. Persists through `AppState.setVocabulary`.
struct VocabularyEditor: View {
    @EnvironmentObject var appState: AppState
    @State private var newTerm: String = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                TextField("Add a name or term…", text: $newTerm)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit(add)
                Button("Add", action: add)
                    .disabled(newTerm.trimmingCharacters(in: .whitespaces).isEmpty)
            }

            if appState.vocabulary.isEmpty {
                Text("No custom terms yet")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(appState.vocabulary, id: \.self) { term in
                    HStack {
                        Text(term)
                        Spacer()
                        Button {
                            remove(term)
                        } label: {
                            Image(systemName: "minus.circle.fill")
                                .foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }

    private func add() {
        let trimmed = newTerm.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !appState.vocabulary.contains(trimmed) else { return }
        appState.setVocabulary(appState.vocabulary + [trimmed])
        newTerm = ""
    }

    private func remove(_ term: String) {
        appState.setVocabulary(appState.vocabulary.filter { $0 != term })
    }
}

// MARK: - KeyEventView

/// An `NSViewRepresentable` that installs a local key-down monitor.
/// Used to wire ⌘⇧Z correction in both the Settings window and the MenuBar popover.
struct KeyEventView: NSViewRepresentable {
    let handler: (NSEvent) -> Void

    func makeNSView(context: Context) -> KeyCaptureNSView {
        KeyCaptureNSView(handler: handler)
    }

    func updateNSView(_ nsView: KeyCaptureNSView, context: Context) {}
}

// MARK: - KeyCaptureNSView

final class KeyCaptureNSView: NSView {
    let handler: (NSEvent) -> Void
    private var monitor: Any?

    init(handler: @escaping (NSEvent) -> Void) {
        self.handler = handler
        super.init(frame: .zero)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) not used") }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        if window != nil {
            monitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
                self?.handler(event)
                return event
            }
        } else {
            // View removed from window — tear down the monitor
            if let m = monitor {
                NSEvent.removeMonitor(m)
                monitor = nil
            }
        }
    }

    deinit {
        if let m = monitor { NSEvent.removeMonitor(m) }
    }
}
