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
                contentRect: NSRect(x: 0, y: 0, width: 520, height: 640),
                styleMask: [.titled, .closable, .fullSizeContentView],
                backing: .buffered,
                defer: false
            )
            w.title = "Welcome to Just Talk"
            w.titlebarAppearsTransparent = true
            w.titleVisibility = .hidden
            w.isMovableByWindowBackground = true
            w.isReleasedWhenClosed = false
            w.center()
            w.contentView = NSHostingView(rootView: OnboardingView(appState: appState))
            window = w
        }
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
    }

    func close() { window?.close() }
}

// MARK: - Brand palette

private enum Brand {
    static let red = Color(red: 0.91, green: 0.28, blue: 0.25)     // #E8483F — the HUD wave
    static let gold = Color(red: 0.90, green: 0.70, blue: 0.31)    // #E6B450 — the pill border
    static let blue = Color(red: 0.11, green: 0.32, blue: 0.91)    // accent
    static let indigo = Color(red: 0.35, green: 0.34, blue: 0.84)
    static let green = Color(red: 0.13, green: 0.64, blue: 0.33)
}

// MARK: - OnboardingView (paged wizard)

struct OnboardingView: View {
    @ObservedObject var appState: AppState

    enum Step: Int, CaseIterable {
        case welcome, microphone, accessibility, activationKey, tryIt, stayInTouch

        var accent: Color {
            switch self {
            case .welcome:       return Brand.gold
            case .microphone:    return Brand.red
            case .accessibility: return Brand.blue
            case .activationKey: return Brand.indigo
            case .tryIt:         return Brand.green
            case .stayInTouch:   return Brand.gold
            }
        }
    }

    @State private var step: Step = .welcome
    private let ticker = Timer.publish(every: 1.0, on: .main, in: .common).autoconnect()
    private let testSentence = "Hi there — this is my very first note with Just Talk."

    var body: some View {
        VStack(spacing: 0) {
            // Colorful hero band — accent gradient behind the step's icon.
            HeroBand(step: step)

            ProgressDots(count: Step.allCases.count, index: step.rawValue, accent: step.accent)
                .padding(.top, 14)

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
                .padding(.horizontal, 28)
                .padding(.top, 18)
                .padding(.bottom, 8)
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            Divider()
            footer.padding(.horizontal, 28).padding(.vertical, 14)
        }
        .frame(width: 520, height: 640)
        .background(WindowBackground())
        .onReceive(ticker) { _ in appState.refreshPermissions() }
        .onChange(of: step) { old, new in handleTransition(from: old, to: new) }
        .onChange(of: appState.micGranted) { _, granted in
            if step == .microphone, granted, !appState.micTestActive { appState.beginMicTest() }
        }
        .onDisappear {
            appState.endMicTest(); appState.endHotkeyTest(); appState.armOnboardingCapture(false)
        }
    }

    // MARK: Footer / navigation

    private var footer: some View {
        HStack {
            if step != .welcome {
                Button("Back") { goTo(Step(rawValue: step.rawValue - 1) ?? .welcome) }
                    .controlSize(.large).buttonStyle(.plain).foregroundStyle(.secondary)
            }
            Spacer()
            if step == .stayInTouch {
                Button("Skip for now") { appState.completeOnboarding() }
                    .controlSize(.large).buttonStyle(.plain).foregroundStyle(.secondary)
                Button("Start talking") { appState.submitContact(); appState.completeOnboarding() }
                    .controlSize(.large).buttonStyle(BrandButton(color: step.accent))
            } else {
                Button(step == .welcome ? "Let's go" : "Continue") {
                    goTo(Step(rawValue: step.rawValue + 1) ?? .stayInTouch)
                }
                .controlSize(.large).buttonStyle(BrandButton(color: step.accent))
                .disabled(!canAdvance)
            }
        }
    }

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

    private func goTo(_ next: Step) { withAnimation(.easeInOut(duration: 0.2)) { step = next } }

