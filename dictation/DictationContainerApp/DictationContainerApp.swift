import DictationCoreBase
import SwiftUI

@main
struct DictationContainerApp: App {
    @State private var recording = false
    @State private var showDiag = false

    var body: some Scene {
        WindowGroup {
            ContentView()
                .fullScreenCover(isPresented: $recording) {
                    RecordSessionView(onClose: { recording = false })
                }
                .fullScreenCover(isPresented: $showDiag) {
                    DiagView(onClose: { showDiag = false })
                }
                .onOpenURL { url in
                    guard url.scheme == DictationHandoff.urlScheme else { return }
                    switch url.host {
                    case "record": recording = true
                    case "diag":   showDiag = true   // justtalk://diag — show the keyboard log
                    default: break
                    }
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
