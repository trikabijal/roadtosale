import SwiftUI
import AVFoundation
import UIKit
import DictationCoreBase

// MARK: - Brand

/// Just Talk's onboarding palette — carries the macOS pill's warm gold accent onto iOS for one
/// identity across platforms. Ink on warm paper, a single gold accent, quiet neutrals.
enum JTBrand {
    // ink + muted come from the shared DesignTokens so the neutrals match macOS; paper is iOS-only warm.
    static let ink = Color(red: DesignTokens.Color.ink.red, green: DesignTokens.Color.ink.green, blue: DesignTokens.Color.ink.blue)
    static let paper = Color(red: 0.99, green: 0.985, blue: 0.97)
    // Gold + deep-gold come from the shared `BrandPalette` so the app and the keyboard never diverge.
    static let gold = Color(red: BrandPalette.goldRGB.red, green: BrandPalette.goldRGB.green, blue: BrandPalette.goldRGB.blue)
    static let goldDeep = Color(red: BrandPalette.goldDeepRGB.red, green: BrandPalette.goldDeepRGB.green, blue: BrandPalette.goldDeepRGB.blue)
    static let muted = Color(red: DesignTokens.Color.muted.red, green: DesignTokens.Color.muted.green, blue: DesignTokens.Color.muted.blue)
    static let hairline = Color(red: 0.88, green: 0.87, blue: 0.84)
}

private extension Color {
    /// Build a Color from a shared-token RGB tuple.
    init(_ rgb: (red: Double, green: Double, blue: Double)) { self.init(red: rgb.red, green: rgb.green, blue: rgb.blue) }
}

/// Per-step onboarding accents — the SHARED multi-colour wizard palette (matches macOS onboarding).
private enum StepAccent {
    static let gold = JTBrand.gold
    static let red = Color(DesignTokens.Onboarding.red)
    static let blue = Color(DesignTokens.Onboarding.blue)
    static let indigo = Color(DesignTokens.Onboarding.indigo)
    static let green = Color(DesignTokens.Onboarding.green)
}

// MARK: - Flow

/// The onboarding steps, in order. Kept as an enum so a DEBUG launch arg (`-JTOnboardingPage n`) can
/// jump straight to any page for screenshot verification.
enum OnboardingStep: Int, CaseIterable {
    case hero, enableKeyboard, allowMic, signIn, done
}

/// The container app's first-run experience — mirrors Wispr's flow: an animated hero, a deep-link
/// straight into the app's own Settings pane (not the generic root), a branded mic pre-permission
/// page, install-tracking sign-in, then "you're set". Persists completion so it shows once.
struct OnboardingFlow: View {
    @AppStorage("onboardingComplete") private var complete = false
    // PERSISTED step — going to Settings (for the keyboard) can relaunch this light container app, which
    // used to reset onboarding to page 1. Storing the step means the user returns to where they were.
    @AppStorage("onboardingStepRaw") private var stepRaw = 0
    let onFinish: () -> Void

    private var step: OnboardingStep { OnboardingStep(rawValue: stepRaw) ?? .hero }

    var body: some View {
        ZStack {
            JTBrand.paper.ignoresSafeArea()
            content
                .transition(.asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity),
                                        removal: .move(edge: .leading).combined(with: .opacity)))
                .id(step)
        }
        .preferredColorScheme(.light)
    }

    @ViewBuilder private var content: some View {
        switch step {
        case .hero:           HeroPage(onNext: { advance(to: .enableKeyboard) })
        // The enable step detects the keyboard itself and shows a ✓ before advancing (see the page).
        case .enableKeyboard: EnableKeyboardPage(onContinue: { advance(to: .allowMic) })
        case .allowMic:       AllowMicPage(onNext: { advance(to: .signIn) })
        case .signIn:         SignInPage(onNext: { advance(to: .done) }, onSkip: { advance(to: .done) })
        case .done:           DonePage(onFinish: { stepRaw = 0; complete = true; onFinish() })
        }
    }

    private func advance(to next: OnboardingStep) {
        withAnimation(.spring(response: 0.45, dampingFraction: 0.85)) { stepRaw = next.rawValue }
    }
}

// MARK: - 1. Hero