    private func handleTransition(from old: Step, to new: Step) {
        switch old {
        case .microphone:    appState.endMicTest()
        case .activationKey: appState.endHotkeyTest()
        case .tryIt:         appState.armOnboardingCapture(false)
        default: break
        }
        switch new {
        case .microphone:    if appState.micGranted { appState.beginMicTest() }
        case .activationKey: appState.beginHotkeyTest()
        case .tryIt:         appState.armOnboardingCapture(true)
        default: break
        }
    }
}

// MARK: - Hero band

private struct HeroBand: View {
    let step: OnboardingView.Step

    var body: some View {
        ZStack {
            LinearGradient(colors: [step.accent, step.accent.opacity(0.75)],
                           startPoint: .topLeading, endPoint: .bottomTrailing)
            // Soft light bloom.
            RadialGradient(colors: [.white.opacity(0.28), .clear],
                           center: .topLeading, startRadius: 0, endRadius: 260)
            iconBadge
        }
        .frame(height: 132)
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder private var iconBadge: some View {
        ZStack {
            Circle().fill(.white.opacity(0.22)).frame(width: 82, height: 82)
            Circle().fill(.white.opacity(0.28)).frame(width: 62, height: 62)
            switch step {
            case .welcome, .stayInTouch:
                Image("JustTalkMark").resizable().renderingMode(.template).scaledToFit()
                    .frame(width: 34, height: 34).foregroundStyle(.white)
            default:
                Image(systemName: symbol).font(.system(size: 30, weight: .semibold)).foregroundStyle(.white)
            }
        }
        .shadow(color: .black.opacity(0.12), radius: 8, y: 3)
    }

    private var symbol: String {
        switch step {
        case .microphone:    return "mic.fill"
        case .accessibility: return "keyboard.fill"
        case .activationKey: return "hand.point.up.left.fill"
        case .tryIt:         return "sparkles"
        default:             return "waveform"
        }
    }
}

/// Subtle vertical page background so the content area isn't flat grey.
private struct WindowBackground: View {
    var body: some View {
        LinearGradient(colors: [Color(nsColor: .windowBackgroundColor),
                                Color(nsColor: .windowBackgroundColor).opacity(0.94)],
                       startPoint: .top, endPoint: .bottom)
            .ignoresSafeArea()
    }
}

// MARK: - Brand button style

private struct BrandButton: ButtonStyle {
    let color: Color
    @Environment(\.isEnabled) private var isEnabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 14, weight: .semibold))
            .padding(.horizontal, 22).padding(.vertical, 9)
            .background(
                RoundedRectangle(cornerRadius: 9)
                    .fill(isEnabled ? color : Color.secondary.opacity(0.3))
            )
            .foregroundStyle(.white)
            .opacity(configuration.isPressed ? 0.82 : 1)
            .shadow(color: isEnabled ? color.opacity(0.35) : .clear, radius: 6, y: 2)
    }
}

// MARK: - Progress dots

private struct ProgressDots: View {
    let count: Int
    let index: Int
    let accent: Color
    var body: some View {
        HStack(spacing: 7) {
            ForEach(0..<count, id: \.self) { i in
                Capsule()
                    .fill(i == index ? accent : Color.secondary.opacity(0.22))
                    .frame(width: i == index ? 22 : 7, height: 7)
                    .animation(.easeInOut(duration: 0.25), value: index)
            }
        }
    }
}

// MARK: - Shared title

private struct StepTitle: View {
    let title: String
    let subtitle: String
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.system(size: 25, weight: .bold))
            Text(subtitle).font(.title3).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// Where to find the app after setup — the icon + an arrow toward the menu bar (top-right).
private struct MenuBarLocator: View {
    var body: some View {
        HStack(spacing: 12) {
            HStack(spacing: 8) {
                Image("JustTalkMark").resizable().renderingMode(.template).scaledToFit()
                    .frame(width: 18, height: 18).foregroundStyle(.primary)
                Image(systemName: "arrow.up.right").font(.system(size: 13, weight: .bold))
                    .foregroundStyle(Brand.gold)
            }
            Text("Look for this icon in your menu bar, up top. Click it any time — or just hold your key and talk.")
                .font(.callout).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Brand.gold.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
    }
}

// MARK: - Steps

private struct WelcomeStep: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            StepTitle(title: "Hi — I'm Just Talk.",
                      subtitle: "I turn your voice into text, anywhere on your Mac.")
            Text("Everything happens on this device — nothing you say is ever uploaded. "
                 + "Let's get you set up. It takes about a minute.")
                .font(.body).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            MenuBarLocator()
        }
    }
}

