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
                contentRect: NSRect(x: 0, y: 0, width: 500, height: 600),
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

// MARK: - OnboardingView (paged wizard)

struct OnboardingView: View {
    @ObservedObject var appState: AppState

    enum Step: Int, CaseIterable {
        case welcome, microphone, accessibility, activationKey, tryIt, stayInTouch
    }

    @State private var step: Step = .welcome

    /// Periodic re-render so permission/conflict status stays live even when no @Published changed.
    private let ticker = Timer.publish(every: 1.0, on: .main, in: .common).autoconnect()

    private let testSentence = "Hi there — this is my very first note with Just Talk."

    var body: some View {
        VStack(spacing: 0) {
            ProgressDots(count: Step.allCases.count, index: step.rawValue)
                .padding(.top, 18)
                .padding(.bottom, 8)

            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    switch step {
                    case .welcome:       WelcomeStep()
                    case .microphone:    MicStep(appState: appState)
                    case .accessibility: AccessibilityStep(appState: appState)
                    case .activationKey: KeyStep(appState: appState)
                    case .tryIt:         TryItStep(appState: appState, sentence: testSentence)
                    case .stayInTouch:   ContactStep(appState: appState)
                    }
                }
                .padding(24)
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            Divider()
            footer
                .padding(.horizontal, 24)
                .padding(.vertical, 14)
        }
        .frame(width: 500, height: 600)
        .onReceive(ticker) { _ in appState.refreshPermissions() }
        .onChange(of: step) { old, new in handleTransition(from: old, to: new) }
        .onChange(of: appState.micGranted) { _, granted in
            if step == .microphone, granted, !appState.micTestActive { appState.beginMicTest() }
        }
        .onDisappear {
            appState.endMicTest()
            appState.endHotkeyTest()
            appState.armOnboardingCapture(false)
        }
    }

    // MARK: Footer / navigation

    private var footer: some View {
        HStack {
            if step != .welcome {
                Button("Back") { goTo(Step(rawValue: step.rawValue - 1) ?? .welcome) }
                    .controlSize(.large)
            }
            Spacer()
            if step == .stayInTouch {
                Button("Skip for now") { appState.completeOnboarding() }
                    .controlSize(.large)
                Button("Start talking") { appState.submitContact(); appState.completeOnboarding() }
                    .controlSize(.large)
                    .buttonStyle(.borderedProminent)
            } else {
                Button(step == .welcome ? "Let's go" : "Next") {
                    goTo(Step(rawValue: step.rawValue + 1) ?? .stayInTouch)
                }
                .controlSize(.large)
                .buttonStyle(.borderedProminent)
                .disabled(!canAdvance)
            }
        }
    }

    /// Gate Next until the current step's requirement is actually met.
    private var canAdvance: Bool {
        switch step {
        case .welcome:       return true
        case .microphone:    return appState.micGranted && appState.micTestPassed
        case .accessibility: return appState.accessibilityGranted
        case .activationKey: return appState.hotkeyTestPassed
        case .tryIt:         return !appState.onboardingTranscript.isEmpty
        case .stayInTouch:   return true
        }
    }

    private func goTo(_ next: Step) { step = next }

    /// Start/stop the per-step live tests as the user moves between pages.
    private func handleTransition(from old: Step, to new: Step) {
        // Leaving a step → tear its test down.
        switch old {
        case .microphone:    appState.endMicTest()
        case .activationKey: appState.endHotkeyTest()
        case .tryIt:         appState.armOnboardingCapture(false)
        default: break
        }
        // Entering a step → arm its test.
        switch new {
        case .microphone:    if appState.micGranted { appState.beginMicTest() }
        case .activationKey: appState.beginHotkeyTest()
        case .tryIt:         appState.armOnboardingCapture(true)
        default: break
        }
    }
}

// MARK: - Progress dots

private struct ProgressDots: View {
    let count: Int
    let index: Int
    var body: some View {
        HStack(spacing: 8) {
            ForEach(0..<count, id: \.self) { i in
                Capsule()
                    .fill(i == index ? Color.accentColor : Color.secondary.opacity(0.25))
                    .frame(width: i == index ? 22 : 8, height: 8)
                    .animation(.easeInOut(duration: 0.2), value: index)
            }
        }
    }
}

// MARK: - Shared step chrome