private struct HeroPage: View {
    let onNext: () -> Void
    @State private var wpm = 40
    @State private var appeared = false

    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            Text("Just Talk")
                .font(.system(size: 15, weight: .semibold)).tracking(2).textCase(.uppercase)
                .foregroundStyle(JTBrand.gold)
            Spacer().frame(height: 24)
            Text("Speak your\nmind, fast.")
                .font(.system(size: 46, weight: .bold, design: .serif))
                .multilineTextAlignment(.center)
                .foregroundStyle(JTBrand.ink)
                .lineSpacing(2)
            Spacer().frame(height: 40)
            // Animated proof: the typing-speed counter races up (Wispr's "45 → 220 wpm" beat).
            VStack(spacing: 4) {
                Text("\(wpm)")
                    .font(.system(size: 78, weight: .heavy, design: .rounded))
                    .foregroundStyle(JTBrand.ink)
                    .contentTransition(.numericText())
                    .monospacedDigit()
                Text("WORDS PER MINUTE")
                    .font(.system(size: 12, weight: .semibold)).tracking(1.5)
                    .foregroundStyle(JTBrand.muted)
            }
            .scaleEffect(appeared ? 1 : 0.9)
            .opacity(appeared ? 1 : 0)
            Spacer().frame(height: 12)
            Text("about 3× faster than thumbs")
                .font(.callout).foregroundStyle(JTBrand.muted)
            Spacer()
            PrimaryButton("Get started", action: onNext)
                .padding(.horizontal, 24).padding(.bottom, 12)
            Text("On-device. Your voice never leaves your iPhone.")
                .font(.footnote).foregroundStyle(JTBrand.muted).padding(.bottom, 8)
        }
        .padding()
        .onAppear {
            withAnimation(.easeOut(duration: 0.6)) { appeared = true }
            // Race the counter 40 → 150.
            Task {
                for target in stride(from: 45, through: 150, by: 5) {
                    try? await Task.sleep(for: .milliseconds(45))
                    withAnimation(.snappy(duration: 0.12)) { wpm = target }
                }
            }
        }
    }
}

// MARK: - 2. Enable keyboard (deep-link into Settings)

/// Detects the two things dictation needs, from the keyboard's OWN Darwin signals (no App-Group / no
/// Full-Access dependency): the keyboard posts `…fullaccess` or `…limited` on load based on its own
/// `hasFullAccess`. Either signal ⇒ the keyboard is enabled; which one ⇒ whether Full Access is on.
@MainActor
final class KeyboardDetector: ObservableObject {
    @Published var enabled = false
    @Published var fullAccess = false

    init() {
        // PRIMARY: read the persisted App-Group flag (written by the keyboard when it loaded with Full
        // Access, e.g. when iOS loaded it as you toggled Full Access) — survives the Settings round-trip.
        refresh()
        // BONUS: live Darwin doorbell for the case where the keyboard loads while the app is foreground.
        let me = Unmanaged.passUnretained(self).toOpaque()
        DictationHandoff.observe(DictationHandoff.keyboardEnabledLimited, observer: me) { _, obs, _, _, _ in
            guard let obs else { return }
            Unmanaged<KeyboardDetector>.fromOpaque(obs).takeUnretainedValue().onSignal(fullAccess: false)
        }
        DictationHandoff.observe(DictationHandoff.keyboardEnabledFullAccess, observer: me) { _, obs, _, _, _ in
            guard let obs else { return }
            Unmanaged<KeyboardDetector>.fromOpaque(obs).takeUnretainedValue().onSignal(fullAccess: true)
        }
    }

    /// Re-read the persisted flag. Called on init, on every foreground, and on a light poll.
    func refresh() {
        if DictationHandoff.keyboardHasFullAccess() { enabled = true; fullAccess = true }
    }

    nonisolated func onSignal(fullAccess: Bool) {
        Task { @MainActor in
            self.enabled = true
            if fullAccess { self.fullAccess = true }
        }
    }

    deinit { DictationHandoff.removeObserver(Unmanaged.passUnretained(self).toOpaque()) }
}

private struct EnableKeyboardPage: View {
    let onContinue: () -> Void
    @StateObject private var detector = KeyboardDetector()
    @Environment(\.scenePhase) private var scenePhase
    @State private var probe = ""
    @State private var showSkip = false

