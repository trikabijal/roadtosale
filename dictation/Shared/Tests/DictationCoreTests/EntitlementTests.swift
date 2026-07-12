import XCTest
import Security
@testable import DictationCoreBase

/// Exercises the licensing gate end-to-end WITHOUT a server: we mint a real RSA key in-test, sign a
/// JWT with it, publish its public half as a JWKS, and check that `EntitlementJWT` verifies genuine
/// tokens and rejects tampered / wrong-key ones — plus the role + grace-window policy in `Entitlement`.
final class EntitlementTests: XCTestCase {

    // MARK: - Test crypto helpers

    /// A freshly generated 2048-bit RSA key pair.
    private func makeKeyPair() throws -> (priv: SecKey, pub: SecKey) {
        let attrs: [CFString: Any] = [
            kSecAttrKeyType: kSecAttrKeyTypeRSA,
            kSecAttrKeySizeInBits: 2048,
        ]
        var err: Unmanaged<CFError>?
        guard let priv = SecKeyCreateRandomKey(attrs as CFDictionary, &err),
              let pub = SecKeyCopyPublicKey(priv) else {
            throw XCTSkip("RSA key generation unavailable in this environment")
        }
        return (priv, pub)
    }

    /// Build a JWKS containing one key (`kid` = "test") from an RSA public key.
    private func jwks(from pub: SecKey, kid: String = "test") throws -> EntitlementJWT.JWKS {
        var err: Unmanaged<CFError>?
        guard let der = SecKeyCopyExternalRepresentation(pub, &err) as Data? else {
            throw XCTSkip("cannot export public key")
        }
        let (n, e) = try Self.parsePKCS1(der)
        let jwk = EntitlementJWT.JWK(kty: "RSA", kid: kid,
                                     n: b64url(n), e: b64url(e), alg: "RS256", use: "sig")
        return EntitlementJWT.JWKS(keys: [jwk])
    }

    /// Mint a signed RS256 JWT for the given claims JSON.
    private func signJWT(claims: [String: Any], priv: SecKey, kid: String = "test") throws -> String {
        let header: [String: Any] = ["alg": "RS256", "kid": kid, "typ": "JWT"]
        let headerData = try JSONSerialization.data(withJSONObject: header)
        let payloadData = try JSONSerialization.data(withJSONObject: claims)
        let signingInput = b64url(headerData) + "." + b64url(payloadData)

        var err: Unmanaged<CFError>?
        guard let sig = SecKeyCreateSignature(priv, .rsaSignatureMessagePKCS1v15SHA256,
                                              Data(signingInput.utf8) as CFData, &err) as Data? else {
            throw XCTSkip("cannot sign")
        }
        return signingInput + "." + b64url(sig)
    }