private struct StepHeader: View {
    let symbol: String
    let title: String
    let subtitle: String
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Image(systemName: symbol)
                .font(.system(size: 34, weight: .semibold))
                .foregroundStyle(.tint)
            Text(title).font(.title).bold()
            Text(subtitle)
                .font(.title3).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

// MARK: - Steps

private struct WelcomeStep: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            StepHeader(
                symbol: "waveform",
                title: "Hi — I'm Just Talk.",
                subtitle: "I turn your voice into text, anywhere on your Mac."
            )
            Text("Everything happens on this device — nothing you say is ever uploaded. "
                 + "Let's get you set up. It takes about a minute.")
                .font(.body).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

private struct MicStep: View {
    @ObservedObject var appState: AppState
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            StepHeader(
                symbol: "mic.fill",
                title: "First, let me hear you.",
                subtitle: "I need your microphone. Audio is transcribed on-device and never leaves your Mac."
            )
            if !appState.micGranted {
                Button("Allow microphone…") { appState.requestMicrophone() }
                    .controlSize(.large).buttonStyle(.borderedProminent)
                Text("macOS will pop up a box — click **Allow**. Already said no once? "
                     + "This opens the right Settings pane so you can switch it on.")
                    .font(.callout).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                // Live "we can hear you" meter.
                MicMeter(level: appState.micInputLevel, passed: appState.micTestPassed)
                if appState.micTestPassed {
                    Label("I can hear you. Sounds great.", systemImage: "checkmark.circle.fill")
                        .font(.title3).foregroundStyle(.green)
                } else {
                    Text("Say something out loud — watch the bars move.")
                        .font(.title3).foregroundStyle(.secondary)
                }
            }
        }
    }
}

/// A row of bars that swell with live mic level; turns green once the test passes.
private struct MicMeter: View {
    let level: Float
    let passed: Bool
    private let bars = 13
    var body: some View {
        HStack(alignment: .center, spacing: 5) {
            ForEach(0..<bars, id: \.self) { i in
                let phase = Double(i) / Double(bars)
                let shaped = CGFloat(min(1, level * 4)) * (0.5 + 0.5 * CGFloat(sin(phase * .pi)))
                Capsule()
                    .fill(passed ? Color.green : Color.accentColor)
                    .frame(width: 6, height: max(6, 8 + shaped * 46))
                    .animation(.easeOut(duration: 0.12), value: level)
            }
        }
        .frame(height: 60)
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
    }
}

private struct AccessibilityStep: View {
    @ObservedObject var appState: AppState
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            StepHeader(
                symbol: "keyboard.fill",
                title: "Now let me type for you.",
                subtitle: "Accessibility lets me drop text into whatever app you're in, and own your talk key."
            )
            if appState.accessibilityGranted {
                Label("Granted. You're good.", systemImage: "checkmark.circle.fill")
                    .font(.title3).foregroundStyle(.green)
            } else {
                Button("Open Accessibility settings…") { appState.requestAccessibility() }
                    .controlSize(.large).buttonStyle(.borderedProminent)
                Text("Find **Just Talk** in the list and switch it **on**.")
                    .font(.callout).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                Text("Heads up: macOS often needs a relaunch to notice. If this stays grey after "
                     + "you've flipped it on, use the button below.")
                    .font(.callout).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                Button("I've enabled it — Quit & Relaunch") { appState.relaunch() }
                    .controlSize(.regular)
            }
        }
    }
}

private struct KeyStep: View {
    @ObservedObject var appState: AppState

