import Foundation

/// The licensing gate. Given a Keycloak-issued access token, decides whether the app is unlocked
/// (`paid` role present) and caches the last-good verdict so a paying user isn't locked out by a
/// flaky network. The OIDC login itself lives in the app targets (AppAuth); this type is the
/// platform-agnostic policy + verification, exposed via the DictationCore facade.
///
/// Security posture: the cached artifact is the SIGNED token, and we re-verify its signature
/// against the realm's JWKS before trusting any claim — so editing the on-disk cache to fake
/// `paid` fails verification. See `EntitlementJWT`.
public struct EntitlementConfig: Sendable, Equatable {
    /// Realm issuer, e.g. `https://justtalk.trika.ai/realms/justtalk`.
    public let issuer: URL
    /// Public OIDC client id registered in the realm.
    public let clientID: String
    /// Realm role that unlocks the app.
    public let requiredRole: String
    /// How long a previously-verified `entitled` verdict keeps the app unlocked while offline.
    public let graceInterval: TimeInterval

    public init(issuer: URL, clientID: String, requiredRole: String = "paid",
                graceInterval: TimeInterval = 7 * 24 * 3600) {
        self.issuer = issuer
        self.clientID = clientID
        self.requiredRole = requiredRole
        self.graceInterval = graceInterval
    }

    public var discoveryURL: URL { issuer.appendingPathComponent(".well-known/openid-configuration") }
    public var authorizationEndpoint: URL { issuer.appendingPathComponent("protocol/openid-connect/auth") }
    public var tokenEndpoint: URL { issuer.appendingPathComponent("protocol/openid-connect/token") }
    public var jwksURL: URL { issuer.appendingPathComponent("protocol/openid-connect/certs") }
    public var endSessionEndpoint: URL { issuer.appendingPathComponent("protocol/openid-connect/logout") }

    /// The Just Talk production realm. Host must match Keycloak's `KC_HOSTNAME`.
    public static let justTalk = EntitlementConfig(
        issuer: URL(string: "https://justtalk.trika.ai/realms/justtalk")!,
        clientID: "justtalk-app"
    )
}

/// What the app should show.
public enum EntitlementState: Equatable, Sendable {
    case entitled                    // signed in, `paid` present, token valid
    case notEntitled                 // signed in, but no `paid` role → show paywall
    case signedOut                   // no usable token → show sign-in
    case gracePeriod(until: Date)    // offline, but a recent verified `entitled` verdict still holds

    /// Whether the app's features should be usable.
    public var isUnlocked: Bool {
        switch self {
        case .entitled: return true
        case .gracePeriod: return true
        case .notEntitled, .signedOut: return false
        }
    }
}

/// The signed-in identity, for display in the app's profile UI. Purely cosmetic — never a security
/// signal; entitlement is decided by the verified `paid` role, not by anything here.
public struct UserProfile: Codable, Equatable, Sendable {
    public let name: String?
    public let email: String?
    public let pictureURL: String?

    public init(name: String?, email: String?, pictureURL: String?) {
        self.name = name; self.email = email; self.pictureURL = pictureURL
    }

    /// Full name if known, else the email's local part, else "Account".
    public var displayName: String {
        if let name, !name.isEmpty { return name }
        if let email, let local = email.split(separator: "@").first { return String(local) }
        return "Account"
    }

    /// 1–2 letter monogram for an avatar when there's no picture. Derived from the display name, so
    /// an email-only user reads as the first letter of the local part (not the domain).
    public var initials: String {
        let words = displayName.split(whereSeparator: { $0 == " " || $0 == "." })
        let letters = words.prefix(2).compactMap { $0.first }
        let text = String(letters).uppercased()
        return text.isEmpty ? "?" : text
    }
}

/// Persisted last-good verdict, so the grace window survives relaunches and offline starts.
struct EntitlementCache: Codable, Equatable {
    var entitled: Bool
    var lastVerifiedAt: Date
    var email: String?
    var name: String?
    var pictureURL: String?
}

/// Evaluates and caches entitlement. Injectable JWKS fetch + clock keep it unit-testable offline.
public final class Entitlement {
    public let config: EntitlementConfig

    private let defaults: UserDefaults
    private let cacheKey: String
    private let fetchJWKS: (URL) async throws -> Data
    private let now: () -> Date

