import AppKit
import SwiftUI

// MARK: - OnboardingWindow

/// Hosts the setup wizard in a normal titled window. Managed in AppKit (like RecordingHUD)
/// rather than as a SwiftUI scene, so AppState can open it at launch and from the menu.
@MainActor
final class OnboardingWindow {
    private var window: NSWindow?

    func show(appState: AppState) {
        if window == nil {
            let w = NSWindow(
                contentRect: NSRect(x: 0, y: 0, width: 460, height: 640),
                styleMask: [.titled, .closable],
                backing: .buffered,
                defer: false
            )
            w.title = "Welcome to Just Talk"
            w.isReleasedWhenClosed = false
            w.center()
            w.contentView = NSHostingView(rootView: OnboardingView(appState: appState))
            window = w
        }
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
    }

    func close() {
        window?.close()
    }
}

// MARK: - OnboardingView

struct OnboardingView: View {
    @ObservedObject var appState: AppState

    /// Drives a periodic re-render so competitor/OS-conflict status stays live even when no
    /// @Published value changed.
    @State private var tick = 0
    private let ticker = Timer.publish(every: 1.0, on: .main, in: .common).autoconnect()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                header

                MicCard(appState: appState)
                AccessibilityCard(appState: appState)
                InputMonitoringCard(appState: appState)
                HotkeyCard(appState: appState)
                LaunchCard(appState: appState)

                Button {
                    appState.completeOnboarding()
                } label: {
                    Text(appState.requiredPermissionsGranted ? "Done — start talking" : "Grant the steps above")
                        .frame(maxWidth: .infinity)
                }
                .controlSize(.large)
                .buttonStyle(.borderedProminent)
                .disabled(!appState.requiredPermissionsGranted)
                .padding(.top, 4)
            }
            .padding(20)
        }
        .frame(width: 460, height: 640)
        .onReceive(ticker) { _ in
            tick &+= 1
            appState.refreshPermissions()
        }
        .onDisappear { appState.endHotkeyTest() }
    }

    private var header: some View {
        HStack(spacing: 12) {
            Image(systemName: "waveform")
                .font(.system(size: 28, weight: .semibold))
                .foregroundStyle(.tint)
            VStack(alignment: .leading, spacing: 2) {
                Text("Set up Just Talk").font(.title2).bold()
                Text("Three quick steps. Everything runs on your Mac — nothing you say leaves the device.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

// MARK: - Cards

/// Shared card chrome with a live status badge.
private struct Card<Content: View>: View {
    let number: Int
    let title: String
    let done: Bool
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                StatusBadge(done: done, number: number)
                Text(title).font(.headline)
                Spacer()
            }
            content()
        }
        .padding(14)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 12))
    }
}

private struct StatusBadge: View {
    let done: Bool
    let number: Int

    var body: some View {
        ZStack {
            Circle().fill(done ? Color.green : Color.secondary.opacity(0.3))
                .frame(width: 24, height: 24)
            if done {
                Image(systemName: "checkmark").font(.system(size: 12, weight: .bold))
                    .foregroundStyle(.white)
            } else {
                Text("\(number)").font(.system(size: 12, weight: .bold))
                    .foregroundStyle(.secondary)
            }
        }
    }
}

private struct MicCard: View {
    @ObservedObject var appState: AppState

    var body: some View {
        Card(number: 1, title: "Microphone", done: appState.micGranted) {
            Text("Lets Just Talk hear you. Audio is transcribed on-device and never uploaded.")
                .font(.callout).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            if !appState.micGranted {
                Button("Allow microphone…") { appState.requestMicrophone() }
            }
        }
    }
}

private struct AccessibilityCard: View {
    @ObservedObject var appState: AppState

    var body: some View {
        Card(number: 2, title: "Accessibility", done: appState.accessibilityGranted) {
            Text("Lets Just Talk paste your text into the app you're using and dedicate your "
                 + "activation key (so the Globe emoji picker doesn't pop up).")
                .font(.callout).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            if !appState.accessibilityGranted {
                Button("Open Accessibility settings…") { appState.requestAccessibility() }
                Text("Turn **Just Talk** on in the list. macOS often needs a relaunch to notice — "
                     + "if this stays grey after you've enabled it, click below.")
                    .font(.caption).foregroundStyle(.tertiary)
                    .fixedSize(horizontal: false, vertical: true)
                Button("I've enabled it — Quit & Relaunch") { appState.relaunch() }
                    .controlSize(.small)
            }
        }
    }
}

private struct InputMonitoringCard: View {
    @ObservedObject var appState: AppState