private struct MicStep: View {
    @ObservedObject var appState: AppState
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            StepTitle(title: "First, let me hear you.",
                      subtitle: "I need your microphone. Audio is transcribed on-device and never leaves your Mac.")
            if !appState.micGranted {
                Button("Allow microphone…") { appState.requestMicrophone() }
                    .buttonStyle(BrandButton(color: Brand.red))
                Text("macOS will pop up a box — click **Allow**. Already said no once? This opens the right Settings pane.")
                    .font(.callout).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                MicMeter(level: appState.micInputLevel, passed: appState.micTestPassed)
                if appState.micTestPassed {
                    Label("I can hear you. Sounds great.", systemImage: "checkmark.circle.fill")
                        .font(.title3).foregroundStyle(Brand.green)
                } else {
                    Text("Say something out loud — watch the bars move.")
                        .font(.title3).foregroundStyle(.secondary)
                }
            }
        }
    }
}

private struct MicMeter: View {
    let level: Float
    let passed: Bool
    private let bars = 15
    var body: some View {
        HStack(alignment: .center, spacing: 5) {
            ForEach(0..<bars, id: \.self) { i in
                let phase = Double(i) / Double(bars)
                // Reach full swing around RMS 0.12 (normal speech), so the bars are lively at a
                // conversational volume instead of needing a shout.
                let shaped = CGFloat(min(1, level / 0.12)) * (0.45 + 0.55 * CGFloat(sin(phase * .pi)))
                Capsule()
                    .fill(passed
                          ? AnyShapeStyle(Brand.green)
                          : AnyShapeStyle(LinearGradient(colors: [Brand.red, Brand.gold],
                                                         startPoint: .bottom, endPoint: .top)))
                    .frame(width: 6, height: max(6, 8 + shaped * 50))
                    .animation(.easeOut(duration: 0.12), value: level)
            }
        }
        .frame(height: 66).frame(maxWidth: .infinity)
        .padding(.vertical, 10)
        .background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 14))
    }
}

private struct AccessibilityStep: View {
    @ObservedObject var appState: AppState
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            StepTitle(title: "Now let me type for you.",
                      subtitle: "Accessibility lets me drop text into whatever app you're in, and own your talk key.")
            if appState.accessibilityGranted {
                Label("Granted. You're good.", systemImage: "checkmark.circle.fill")
                    .font(.title3).foregroundStyle(Brand.green)
            } else {
                Button("Open Accessibility settings…") { appState.requestAccessibility() }
                    .buttonStyle(BrandButton(color: Brand.blue))
                Text("Find **Just Talk** in the list and switch it **on**.")
                    .font(.callout).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                Text("Heads up: macOS often needs a relaunch to notice. If this stays grey after you flip it on, use the button below.")
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
            StepTitle(title: "Pick your talk key.",
                      subtitle: "Hold this key anywhere to dictate. The 🌐 (fn) key is the classic choice.")
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
                Label("\(names) already uses fn — macOS can't share one key. Quit it, or pick another key above.",
                      systemImage: "exclamationmark.triangle.fill")
                    .font(.callout).foregroundStyle(.orange).fixedSize(horizontal: false, vertical: true)
            }

