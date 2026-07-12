import DictationCoreBase
import SwiftUI

@main
struct DictationContainerApp: App {
    @State private var recording = false
    @State private var showDiag = false
    @AppStorage("onboardingComplete") private var onboardingComplete = false
    @Environment(\.scenePhase) private var scenePhase
    /// App-level so the silent keep-alive persists across screens + backgrounding — the whole point of
    /// warming: the session is hot before the first dictation, so no cold launch / app switch.
    @StateObject private var session = RecordSessionModel()

    var body: some Scene {
        WindowGroup {
            Group {
                if onboardingComplete {
                    ContentView()
                } else {
                    // Warm the session the moment onboarding finishes → the first real dictation is
                    // already hot (Wispr parity: it never leaves the app you're typing in).
                    OnboardingFlow(onFinish: { Task { await session.warm() } })
                }
            }
                .fullScreenCover(isPresented: $recording) {
                    RecordSessionView(model: session, onClose: { recording = false })
                }
                .fullScreenCover(isPresented: $showDiag) {
                    DiagView(onClose: { showDiag = false })
                }
                .onOpenURL { url in
                    guard url.scheme == DictationHandoff.urlScheme else { return }
                    switch url.host {
                    case "record": recording = true; Task { await session.begin() }
                    case "diag":   showDiag = true   // justtalk://diag — show the keyboard log
                    default: break
                    }
                }
                .onChange(of: scenePhase) { _, phase in
                    DictationHandoff.trace("app", "scenePhase → \(phase)")
                    // Re-warm on every foreground once set up (covers app relaunch / iOS reclaiming the
                    // keep-alive). Idempotent; needs mic already granted (post-onboarding).
                    if phase == .active && onboardingComplete { Task { await session.warm() } }
                    // Swiped back to the host app → dismiss the record cover but KEEP the session alive
                    // (it records in the background). The cover is only for the cold-launch moment.
                    if phase == .background && recording { recording = false }
                }
        }
    }
}

/// Reads the keyboard's diagnostic log from the shared App Group and shows it (open via
/// justtalk://diag). Also reports whether the App Group container is reachable at all.
struct DiagView: View {
    let onClose: () -> Void
    @State private var text = "loading…"

    private var appGroupURL: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "group.com.trika.dictation")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Keyboard Diagnostics").font(.headline)
                Spacer()
                Button("Close", action: onClose)
            }
            ScrollView {
                Text(text).font(.system(size: 11, design: .monospaced))
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding()
        .onAppear(perform: load)
    }

    private func load() {
        guard let group = appGroupURL else {
            text = "APP GROUP UNAVAILABLE — containerURL is nil (entitlement/provisioning not wired)"
            return
        }
        let log = group.appendingPathComponent("keyboard-diag.log")
        if let s = try? String(contentsOf: log, encoding: .utf8), !s.isEmpty {
            text = "APP GROUP OK: \(group.path)\n\n" + s
        } else {
            text = "APP GROUP OK: \(group.path)\n\n(keyboard-diag.log is empty or missing — tap the keyboard mic first)"
        }
    }
}