    /// - Parameters:
    ///   - defaults: where the last-good verdict is stored (app-group suite on iOS, standard on macOS).
    ///   - fetchJWKS: returns the raw JWKS JSON for a URL. Defaults to `URLSession`.
    ///   - now: clock, injectable for tests.
    public init(config: EntitlementConfig = .justTalk,
                defaults: UserDefaults = .standard,
                fetchJWKS: ((URL) async throws -> Data)? = nil,
                now: @escaping () -> Date = Date.init) {
        self.config = config
        self.defaults = defaults
        self.cacheKey = "entitlement.cache.\(config.clientID)"
        self.now = now
        self.fetchJWKS = fetchJWKS ?? { url in
            let (data, response) = try await URLSession.shared.data(from: url)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                throw URLError(.badServerResponse)
            }
            return data
        }
    }

    // MARK: - Online evaluation

    /// Verify `accessToken` against the realm's JWKS and return the resulting state, persisting a
    /// fresh last-good verdict. Throws only if the token is malformed or its signature is invalid
    /// (i.e. not a genuine realm token) — network failures fall back to the cached verdict.
    @discardableResult
    public func evaluate(accessToken: String) async -> EntitlementState {
        do {
            let jwksData = try await fetchJWKS(config.jwksURL)
            let jwks = try JSONDecoder().decode(EntitlementJWT.JWKS.self, from: jwksData)
            let verified = try EntitlementJWT.verify(accessToken, jwks: jwks)
            let claims = verified.claims

            // Expired token → treat as signed out; the app should refresh via AppAuth and re-evaluate.
            if let exp = claims.exp, Date(timeIntervalSince1970: exp) <= now() {
                return cachedStateOnFailure(default: .signedOut)
            }
            let entitled = claims.roles.contains(config.requiredRole)
            store(EntitlementCache(entitled: entitled, lastVerifiedAt: now(),
                                   email: claims.email, name: claims.displayName,
                                   pictureURL: claims.picture))
            return entitled ? .entitled : .notEntitled
        } catch let e as EntitlementJWT.VerifyError
                    where e == .signatureInvalid || e == .malformed || e == .unsupportedAlg {
            // A token that fails verification is not trustworthy — do not fall back to a cached "yes".
            clear()
            return .signedOut
        } catch {
            // Network / JWKS fetch failure (unknownKey can also mean we couldn't reach fresh keys):
            // fall back to the cached verdict within the grace window.
            return cachedStateOnFailure(default: .signedOut)
        }
    }

    // MARK: - Offline / cached evaluation

    /// The state derived purely from the persisted verdict — used at launch before any network call,
    /// and whenever we're offline. An `entitled` cache stays unlocked until `graceInterval` elapses.
    public func cachedState() -> EntitlementState {
        guard let cache = loadCache() else { return .signedOut }
        guard cache.entitled else { return .notEntitled }
        let expiresAt = cache.lastVerifiedAt.addingTimeInterval(config.graceInterval)
        return now() < expiresAt ? .gracePeriod(until: expiresAt) : .signedOut
    }

    /// The email of the currently cached user, if any (for UI only — not a security signal).
    public func cachedEmail() -> String? { loadCache()?.email }

    /// The cached signed-in identity, for the profile UI. Nil when signed out.
    public func cachedProfile() -> UserProfile? {
        guard let cache = loadCache(), cache.email != nil || cache.name != nil else { return nil }
        return UserProfile(name: cache.name, email: cache.email, pictureURL: cache.pictureURL)
    }

    /// Forget the entitlement (sign-out).
    public func clear() { defaults.removeObject(forKey: cacheKey) }

    // MARK: - Private

    private func cachedStateOnFailure(default fallback: EntitlementState) -> EntitlementState {
        let cached = cachedState()
        // Only a still-valid grace verdict rescues an offline failure; otherwise use the fallback.
        if case .gracePeriod = cached { return cached }
        if case .entitled = cached { return cached }
        return fallback
    }

    private func store(_ cache: EntitlementCache) {
        if let data = try? JSONEncoder().encode(cache) { defaults.set(data, forKey: cacheKey) }
    }

    private func loadCache() -> EntitlementCache? {
        guard let data = defaults.data(forKey: cacheKey) else { return nil }
        return try? JSONDecoder().decode(EntitlementCache.self, from: data)
    }
}
