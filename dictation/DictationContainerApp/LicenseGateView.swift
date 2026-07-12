import SwiftUI
import DictationCoreBase

/// The iOS licensing screen — shown after onboarding whenever the app isn't unlocked. Pure function
/// of `LicensingService.state`: sign in, or (signed in but unpaid) a plan-inactive message. Uses the
/// shared `JTBrand` palette from OnboardingFlow so it matches the rest of the app.
struct LicenseGateView: View {
    @ObservedObject var licensing: LicensingService

    var body: some View {
        ZStack {
            JTBrand.paper.ignoresSafeArea()
            VStack(spacing: 0) {
                Spacer()
                Image(systemName: "waveform.circle.fill")
                    .font(.system(size: 60)).foregroundStyle(JTBrand.gold)
                Spacer().frame(height: 28)
                content
                Spacer()
            }
            .padding(.horizontal, 32)
        }
        .preferredColorScheme(.light)
    }

    @ViewBuilder private var content: some View {
        switch licensing.state {
        case .entitled, .gracePeriod:
            titled("You're all set", "Just Talk is unlocked.")
        case .notEntitled:
            planInactive
        case .signedOut:
            signIn
        }
    }

    private var signIn: some View {
        VStack(spacing: 0) {
            titled("Unlock Just Talk", "Sign in to activate dictation.")
            Spacer().frame(height: 28)
            googleButton
            errorRow
        }
    }

    private var planInactive: some View {
        VStack(spacing: 0) {
            titled("Your plan isn't active", licensing.email.map { "Signed in as \($0)." } ?? "Signed in.")
            Spacer().frame(height: 10)
            Text("Once your subscription is active, Just Talk unlocks here automatically.")
                .font(.footnote).foregroundStyle(JTBrand.muted)
                .multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
            Spacer().frame(height: 24)
            Button("Check again") { Task { await licensing.refresh() } }
                .font(.headline).foregroundStyle(.white)
                .frame(maxWidth: .infinity).padding(.vertical, 16)
                .background(JTBrand.ink, in: Capsule())
            Button("Sign out") { licensing.signOut() }
                .font(.callout.weight(.semibold)).foregroundStyle(JTBrand.muted)
                .padding(.top, 12)
            errorRow
        }
    }

    private func titled(_ title: String, _ subtitle: String) -> some View {
        VStack(spacing: 10) {
            Text(title)
                .font(.system(size: 30, weight: .bold, design: .serif))
                .foregroundStyle(JTBrand.ink).multilineTextAlignment(.center)
            Text(subtitle)
                .font(.body).foregroundStyle(JTBrand.muted)
                .multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
        }
    }

    private var googleButton: some View {
        Button {
            Task { await licensing.signIn() }
        } label: {
            HStack(spacing: 12) {
                if licensing.isBusy { ProgressView().tint(.white) }
                else { Image(systemName: "g.circle.fill").font(.system(size: 20)) }
                Text(licensing.isBusy ? "Signing in…" : "Continue with Google").font(.headline)
            }
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity).padding(.vertical, 16)
            .background(JTBrand.ink, in: Capsule())
        }
        .disabled(licensing.isBusy)
    }

    @ViewBuilder private var errorRow: some View {
        if let err = licensing.lastError, !err.isEmpty {
            Text(err).font(.footnote).foregroundStyle(.red)
                .multilineTextAlignment(.center).padding(.top, 14)
        }
    }
}