    var body: some View {
        Card(number: 3, title: "Input Monitoring", done: appState.inputMonitoringGranted) {
            Text("Lets Just Talk see your activation key press. macOS treats this separately "
                 + "from Accessibility — both are needed for the hotkey to work reliably.")
                .font(.callout).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            if !appState.inputMonitoringGranted {
                Button("Allow Input Monitoring…") { appState.requestInputMonitoring() }
                Text("Turn **Just Talk** on under Input Monitoring. macOS may need a relaunch to "
                     + "notice — use the relaunch button in step 2 if it stays grey.")
                    .font(.caption).foregroundStyle(.tertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

private struct HotkeyCard: View {
    @ObservedObject var appState: AppState

    private var competitors: [NSRunningApplication] { HotkeyConflict.runningCompetitors() }
    private var osClaimsFn: Bool { HotkeyConflict.osClaimsFn(for: appState.hotkeyConfig) }
    private var hasConflict: Bool { osClaimsFn || (appState.hotkeyConfig.isFn && !competitors.isEmpty) }

    var body: some View {
        Card(number: 4, title: "Activation key", done: appState.hotkeyTestPassed) {
            Picker("Key", selection: Binding(
                get: { appState.hotkeyConfig },
                set: { appState.setHotkey($0) }
            )) {
                ForEach(HotkeyConfig.allCases) { key in
                    Text(key.displayName).tag(key)
                }
            }
            .labelsHidden()

            Picker("Mode", selection: Binding(
                get: { appState.hotkeyMode },
                set: { appState.setHotkeyMode($0) }
            )) {
                ForEach(HotkeyMode.allCases, id: \.self) { mode in
                    Text(mode.displayName).tag(mode)
                }
            }
            .labelsHidden()
            Text(appState.hotkeyMode == .toggle
                 ? "Sticky: tap once to start, talk as long as you like (pauses are fine), tap again to stop."
                 : "Hold the key while you talk; release to finish.")
                .font(.caption).foregroundStyle(.tertiary)
                .fixedSize(horizontal: false, vertical: true)

            // Conflict hints for the SELECTED key — shown regardless of Accessibility, so
            // changing the key immediately reflects whether it's likely to collide.
            if osClaimsFn {
                ConflictRow(
                    text: "macOS uses the Globe key for “\(HotkeyConflict.appleFnUsageLabel())”. Set it to “Do Nothing”, or pick a non-Fn key above.",
                    actionLabel: "Keyboard settings"
                ) {
                    if let url = URL(string: "x-apple.systempreferences:com.apple.preference.keyboard") {
                        NSWorkspace.shared.open(url)
                    }
                }
            }
            if appState.hotkeyConfig.isFn, !competitors.isEmpty {
                ConflictRow(
                    text: "\(competitors.compactMap { $0.localizedName }.joined(separator: ", ")) is running and uses Fn by default — macOS can't share one key between two apps. Quit it, or pick a non-Fn key above.",
                    actionLabel: "Quit it"
                ) {
                    competitors.forEach { _ = $0.terminate() }
                    appState.refreshPermissions()
                }
            }

            // Status / test.
            if appState.hotkeyTestPassed {
                Label("Key works — \(appState.hotkeyConfig.shortName) reaches Just Talk.",
                      systemImage: "checkmark.circle.fill")
                    .font(.callout).foregroundStyle(.green)
            } else if !appState.accessibilityGranted {
                Text("Grant Accessibility (step 2), then press \(appState.hotkeyConfig.shortName) to confirm it.")
                    .font(.caption).foregroundStyle(.tertiary)
            } else {
                if !hasConflict {
                    Label("\(appState.hotkeyConfig.shortName) selected — no conflicts detected.",
                          systemImage: "checkmark.circle")
                        .font(.caption).foregroundStyle(.green)
                }
                Button("Test: press \(appState.hotkeyConfig.shortName) now") { appState.beginHotkeyTest() }
                Text("Press the key. If the check above stays grey, something is intercepting it — fix a hint above or pick another key.")
                    .font(.caption).foregroundStyle(.tertiary)
            }
        }
        .onChange(of: appState.hotkeyTestPassed) { _, passed in
            if passed { appState.endHotkeyTest() }
        }
    }
}

private struct LaunchCard: View {
    @ObservedObject var appState: AppState

    var body: some View {
        Card(number: 5, title: "Launch at login (optional)", done: appState.launchAtLogin) {
            Toggle("Start Just Talk automatically when I log in", isOn: Binding(
                get: { appState.launchAtLogin },
                set: { appState.setLaunchAtLogin($0) }
            ))
        }
    }
}

private struct ConflictRow: View {
    let text: String
    let actionLabel: String
    let action: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
            Text(text).font(.caption).fixedSize(horizontal: false, vertical: true)
            Spacer()
            Button(actionLabel, action: action).controlSize(.small)
        }
        .padding(8)
        .background(.orange.opacity(0.1), in: RoundedRectangle(cornerRadius: 8))
    }
}
