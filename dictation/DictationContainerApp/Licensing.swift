import Foundation
import UIKit
import Security
import AppAuth
import DictationCoreBase

/// iOS licensing gate — the container-app twin of the macOS `LicensingService`. Same OIDC+PKCE flow
/// against Keycloak (Google via `kc_idp_hint`), same shared `Entitlement` policy; only the
/// presentation (a `UIViewController` anchor) and the storage suite differ.
///
/// The entitlement verdict is cached in the App-Group suite so it lives in the shared container; the
/// token set lives in the app's Keychain.
@MainActor
final class LicensingService: ObservableObject {
    @Published private(set) var state: EntitlementState = .signedOut
    @Published private(set) var isBusy = false
    @Published private(set) var lastError: String?

    private let config = EntitlementConfig.justTalk
    private let entitlement: Entitlement
    private var authState: OIDAuthState? { didSet { persistAuthState() } }
    private var currentFlow: (any OIDExternalUserAgentSession)?

    private let redirectURL = URL(string: "com.trika.justtalk.ios://oauth2redirect")!
    private let keychain = KeychainItem(account: "com.trika.justtalk.ios.authstate")

    init() {
        let defaults = UserDefaults(suiteName: DictationHandoff.appGroup) ?? .standard
        self.entitlement = Entitlement(config: .justTalk, defaults: defaults)
        self.authState = loadAuthState()
        self.state = entitlement.cachedState()
    }

    var email: String? { entitlement.cachedEmail() }
    /// The signed-in identity (name / email / avatar) for the profile UI. Nil when signed out.
    var profile: UserProfile? { entitlement.cachedProfile() }
    var hasSession: Bool { authState != nil }

    // MARK: - Lifecycle

    func refresh() async {
        guard let authState else { state = entitlement.cachedState(); return }
        guard let token = await freshAccessToken(authState) else {
            state = entitlement.cachedState(); return
        }
        state = await entitlement.evaluate(accessToken: token)
    }

    func signIn() async {
        guard let presenter = Self.topViewController() else {
            lastError = "Couldn't present sign-in."; return
        }
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
                additionalParameters: ["kc_idp_hint": "google"]
            )
            let newState = try await present(request, presenter: presenter)
            self.authState = newState
            await refresh()
        } catch {
            lastError = friendlyMessage(error)
        }
    }

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

    private func present(_ request: OIDAuthorizationRequest,
                         presenter: UIViewController) async throws -> OIDAuthState {
        try await withCheckedThrowingContinuation { cont in
            currentFlow = OIDAuthState.authState(
                byPresenting: request, presenting: presenter
            ) { authState, error in
                if let authState { cont.resume(returning: authState) }
                else { cont.resume(throwing: error ?? LicensingError.authFailed) }
            }
        }
    }

    private func freshAccessToken(_ authState: OIDAuthState) async -> String? {
        await withCheckedContinuation { cont in
            authState.performAction { accessToken, _, _ in
                cont.resume(returning: accessToken)
            }
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
        if ns.domain == OIDGeneralErrorDomain,
           ns.code == OIDErrorCode.userCanceledAuthorizationFlow.rawValue ||
           ns.code == OIDErrorCode.programCanceledAuthorizationFlow.rawValue {
            return ""
        }
        return "Sign-in failed: \(ns.localizedDescription)"
    }

    /// Top-most view controller across the active foreground scene, to anchor the auth sheet.
    private static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
        var top = scene?.keyWindow?.rootViewController
            ?? scene?.windows.first { $0.isKeyWindow }?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}

enum LicensingError: Error { case discoveryFailed, authFailed }

/// Minimal Keychain wrapper for the archived OIDAuthState.
private struct KeychainItem {
    let account: String
    private let service = "com.trika.justtalk.ios.licensing"

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
