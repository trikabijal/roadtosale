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
            VStack(spacing: 0) {
                AccountCard(licensing: licensing)
                Spacer().frame(height: 20)
                Text("You're all set — happy talking.")
                    .font(.callout).foregroundStyle(JTBrand.muted)
            }
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

/// Reusable signed-in identity card — avatar, name, email, plan status, sign out. Shown in the
/// Setup tab and on the gate's "all set" screen so the profile lives in one place.
struct AccountCard: View {
    @ObservedObject var licensing: LicensingService

    var body: some View {
        VStack(spacing: 14) {
            AvatarView(profile: licensing.profile, size: 68)
            VStack(spacing: 3) {
                Text(licensing.profile?.displayName ?? "Your account")
                    .font(.system(size: 20, weight: .bold, design: .serif)).foregroundStyle(JTBrand.ink)
                if let email = licensing.profile?.email {
                    Text(email).font(.callout).foregroundStyle(JTBrand.muted)
                }
            }
            planBadge
            Button("Sign out") { licensing.signOut() }
                .font(.callout.weight(.semibold)).foregroundStyle(JTBrand.muted)
        }
        .frame(maxWidth: .infinity)
        .padding(24)
        .background(.white, in: RoundedRectangle(cornerRadius: 20))
        .overlay(RoundedRectangle(cornerRadius: 20).stroke(JTBrand.hairline))
    }

    private var planBadge: some View {
        let active = licensing.state.isUnlocked
        return HStack(spacing: 6) {
            Circle().fill(active ? Color.green : Color.orange).frame(width: 7, height: 7)
            Text(active ? "Plan active" : "Plan inactive").font(.caption.weight(.semibold))
                .foregroundStyle(JTBrand.ink)
        }
        .padding(.horizontal, 12).padding(.vertical, 6)
        .background((active ? Color.green : Color.orange).opacity(0.12), in: Capsule())
    }
}

/// Circular avatar: the user's Google picture if present, else a gold monogram.
struct AvatarView: View {
    let profile: UserProfile?
    var size: CGFloat = 48

    var body: some View {
        ZStack {
            Circle().fill(JTBrand.gold.opacity(0.18))
            if let s = profile?.pictureURL, let url = URL(string: s) {
                AsyncImage(url: url) { $0.resizable().scaledToFill() } placeholder: { monogram }
                    .clipShape(Circle())
            } else {
                monogram
            }
        }
        .frame(width: size, height: size)
    }

    private var monogram: some View {
        Text(profile?.initials ?? "?")
            .font(.system(size: size * 0.4, weight: .semibold, design: .rounded))
            .foregroundStyle(JTBrand.gold)
    }
}
