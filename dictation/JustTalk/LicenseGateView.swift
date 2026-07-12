import SwiftUI
import AppKit
import DictationCore

/// The macOS licensing window — shown when a locked user tries to dictate, or from the menu's
/// Account row. Content is a pure function of `LicensingService.state`: sign in, or (signed in but
/// unpaid) a plan-inactive message. It auto-closes the moment entitlement is granted.
struct LicenseGateView: View {
    @ObservedObject var licensing: LicensingService
    /// The hosting window — the anchor ASWebAuthenticationSession presents from, and what we close.
    let window: NSWindow

    private let gold = Color(red: BrandPalette.goldRGB.red, green: BrandPalette.goldRGB.green, blue: BrandPalette.goldRGB.blue)

    var body: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 28)
            Image("JustTalkMark")
                .resizable().renderingMode(.template)
                .frame(width: 46, height: 46)
                .foregroundStyle(gold)
            Spacer().frame(height: 22)
            content
            Spacer(minLength: 28)
        }
        .frame(width: 380)
        .padding(.horizontal, 32)
        .onChange(of: licensing.state) { _, new in
            if new.isUnlocked { window.close() }
        }
    }

    @ViewBuilder private var content: some View {
        switch licensing.state {
        case .entitled, .gracePeriod:
            titled("You're all set", "Just Talk is unlocked. Happy talking.")
        case .notEntitled:
            planInactive
        case .signedOut:
            signIn
        }
    }

    // MARK: - Signed out

    private var signIn: some View {
        VStack(spacing: 0) {
            titled("Unlock Just Talk", "Sign in to activate dictation on this Mac.")
            Spacer().frame(height: 24)
            googleButton(title: "Continue with Google")
            errorRow
        }
    }

    // MARK: - Signed in, not paid

    private var planInactive: some View {
        VStack(spacing: 0) {
            titled("Your plan isn't active", licensing.email.map { "Signed in as \($0)." } ?? "Signed in.")
            Spacer().frame(height: 10)
            Text("Once your subscription is active, Just Talk unlocks automatically here — no need to sign in again.")
                .font(.footnote).foregroundStyle(.secondary)
                .multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
            Spacer().frame(height: 22)
            Button("Check again") { Task { await licensing.refresh() } }
                .buttonStyle(.borderedProminent).tint(gold)
            Button("Sign out") { licensing.signOut() }
                .buttonStyle(.link).padding(.top, 8)
            errorRow
        }
    }

    // MARK: - Pieces

    private func titled(_ title: String, _ subtitle: String) -> some View {
        VStack(spacing: 8) {
            Text(title)
                .font(.system(size: 22, weight: .bold, design: .serif))
                .multilineTextAlignment(.center)
            Text(subtitle)
                .font(.callout).foregroundStyle(.secondary)
                .multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
        }
    }

    private func googleButton(title: String) -> some View {
        Button {
            Task { await licensing.signIn(presenting: window) }
        } label: {
            HStack(spacing: 10) {
                if licensing.isBusy {
                    ProgressView().controlSize(.small)
                } else {
                    Image(systemName: "g.circle.fill")
                }
                Text(licensing.isBusy ? "Signing in…" : title).fontWeight(.semibold)
            }
            .frame(maxWidth: .infinity).padding(.vertical, 6)
        }
        .buttonStyle(.borderedProminent).tint(gold)
        .controlSize(.large)
        .disabled(licensing.isBusy)
    }

    @ViewBuilder private var errorRow: some View {
        if let err = licensing.lastError, !err.isEmpty {
            Text(err).font(.footnote).foregroundStyle(.red)
                .multilineTextAlignment(.center).padding(.top, 12)
        }
    }
}

/// Owns the single licensing window and presents it. A menu-bar (LSUIElement) app has no windows by
/// default, so we create one on demand — it also serves as the presentation anchor for the auth sheet.
@MainActor
final class LicenseWindowController {
    static let shared = LicenseWindowController()
    private var window: NSWindow?

    func present(licensing: LicensingService) {
        if let window {
            window.makeKeyAndOrderFront(nil)
            NSApp.activate(ignoringOtherApps: true)
            return
        }
        let win = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 380, height: 340),
            styleMask: [.titled, .closable, .fullSizeContentView],
            backing: .buffered, defer: false
        )
        win.titlebarAppearsTransparent = true
        win.titleVisibility = .hidden
        win.isMovableByWindowBackground = true
        win.center()
        win.isReleasedWhenClosed = false
        win.contentView = NSHostingView(rootView: LicenseGateView(licensing: licensing, window: win))
        window = win
        win.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }
}