    private var allSet: Bool { detector.enabled && detector.fullAccess }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            OnboardingHeader(eyebrow: "Step 1",
                             title: "Turn on the\nJust Talk keyboard",
                             subtitle: "It's a couple of taps — do these two, then come back here.",
                             accent: StepAccent.blue)
            Spacer().frame(height: 20)
            InstructionRow(n: 1, text: "Open **Settings → Keyboards**, add **Just Talk**, and turn on **Full Access**.", accent: StepAccent.blue)
            InstructionRow(n: 2, text: "Come back here — we detect it automatically. (Not detected? Tap the box and switch to Just Talk via 🌐 once.)", accent: StepAccent.blue)
            Spacer().frame(height: 18)
            statusRow
            Spacer().frame(height: 12)
            TextField("Tap here → press 🌐 → choose Just Talk", text: $probe)
                .padding(.vertical, 12).padding(.horizontal, 14)
                .background(.white, in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke((allSet ? StepAccent.green : StepAccent.blue).opacity(0.5)))
            Spacer()
            PrimaryButton("Open Settings", action: openAppSettings)
                .padding(.bottom, showSkip && !allSet ? 6 : 12)
            if showSkip && !allSet {
                Button("Skip for now", action: onContinue)
                    .font(.footnote).foregroundStyle(JTBrand.muted)
                    .frame(maxWidth: .infinity).padding(.bottom, 8)
            }
        }
        .padding()
        .task { await watch() }
        .task { try? await Task.sleep(for: .seconds(12)); withAnimation { showSkip = true } }
        // Returning from Settings (where Full Access was toggled + iOS loaded the keyboard) → re-read
        // the persisted flag. This is the Wispr-style "detected the moment you come back".
        .onChange(of: scenePhase) { _, phase in if phase == .active { detector.refresh() } }
    }

    // Three states: waiting → enabled-but-no-Full-Access → all set.
    @ViewBuilder private var statusRow: some View {
        let (icon, color, text): (String, Color, String) = {
            if allSet { return ("checkmark.circle.fill", StepAccent.green, "Just Talk is on with Full Access — you're set!") }
            if detector.enabled { return ("exclamationmark.triangle.fill", StepAccent.red, "Keyboard added — now turn on Full Access for it.") }
            return ("circle.dashed", JTBrand.muted, "Waiting for the keyboard…")
        }()
        HStack(spacing: 10) {
            Image(systemName: icon).font(.system(size: 20, weight: .semibold)).foregroundStyle(color)
            Text(text).font(.callout.weight(allSet || detector.enabled ? .semibold : .regular))
                .foregroundStyle(allSet || detector.enabled ? JTBrand.ink : JTBrand.muted)
            Spacer()
        }
        .padding(14)
        .background(color.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
        .animation(.easeInOut, value: detector.enabled)
        .animation(.easeInOut, value: detector.fullAccess)
    }

    /// Poll the Full-Access flag (the Darwin signal sets `enabled` via the observer); advance only when
    /// BOTH are true — enabled AND Full Access — since dictation needs both.
    private func watch() async {
        while !Task.isCancelled {
            detector.refresh()   // poll the persisted flag (covers cases the foreground hook misses)
            if allSet {
                try? await Task.sleep(for: .milliseconds(1200))   // let the ✓ register
                onContinue(); return
            }
            try? await Task.sleep(for: .milliseconds(400))
        }
    }

    /// Opens Just Talk's own pane in Settings. iOS only allows an app to open its OWN Settings root
    /// (not the Keyboards sub-page directly), so the user taps "Keyboards" there — one tap.
    private func openAppSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }
}

private struct ToggleRow: View {
    let title: String
    let on: Bool
    var body: some View {
        HStack {
            Text(title).font(.body).foregroundStyle(JTBrand.ink)
            Spacer()
            // A static, on-looking toggle (preview only).
            Capsule().fill(on ? Color.green : Color(.systemGray4))
                .frame(width: 46, height: 28)
                .overlay(Circle().fill(.white).padding(3), alignment: on ? .trailing : .leading)
        }
        .padding(.horizontal, 16).padding(.vertical, 14)
    }
}

private struct WhyCard: View {
    let icon: String; let title: String; let message: String
    var accent: Color = JTBrand.gold
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon).font(.system(size: 16, weight: .semibold))
                .foregroundStyle(accent).frame(width: 24)
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(JTBrand.ink)
                Text(message).font(.footnote).foregroundStyle(JTBrand.muted).fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(16)
        .background(accent.opacity(0.08), in: RoundedRectangle(cornerRadius: 14))
    }
}

// MARK: - 3. Allow microphone (branded pre-permission)

private struct AllowMicPage: View {
    let onNext: () -> Void
    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            ZStack {
                Circle().fill(StepAccent.red.opacity(0.12)).frame(width: 180, height: 180)
                Circle().fill(StepAccent.red.opacity(0.18)).frame(width: 120, height: 120)
                Image(systemName: "mic.fill").font(.system(size: 54)).foregroundStyle(StepAccent.red)
            }
            Spacer().frame(height: 40)
            Text("Let Just Talk\nhear you")
                .font(.system(size: 34, weight: .bold, design: .serif))
                .multilineTextAlignment(.center).foregroundStyle(JTBrand.ink)
            Spacer().frame(height: 16)
            Text("Just Talk needs the microphone to turn your voice into text — transcribed on-device, never uploaded.")
                .font(.body).multilineTextAlignment(.center).foregroundStyle(JTBrand.muted)
                .padding(.horizontal, 24)
            Spacer()
            PrimaryButton("Allow microphone", action: requestMic).padding(.bottom, 16)
        }
        .padding()
    }

    private func requestMic() {
        AVAudioApplication.requestRecordPermission { _ in
            DispatchQueue.main.async { onNext() }
        }
    }
}

