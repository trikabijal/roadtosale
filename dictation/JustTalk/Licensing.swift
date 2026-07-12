import Foundation
import AppKit
import Security
import AppAuth
import DictationCore

/// macOS licensing gate. Wraps the procured AppAuth OIDC+PKCE flow against Keycloak, persists the
/// token set in the Keychain, and derives the app's unlock state from the shared `Entitlement`
/// policy (the `paid` realm role, with an offline grace window).
///
/// The verdict lives in `state`; the app is usable only when `state.isUnlocked`. Sign-in opens an
/// ASWebAuthenticationSession (Keycloak → Google via `kc_idp_hint`), which self-captures the
/// custom-scheme redirect — no URL-scheme registration or openURL handling required.
@MainActor
final class LicensingService: ObservableObject {
    @Published private(set) var state: EntitlementState = .signedOut
    @Published private(set) var isBusy = false
    @Published private(set) var lastError: String?

    private let config = EntitlementConfig.justTalk
    private let entitlement: Entitlement
    private var authState: OIDAuthState? { didSet { persistAuthState() } }
    private var currentFlow: (any OIDExternalUserAgentSession)?

    /// Redirect registered on the `justtalk-app` client in Keycloak. Bundle-id scheme, so it's
    /// unambiguously ours; ASWebAuthenticationSession intercepts it internally.
    private let redirectURL = URL(string: "com.trika.justtalk.mac://oauth2redirect")!
    private let keychain = KeychainItem(account: "com.trika.justtalk.authstate")

    init(entitlement: Entitlement = Entitlement(config: .justTalk)) {
        self.entitlement = entitlement
        self.authState = loadAuthState()
        self.state = entitlement.cachedState()   // instant launch verdict from cache (may be grace)
    }

    /// Email of the signed-in user, for display only.
    var email: String? { entitlement.cachedEmail() }

    /// The signed-in identity (name / email / avatar) for the profile UI. Nil when signed out.
    var profile: UserProfile? { entitlement.cachedProfile() }

    /// Whether we have a stored session at all (drives "Sign in" vs "Sign out" UI).
    var hasSession: Bool { authState != nil }

    // MARK: - Lifecycle

    /// Launch / foreground refresh: if we hold tokens, get a fresh access token and re-verify against
    /// the live JWKS; otherwise fall back to the cached verdict. Network failure keeps the grace state.
    func refresh() async {
        guard let authState else { state = entitlement.cachedState(); return }
        guard let token = await freshAccessToken(authState) else {
            state = entitlement.cachedState()
            return
        }
        state = await entitlement.evaluate(accessToken: token)
    }

    /// Full interactive sign-in. `window` anchors the auth sheet.
    func signIn(presenting window: NSWindow) async {
        isBusy = true; lastError = nil
        defer { isBusy = false }
        do {
            let serviceConfig = try await discoverConfiguration()
            let request = OIDAuthorizationRequest(
                configuration: serviceConfig,
                clientId: config.clientID,
                clientSecret: nil,
                scopes: [OIDScopeOpenID, OIDScopeProfile, "email"],
                redirectURL: redirectURL,
                responseType: OIDResponseTypeCode,
                // Skip Keycloak's own login screen and go straight to Google.
                additionalParameters: ["kc_idp_hint": "google"]
            )
            let newState = try await present(request, window: window)
            self.authState = newState
            await refresh()
        } catch {
            lastError = friendlyMessage(error)
        }
    }

    /// Forget the session and lock the app.
    func signOut() {
        authState = nil
        entitlement.clear()
        state = .signedOut
        lastError = nil
    }

    // MARK: - AppAuth async bridges

    private func discoverConfiguration() async throws -> OIDServiceConfiguration {
        try await withCheckedThrowingContinuation { cont in
            OIDAuthorizationService.discoverConfiguration(forIssuer: config.issuer) { cfg, error in
                if let cfg { cont.resume(returning: cfg) }
                else { cont.resume(throwing: error ?? LicensingError.discoveryFailed) }
            }
        }
    }

    private func present(_ request: OIDAuthorizationRequest, window: NSWindow) async throws -> OIDAuthState {
        try await withCheckedThrowingContinuation { cont in
            currentFlow = OIDAuthState.authState(
                byPresenting: request, presenting: window
            ) { authState, error in
                if let authState { cont.resume(returning: authState) }
                else { cont.resume(throwing: error ?? LicensingError.authFailed) }
            }
        }
    }

    /// Returns a valid access token, transparently refreshing via the refresh token if needed.
    private func freshAccessToken(_ authState: OIDAuthState) async -> String? {
        await withCheckedContinuation { cont in
            authState.performAction { accessToken, _, _ in
                cont.resume(returning: accessToken)
            }
            // performAction may mutate the auth state (rotated refresh token) — re-persist.
            self.persistAuthState()
        }
    }

    // MARK: - Keychain persistence

    private func persistAuthState() {
        guard let authState else { keychain.delete(); return }
        if let data = try? NSKeyedArchiver.archivedData(withRootObject: authState,
                                                         requiringSecureCoding: true) {
            keychain.set(data)
        }
    }

    private func loadAuthState() -> OIDAuthState? {
        guard let data = keychain.get() else { return nil }
        return try? NSKeyedUnarchiver.unarchivedObject(ofClass: OIDAuthState.self, from: data)
    }

    private func friendlyMessage(_ error: Error) -> String {
        let ns = error as NSError
        // User closed the auth sheet — not really an error worth shouting about.
        if ns.domain == OIDGeneralErrorDomain,
           ns.code == OIDErrorCode.userCanceledAuthorizationFlow.rawValue ||
           ns.code == OIDErrorCode.programCanceledAuthorizationFlow.rawValue {
            return ""
        }
        return "Sign-in failed: \(ns.localizedDescription)"
    }
}

enum LicensingError: Error { case discoveryFailed, authFailed }

/// Minimal Keychain wrapper for a single generic-password item (the archived OIDAuthState).
private struct KeychainItem {
    let account: String
    private let service = "com.trika.justtalk.licensing"

    private var baseQuery: [CFString: Any] {
        [kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: account]
    }

    func set(_ data: Data) {
        SecItemDelete(baseQuery as CFDictionary)
        var add = baseQuery
        add[kSecValueData] = data
        add[kSecAttrAccessible] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(add as CFDictionary, nil)
    }

    func get() -> Data? {
        var query = baseQuery
        query[kSecReturnData] = true
        query[kSecMatchLimit] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }

    func delete() { SecItemDelete(baseQuery as CFDictionary) }
}
