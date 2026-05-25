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
                    // Fn is a modifier key (fires flagsChanged, not keyDown),
                    // so it can't be captured with onKeyPress. Shown as a
                    // fixed label for now.
                    Text("Fn  (hold to record)")
                        .foregroundStyle(.secondary)
                }

                Toggle("Auto-paste after transcription", isOn: Binding(
                    get: { appState.autoPaste },
                    set: { appState.setAutoPaste($0) }
                ))
            }

            // MARK: Model section
            Section("Model") {
                Picker("WhisperKit model", selection: Binding(
                    get: {
                        ModelTier(rawValue: UserDefaults.standard.string(forKey: "modelTier") ?? "")
                            ?? .largeV3Turbo
                    },
                    set: { appState.setModelTier($0) }
                )) {
                    ForEach(ModelTier.allCases, id: \.self) { tier in
                        Text(tier.displayName).tag(tier)
                    }
                }
                .pickerStyle(.radioGroup)

                if !appState.engineLoaded {
                    HStack(spacing: 6) {
                        ProgressView().scaleEffect(0.7)
                        Text(appState.statusMessage)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
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
        .frame(width: 400, height: 420)
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