// MARK: - 4. Sign in (install tracking)

private struct SignInPage: View {
    let onNext: () -> Void
    let onSkip: () -> Void
    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            Image(systemName: "sparkles").font(.system(size: 44)).foregroundStyle(StepAccent.indigo)
            Spacer().frame(height: 24)
            Text("Stay in the loop")
                .font(.system(size: 32, weight: .bold, design: .serif)).foregroundStyle(JTBrand.ink)
            Spacer().frame(height: 12)
            Text("Sign in so we can save your preferences and let you know about new features.")
                .font(.body).multilineTextAlignment(.center).foregroundStyle(JTBrand.muted)
                .padding(.horizontal, 28)
            Spacer()
            // Google sign-in — the identity + install signal. Full OAuth needs a Google client id
            // (see InstallSignal); until configured this records an anonymous install ping so we still
            // learn that someone set the app up.
            Button(action: { InstallSignal.signInWithGoogle(); onNext() }) {
                HStack(spacing: 12) {
                    Image(systemName: "g.circle.fill").font(.system(size: 20))
                    Text("Continue with Google").font(.headline)
                }
                .foregroundStyle(JTBrand.ink)
                .frame(maxWidth: .infinity).padding(.vertical, 16)
                .background(.white, in: Capsule())
                .overlay(Capsule().stroke(JTBrand.hairline))
            }
            .padding(.horizontal, 24)
            Button("Not now", action: onSkip)
                .font(.callout.weight(.semibold)).foregroundStyle(JTBrand.muted)
                .padding(.top, 14).padding(.bottom, 8)
        }
        .padding()
    }
}

// MARK: - 5. Done

private struct DonePage: View {
    let onFinish: () -> Void
    @State private var appeared = false
    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            ZStack {
                Circle().fill(StepAccent.green.opacity(0.12)).frame(width: 140, height: 140)
                Image(systemName: "checkmark.circle.fill").font(.system(size: 72)).foregroundStyle(StepAccent.green)
                    .scaleEffect(appeared ? 1 : 0.5).opacity(appeared ? 1 : 0)
            }
            Spacer().frame(height: 32)
            Text("You're all set ✨")
                .font(.system(size: 34, weight: .bold, design: .serif)).foregroundStyle(JTBrand.ink)
            Spacer().frame(height: 16)
            Text("Open any app, tap the 🌐 globe on the keyboard to switch to Just Talk, then hit the mic and speak.")
                .font(.body).multilineTextAlignment(.center).foregroundStyle(JTBrand.muted)
                .padding(.horizontal, 24)
            Spacer()
            PrimaryButton("Start talking", action: onFinish).padding(.bottom, 16)
        }
        .padding()
        .onAppear { withAnimation(.spring(response: 0.5, dampingFraction: 0.6)) { appeared = true } }
    }
}

// MARK: - Shared components

private struct InstructionRow: View {
    let n: Int
    let text: LocalizedStringKey
    let accent: Color
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Text("\(n)").font(.subheadline.weight(.bold)).foregroundStyle(.white)
                .frame(width: 24, height: 24).background(accent, in: Circle())
            Text(text).font(.callout).foregroundStyle(JTBrand.ink)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(.vertical, 6)
    }
}

private struct OnboardingHeader: View {
    let eyebrow: String; let title: String; let subtitle: String
    var accent: Color = JTBrand.gold
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(eyebrow).font(.system(size: 13, weight: .semibold)).tracking(1.5).textCase(.uppercase)
                .foregroundStyle(accent)
            Text(title).font(.system(size: 32, weight: .bold, design: .serif))
                .foregroundStyle(JTBrand.ink).fixedSize(horizontal: false, vertical: true)
            Text(subtitle).font(.body).foregroundStyle(JTBrand.muted)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, 24)
    }
}

private struct PrimaryButton: View {
    let title: String; let action: () -> Void
    init(_ title: String, action: @escaping () -> Void) { self.title = title; self.action = action }
    var body: some View {
        Button(action: action) {
            Text(title).font(.headline).foregroundStyle(.white)
                .frame(maxWidth: .infinity).padding(.vertical, 17)
                .background(JTBrand.ink, in: Capsule())
        }
    }
}