    private func b64url(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// Parse `RSAPublicKey ::= SEQUENCE { INTEGER modulus, INTEGER exponent }` → (n, e) magnitudes.
    private static func parsePKCS1(_ der: Data) throws -> (Data, Data) {
        var i = der.startIndex
        func byte() -> UInt8 { defer { i = der.index(after: i) }; return der[i] }
        func readLen() -> Int {
            let first = byte()
            if first & 0x80 == 0 { return Int(first) }
            var len = 0
            for _ in 0..<Int(first & 0x7F) { len = (len << 8) | Int(byte()) }
            return len
        }
        func readInteger() -> Data {
            precondition(byte() == 0x02, "expected INTEGER")
            let len = readLen()
            var bytes = [UInt8]()
            for _ in 0..<len { bytes.append(byte()) }
            while bytes.first == 0x00 { bytes.removeFirst() }   // strip DER sign byte for JWK magnitude
            return Data(bytes)
        }
        precondition(byte() == 0x30, "expected SEQUENCE")
        _ = readLen()
        let n = readInteger()
        let e = readInteger()
        return (n, e)
    }

    private func freshDefaults(_ name: String = #function) -> UserDefaults {
        let d = UserDefaults(suiteName: "entitlement.test.\(name)")!
        d.removePersistentDomain(forName: "entitlement.test.\(name)")
        return d
    }

    private let config = EntitlementConfig(
        issuer: URL(string: "https://example.test/realms/justtalk")!,
        clientID: "justtalk-app", requiredRole: "paid", graceInterval: 7 * 24 * 3600
    )

    // MARK: - Signature verification

    func testVerifiesGenuineToken() throws {
        let (priv, pub) = try makeKeyPair()
        let keys = try jwks(from: pub)
        let token = try signJWT(claims: [
            "sub": "u1", "email": "a@b.com",
            "exp": Date().addingTimeInterval(300).timeIntervalSince1970,
            "realm_access": ["roles": ["paid", "offline_access"]],
        ], priv: priv)

        let verified = try EntitlementJWT.verify(token, jwks: keys)
        XCTAssertEqual(verified.claims.email, "a@b.com")
        XCTAssertTrue(verified.claims.roles.contains("paid"))
    }

    func testRejectsTamperedPayload() throws {
        let (priv, pub) = try makeKeyPair()
        let keys = try jwks(from: pub)
        let token = try signJWT(claims: [
            "sub": "u1", "realm_access": ["roles": ["basic"]],
            "exp": Date().addingTimeInterval(300).timeIntervalSince1970,
        ], priv: priv)

        // Swap the payload segment for a forged "paid" one, keep the original signature.
        var parts = token.split(separator: ".").map(String.init)
        let forged = try JSONSerialization.data(withJSONObject: ["realm_access": ["roles": ["paid"]]])
        parts[1] = b64url(forged)
        let tampered = parts.joined(separator: ".")

        XCTAssertThrowsError(try EntitlementJWT.verify(tampered, jwks: keys)) { error in
            XCTAssertEqual(error as? EntitlementJWT.VerifyError, .signatureInvalid)
        }
    }

    func testRejectsWrongKey() throws {
        let (priv, _) = try makeKeyPair()
        let (_, otherPub) = try makeKeyPair()
        let keys = try jwks(from: otherPub)   // JWKS advertises a DIFFERENT key than signed with
        let token = try signJWT(claims: [
            "exp": Date().addingTimeInterval(300).timeIntervalSince1970,
            "realm_access": ["roles": ["paid"]],
        ], priv: priv)

        XCTAssertThrowsError(try EntitlementJWT.verify(token, jwks: keys))
    }

    // MARK: - Policy: role + expiry + grace

    func testEntitledWhenPaidRolePresent() async throws {
        let (priv, pub) = try makeKeyPair()
        let keys = try jwks(from: pub)
        let jwksData = try JSONEncoder().encode(keys)
        let token = try signJWT(claims: [
            "email": "paid@x.com",
            "exp": Date().addingTimeInterval(300).timeIntervalSince1970,
            "realm_access": ["roles": ["paid"]],
        ], priv: priv)

        let ent = Entitlement(config: config, defaults: freshDefaults(),
                              fetchJWKS: { _ in jwksData }, now: Date.init)
        let state = await ent.evaluate(accessToken: token)
        XCTAssertEqual(state, .entitled)
        XCTAssertTrue(state.isUnlocked)
    }

    func testNotEntitledWithoutPaidRole() async throws {
        let (priv, pub) = try makeKeyPair()
        let jwksData = try JSONEncoder().encode(try jwks(from: pub))
        let token = try signJWT(claims: [
            "exp": Date().addingTimeInterval(300).timeIntervalSince1970,
            "realm_access": ["roles": ["basic"]],
        ], priv: priv)

        let ent = Entitlement(config: config, defaults: freshDefaults(),
                              fetchJWKS: { _ in jwksData }, now: Date.init)
        let state = await ent.evaluate(accessToken: token)
        XCTAssertEqual(state, .notEntitled)
        XCTAssertFalse(state.isUnlocked)
    }

    func testGracePeriodKeepsUnlockedOfflineThenExpires() async throws {
        let (priv, pub) = try makeKeyPair()
        let jwksData = try JSONEncoder().encode(try jwks(from: pub))
        let token = try signJWT(claims: [
            "exp": Date().addingTimeInterval(300).timeIntervalSince1970,
            "realm_access": ["roles": ["paid"]],
        ], priv: priv)

        let defaults = freshDefaults()
        var clock = Date(timeIntervalSince1970: 1_000_000)

        // 1) Online: verified entitled, cache written.
        let online = Entitlement(config: config, defaults: defaults,
                                 fetchJWKS: { _ in jwksData }, now: { clock })
        let onlineState = await online.evaluate(accessToken: token)
        XCTAssertEqual(onlineState, .entitled)

        // 2) Offline (fetch throws) 1 day later → still unlocked via grace.
        clock = clock.addingTimeInterval(24 * 3600)
        let offline = Entitlement(config: config, defaults: defaults,
                                  fetchJWKS: { _ in throw URLError(.notConnectedToInternet) },
                                  now: { clock })
        let day1 = await offline.evaluate(accessToken: token)
        XCTAssertTrue(day1.isUnlocked, "within grace should stay unlocked")

        // 3) Offline 8 days later → grace expired → locked.
        clock = clock.addingTimeInterval(8 * 24 * 3600)
        XCTAssertEqual(offline.cachedState(), .signedOut)
    }

    func testSignedOutWhenNoCache() {
        let ent = Entitlement(config: config, defaults: freshDefaults())
        XCTAssertEqual(ent.cachedState(), .signedOut)
    }

    func testClearForgetsEntitlement() async throws {
        let (priv, pub) = try makeKeyPair()
        let jwksData = try JSONEncoder().encode(try jwks(from: pub))
        let token = try signJWT(claims: [
            "exp": Date().addingTimeInterval(300).timeIntervalSince1970,
            "realm_access": ["roles": ["paid"]],
        ], priv: priv)
        let defaults = freshDefaults()
        let ent = Entitlement(config: config, defaults: defaults, fetchJWKS: { _ in jwksData })
        _ = await ent.evaluate(accessToken: token)
        XCTAssertTrue(ent.cachedState().isUnlocked)
        ent.clear()
        XCTAssertEqual(ent.cachedState(), .signedOut)
    }

    // MARK: - Profile

    func testProfileDisplayNameAndInitials() {
        let full = UserProfile(name: "Bijal Sanghavi", email: "bijal@x.com", pictureURL: nil)
        XCTAssertEqual(full.displayName, "Bijal Sanghavi")
        XCTAssertEqual(full.initials, "BS")

        let emailOnly = UserProfile(name: nil, email: "bijal@trika.ai", pictureURL: nil)
        XCTAssertEqual(emailOnly.displayName, "bijal")
        XCTAssertEqual(emailOnly.initials, "B")

        let empty = UserProfile(name: nil, email: nil, pictureURL: nil)
        XCTAssertEqual(empty.displayName, "Account")
        XCTAssertEqual(empty.initials, "A")
    }

    func testEvaluateStoresProfile() async throws {
        let (priv, pub) = try makeKeyPair()
        let jwksData = try JSONEncoder().encode(try jwks(from: pub))
        let token = try signJWT(claims: [
            "email": "paid@x.com", "name": "Paid User",
            "exp": Date().addingTimeInterval(300).timeIntervalSince1970,
            "realm_access": ["roles": ["paid"]],
        ], priv: priv)
        let ent = Entitlement(config: config, defaults: freshDefaults(), fetchJWKS: { _ in jwksData })
        _ = await ent.evaluate(accessToken: token)
        XCTAssertEqual(ent.cachedProfile()?.name, "Paid User")
        XCTAssertEqual(ent.cachedProfile()?.email, "paid@x.com")
    }

    // MARK: - base64url

    func testBase64urlRoundTrip() throws {
        let raw = Data([0xFB, 0xFF, 0x00, 0x10, 0xAB, 0xCD, 0xEF])
        let encoded = b64url(raw)
        XCTAssertFalse(encoded.contains("="))
        XCTAssertFalse(encoded.contains("+"))
        XCTAssertFalse(encoded.contains("/"))
        XCTAssertEqual(try EntitlementJWT.base64urlDecode(encoded), raw)
    }
}