            // The "second yellow microphone": macOS's own Dictation also fires on the Globe/fn key.
            if HotkeyConflict.macOSDictationClaimsFn(for: appState.hotkeyConfig) {
                VStack(alignment: .leading, spacing: 8) {
                    Label("macOS Dictation also uses the 🌐 / fn key — that's the yellow microphone popping up. "
                          + "Two apps can't share one key.", systemImage: "exclamationmark.triangle.fill")
                        .font(.callout).foregroundStyle(.orange).fixedSize(horizontal: false, vertical: true)
                    Text("Fix it either way: open **Keyboard → Dictation** and set its Shortcut to **Off**, "
                         + "or just pick a different key for Just Talk above.")
                        .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    Button("Open Keyboard settings…") { appState.openKeyboardSettings() }.controlSize(.small)
                }
                .padding(10).background(.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 10))
            }

            Divider().padding(.vertical, 2)

            if appState.hotkeyTestPassed {
                Label("\(appState.hotkeyConfig.shortName) reaches Just Talk. Perfect.",
                      systemImage: "checkmark.circle.fill")
                    .font(.title3).foregroundStyle(Brand.green)
            } else if !appState.accessibilityGranted {
                Text("Grant Accessibility first (previous step), then press \(appState.hotkeyConfig.shortName) here.")
                    .font(.callout).foregroundStyle(.secondary)
            } else {
                Text("Give it a press: tap **\(appState.hotkeyConfig.shortName)** now.")
                    .font(.title3).foregroundStyle(.secondary)
                Text("If the check doesn't turn green, something's intercepting the key — fix the warning above or pick another.")
                    .font(.caption).foregroundStyle(.tertiary).fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

private struct TryItStep: View {
    @ObservedObject var appState: AppState
    let sentence: String

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            StepTitle(title: "Let's prove it works.",
                      subtitle: "Press your \(appState.hotkeyConfig.shortName) key and read this out loud:")
            Text("\u{201C}\(sentence)\u{201D}")
                .font(.title3).italic()
                .padding(14).frame(maxWidth: .infinity, alignment: .leading)
                .background(Brand.green.opacity(0.10), in: RoundedRectangle(cornerRadius: 12))

            VStack(alignment: .leading, spacing: 6) {
                Text("WHAT I HEARD").font(.caption).bold().foregroundStyle(.tertiary)
                if appState.onboardingTranscript.isEmpty {
                    Text(appState.dictationState == .idle ? "…waiting for you to talk" : "Listening…")
                        .font(.title3).foregroundStyle(.secondary)
                } else {
                    Text(appState.onboardingTranscript).font(.title3).foregroundStyle(.primary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(14).frame(maxWidth: .infinity, minHeight: 74, alignment: .topLeading)
            .background(
                (appState.onboardingTranscript.isEmpty ? Color.secondary.opacity(0.10) : Brand.green.opacity(0.16)),
                in: RoundedRectangle(cornerRadius: 12)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(appState.onboardingTranscript.isEmpty ? Color.clear : Brand.green.opacity(0.5), lineWidth: 1)
            )

            if !appState.onboardingTranscript.isEmpty {
                Label("That's you — typed. This is exactly how it works in any app.",
                      systemImage: "checkmark.circle.fill")
                    .font(.callout).foregroundStyle(Brand.green).fixedSize(horizontal: false, vertical: true)
                Button("Try again") { appState.armOnboardingCapture(true) }.controlSize(.small)
            }
        }
    }
}

private struct ContactStep: View {
    @ObservedObject var appState: AppState
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            StepTitle(title: "You're all set. 🎉",
                      subtitle: "Your voice never leaves your Mac. Leave your details so we can send updates and help if you get stuck.")
            VStack(alignment: .leading, spacing: 12) {
                LabeledField(label: "Name", text: Binding(get: { appState.contactName }, set: { appState.contactName = $0 }), prompt: "Your name")
                LabeledField(label: "Email", text: Binding(get: { appState.contactEmail }, set: { appState.contactEmail = $0 }), prompt: "you@example.com")
                LabeledField(label: "Phone", text: Binding(get: { appState.contactPhone }, set: { appState.contactPhone = $0 }), prompt: "Optional")
            }
            Text("Optional — you can skip. We won't spam you or share it.")
                .font(.caption).foregroundStyle(.tertiary)
            MenuBarLocator()
        }
    }
}

private struct LabeledField: View {
    let label: String
    @Binding var text: String
    let prompt: String
    var body: some View {
        HStack {
            Text(label).frame(width: 58, alignment: .leading).foregroundStyle(.secondary)
            TextField(prompt, text: $text).textFieldStyle(.roundedBorder)
        }
    }
}