    private var hotkeyModeHint: String {
        switch appState.hotkeyMode {
        case .toggle:    return "Sticky: tap once to start, talk as long as you like, tap again to stop."
        case .holdLatch: return "Hold to talk; release to finish. Or double-tap to lock it hands-free, then tap once to stop."
        case .hold:      return "Hold the key while you talk; release to finish."
        }
    }
    private var competitors: [NSRunningApplication] { HotkeyConflict.runningCompetitors() }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            StepHeader(
                symbol: "hand.point.up.left.fill",
                title: "Pick your talk key.",
                subtitle: "Hold this key anywhere to dictate. The Globe (Fn) key is the classic choice."
            )
            HStack(spacing: 12) {
                Picker("Key", selection: Binding(get: { appState.hotkeyConfig }, set: { appState.setHotkey($0) })) {
                    ForEach(HotkeyConfig.allCases) { key in Text(key.displayName).tag(key) }
                }.labelsHidden().frame(maxWidth: 200)
                Picker("Mode", selection: Binding(get: { appState.hotkeyMode }, set: { appState.setHotkeyMode($0) })) {
                    ForEach(HotkeyMode.allCases, id: \.self) { mode in Text(mode.displayName).tag(mode) }
                }.labelsHidden().frame(maxWidth: 200)
            }
            Text(hotkeyModeHint).font(.callout).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            if appState.hotkeyConfig.isFn, !competitors.isEmpty {
                let names = competitors.compactMap { $0.localizedName }.joined(separator: ", ")
                Label("\(names) already uses Fn — macOS can't share one key. Quit it, or pick another key above.",
                      systemImage: "exclamationmark.triangle.fill")
                    .font(.callout).foregroundStyle(.orange)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Divider().padding(.vertical, 4)

            if appState.hotkeyTestPassed {
                Label("\(appState.hotkeyConfig.shortName) reaches Just Talk. Perfect.",
                      systemImage: "checkmark.circle.fill")
                    .font(.title3).foregroundStyle(.green)
            } else if !appState.accessibilityGranted {
                Text("Grant Accessibility first (previous step), then press \(appState.hotkeyConfig.shortName) here.")
                    .font(.callout).foregroundStyle(.secondary)
            } else {
                Text("Give it a press: tap **\(appState.hotkeyConfig.shortName)** now.")
                    .font(.title3).foregroundStyle(.secondary)
                Text("If the check doesn't turn green, something's intercepting the key — fix the warning above or pick another.")
                    .font(.callout).foregroundStyle(.tertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

private struct TryItStep: View {
    @ObservedObject var appState: AppState
    let sentence: String

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            StepHeader(
                symbol: "sparkles",
                title: "Let's prove it works.",
                subtitle: "Press your \(appState.hotkeyConfig.shortName) key and read this out loud:"
            )
            Text("\u{201C}\(sentence)\u{201D}")
                .font(.title3).italic()
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 12))

            // Result box — their words, typed by Just Talk.
            VStack(alignment: .leading, spacing: 6) {
                Text("WHAT I HEARD").font(.caption).bold().foregroundStyle(.tertiary)
                if appState.onboardingTranscript.isEmpty {
                    Text(appState.dictationState == .idle ? "…waiting for you to talk"
                         : "Listening…")
                        .font(.title3).foregroundStyle(.secondary)
                } else {
                    Text(appState.onboardingTranscript)
                        .font(.title3).foregroundStyle(.primary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(14)
            .frame(maxWidth: .infinity, minHeight: 70, alignment: .topLeading)
            .background(
                (appState.onboardingTranscript.isEmpty ? Color.secondary.opacity(0.12) : Color.green.opacity(0.12)),
                in: RoundedRectangle(cornerRadius: 12)
            )

            if !appState.onboardingTranscript.isEmpty {
                Label("That's you — typed. This is exactly how it works in any app.",
                      systemImage: "checkmark.circle.fill")
                    .font(.callout).foregroundStyle(.green)
                    .fixedSize(horizontal: false, vertical: true)
                Button("Try again") { appState.armOnboardingCapture(true) }
                    .controlSize(.small)
            }
        }
    }
}

private struct ContactStep: View {
    @ObservedObject var appState: AppState
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            StepHeader(
                symbol: "envelope.fill",
                title: "Stay in the loop.",
                subtitle: "Your voice never leaves your Mac. This is just so we can send updates and help if you get stuck."
            )
            VStack(alignment: .leading, spacing: 12) {
                LabeledField(label: "Name", text: Binding(get: { appState.contactName }, set: { appState.contactName = $0 }), prompt: "Your name")
                LabeledField(label: "Email", text: Binding(get: { appState.contactEmail }, set: { appState.contactEmail = $0 }), prompt: "you@example.com")
                LabeledField(label: "Phone", text: Binding(get: { appState.contactPhone }, set: { appState.contactPhone = $0 }), prompt: "Optional")
            }
            Text("Optional — you can skip. We won't spam you or share it.")
                .font(.caption).foregroundStyle(.tertiary)
        }
    }
}

private struct LabeledField: View {
    let label: String
    @Binding var text: String
    let prompt: String
    var body: some View {
        HStack {
            Text(label).frame(width: 60, alignment: .leading).foregroundStyle(.secondary)
            TextField(prompt, text: $text).textFieldStyle(.roundedBorder)
        }
    }
}
