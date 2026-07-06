import AppKit
import SwiftUI
import DictationCore

// MARK: - RequirementsWindow

/// Shown at launch when the pre-flight (`SystemPreflight.check`) finds a hard blocker — an Intel
/// Mac, too-old macOS, or too little disk. A clear "here's what your Mac needs" screen instead of a
/// broken onboarding or a stuck first-run model download.
@MainActor
final class RequirementsWindow {
    private var window: NSWindow?

    func show(capabilities: SystemCapabilities) {
        if window == nil {
            let w = NSWindow(
                contentRect: NSRect(x: 0, y: 0, width: 460, height: 380),
                styleMask: [.titled, .closable],
                backing: .buffered,
                defer: false
            )
            w.title = "Just Talk — System Requirements"
            w.isReleasedWhenClosed = false
            w.center()
            w.contentView = NSHostingView(rootView: RequirementsView(capabilities: capabilities))
            window = w
        }
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
    }
}

// MARK: - RequirementsView

struct RequirementsView: View {
    let capabilities: SystemCapabilities

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(spacing: 12) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 26))
                    .foregroundStyle(Theme.Palette.caution)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Just Talk can't run on this Mac yet")
                        .font(.title3.weight(.semibold))
                    Text("Here's what needs to change:")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
            }

            VStack(alignment: .leading, spacing: 14) {
                ForEach(Array(capabilities.blockers.enumerated()), id: \.offset) { _, blocker in
                    HStack(alignment: .top, spacing: 10) {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(Theme.Palette.recording)
                            .padding(.top, 2)
                        Text(message(for: blocker))
                            .font(.callout)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }

            Spacer()

            HStack {
                Button("Software Update…") {
                    if let url = URL(string: "x-apple.systempreferences:com.apple.preferences.softwareupdate") {
                        NSWorkspace.shared.open(url)
                    }
                }
                Spacer()
                Button("Quit") { NSApp.terminate(nil) }
                    .keyboardShortcut(.defaultAction)
            }
        }
        .padding(24)
        .frame(width: 460, height: 380)
        .environment(\.colorScheme, .dark)
        .background(Theme.Palette.surface)
    }

    private func message(for blocker: SystemCapabilities.Blocker) -> String {
        switch blocker {
        case .notAppleSilicon:
            return "Needs an Apple Silicon Mac (M1 or later). Just Talk's speech engine (WhisperKit) "
                + "and on-device cleanup (Apple Intelligence) both require Apple Silicon — an Intel "
                + "Mac can't run them."
        case .osBelow(let minMajor, let current):
            return "Needs macOS \(minMajor) or later — you're on macOS \(current). Update via "
                + "System Settings ▸ General ▸ Software Update."
        case .lowDisk(let neededGB, let freeGB):
            return "Needs about \(Int(neededGB)) GB of free space for the on-device model. You have "
                + String(format: "%.1f", freeGB) + " GB free — clear some space, then reopen Just Talk."
        case .lowRAM(let neededGB, let actualGB):
            return "Needs at least \(Int(neededGB)) GB of memory to run the on-device speech model. "
                + "This Mac has \(Int(actualGB.rounded())) GB, which isn't enough for smooth dictation."
        }
    }
}
