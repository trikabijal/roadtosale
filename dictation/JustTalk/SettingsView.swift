import AppKit
import SwiftUI
import DictationCore

// MARK: - SettingsWindow

/// Hosts Settings in a normal titled AppKit window, managed exactly like `HistoryWindow`.
/// The SwiftUI `Settings` scene + the `showSettingsWindow:` selector are unreliable for a
/// menu-bar (`LSUIElement`) app hosted in an NSPopover outside the scene graph — clicking
/// Settings would open nothing (or focus whatever window was last shown). An explicit
/// NSWindow, opened the same way History is, is the reliable path.
@MainActor
final class SettingsWindow {
    private var window: NSWindow?

    func show(appState: AppState) {
        if window == nil {
            let w = NSWindow(
                contentRect: NSRect(x: 0, y: 0, width: 420, height: 560),
                styleMask: [.titled, .closable, .miniaturizable],
                backing: .buffered,
                defer: false
            )
            w.title = "Just Talk Settings"
            w.isReleasedWhenClosed = false
            w.center()
            w.contentView = NSHostingView(rootView: SettingsView().environmentObject(appState))
            window = w
        }
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
    }
}

// MARK: - SettingsView

struct SettingsView: View {
    @EnvironmentObject var appState: AppState

    var body: some View {
        Form {

            // MARK: Dictation section
            Section {
                Picker("Activation key", selection: Binding(
                    get: { appState.hotkeyConfig },
                    set: { appState.setHotkey($0) }
                )) {
                    ForEach(HotkeyConfig.allCases) { key in
                        Text(key.displayName).tag(key)
                    }
                }
                .pickerStyle(.menu)
                .tint(Theme.Palette.accent)

                Button("Re-run setup…") { appState.showOnboardingWindow() }
                    .buttonStyle(.link)
                    .tint(Theme.Palette.accent)

                Picker("Activation mode", selection: Binding(
                    get: { appState.hotkeyMode },
                    set: { appState.setHotkeyMode($0) }
                )) {
                    ForEach(HotkeyMode.allCases, id: \.self) { mode in
                        Text(mode.displayName).tag(mode)
                    }
                }
                .pickerStyle(.menu)
                .tint(Theme.Palette.accent)

                Toggle("Auto-paste after transcription", isOn: Binding(
                    get: { appState.autoPaste },
                    set: { appState.setAutoPaste($0) }
                ))
                .tint(Theme.Palette.accent)

                Toggle("Play start/stop sounds", isOn: Binding(
                    get: { appState.soundEnabled },
                    set: { appState.setSoundEnabled($0) }
                ))
                .tint(Theme.Palette.accent)

                // Live text (words appear as you speak) is now always on — there is a single
                // capture→transcribe path, so no toggle. The former "Streaming (BETA)" switch was
                // removed when the second (preview) model was deleted.
            } header: {
                sectionHeader("Dictation")
            }

            // MARK: Startup section
            Section {
                Toggle("Launch at login", isOn: Binding(
                    get: { appState.launchAtLogin },
                    set: { appState.setLaunchAtLogin($0) }
                ))
                .tint(Theme.Palette.accent)
            } header: {
                sectionHeader("Startup")
            }

            // MARK: Speech-to-text section
            Section {
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
                .pickerStyle(.menu)
                .tint(Theme.Palette.accent)

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
                    .pickerStyle(.menu)
                    .tint(Theme.Palette.accent)
                }

                if appState.availability == .warmingUp {
                    HStack(spacing: Theme.Space.sm) {
                        ProgressView().scaleEffect(0.7)
                        Text(appState.statusMessage)
                            .font(.caption)
                            .foregroundStyle(Theme.Palette.textTertiary)
                    }
                }
            } header: {
                sectionHeader("Speech-to-text")
            }

            // MARK: AI cleanup section
            Section {
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
                .pickerStyle(.menu)
                .tint(Theme.Palette.accent)

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
                .pickerStyle(.menu)
                .tint(Theme.Palette.accent)

                Text("On-device only. Nothing you say or type leaves this Mac.")
                    .font(.caption2)
                    .foregroundStyle(Theme.Palette.textTertiary)
            } header: {
                sectionHeader("AI Cleanup")
            }

            // MARK: Per-app cleanup section
            Section {
                AppProfilesEditor()
                Text("Override the cleanup level for specific apps — e.g. Off in your terminal or code editor.")
                    .font(.caption2)
                    .foregroundStyle(Theme.Palette.textTertiary)
            } header: {
                sectionHeader("Per-App Cleanup")
            }

            // MARK: Custom vocabulary section
            Section {
                VocabularyEditor()
                Text("Names and jargon — biases transcription and forces spelling after cleanup.")
                    .font(.caption2)
                    .foregroundStyle(Theme.Palette.textTertiary)
            } header: {
                sectionHeader("Custom Vocabulary")
            }

            // MARK: Weekly stats section
            Section {
                let s = appState.weeklyStats
                LabeledContent("Transcripts", value: "\(s.totalCount)")
                LabeledContent("Audio dictated",
                               value: String(format: "%.1f min", s.totalAudioMs / 60_000.0))
                LabeledContent("Avg confidence", value: "\(Int(s.avgConfidence * 100))%")
                LabeledContent("Correction rate",
                               value: "\(String(format: "%.1f", s.correctionRate * 100))%")
                LabeledContent("Avg latency", value: "\(Int(s.avgLatencyMs)) ms")
                LabeledContent("Avg audio length",
                               value: "\(String(format: "%.1f", s.avgAudioDurationMs / 1000.0)) s")
            } header: {
                sectionHeader("This Week")
            }

            // MARK: Usage & cost projection
            Section {
                let t = appState.usageTotals
                LabeledContent("Total dictations", value: "\(t.totalCount)")
                LabeledContent("Total audio",
                               value: String(format: "%.1f min · %.2f hrs", t.totalMinutes, t.totalHours))
                LabeledContent("Est. cloud STT cost",
                               value: String(format: "≈ ₹%.0f", t.totalHours * 45))
                Text("On-device STT + cleanup is free. The estimate shows what a cloud model billed ~₹45/hr would cost at this usage — a reference for Road to Sale pricing.")
                    .font(.caption2).foregroundStyle(Theme.Palette.textTertiary)
            } header: {
                sectionHeader("Usage & Cost (all-time)")
            }
        }
        .formStyle(.grouped)
        .scrollContentBackground(.hidden)
        .environment(\.colorScheme, .dark)
        .frame(width: 420, height: 560)
        .background(Theme.Palette.surface)
        .navigationTitle("Just Talk Settings")
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

    // MARK: Section header

    /// Uppercase, letter-spaced mono label in the tertiary text color — the grouped-form
    /// section header used throughout the redesigned Settings window.
    private func sectionHeader(_ title: String) -> some View {
        Text(title.uppercased())
            .font(Theme.Font.mono(11))
            .foregroundStyle(Theme.Palette.textTertiary)
            .tracking(1.2)
            .textCase(nil)
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
            // Guard against re-parenting adding a second monitor (double-fire / leak).
            if monitor == nil {
                monitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
                    self?.handler(event)
                    return event
                }
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
