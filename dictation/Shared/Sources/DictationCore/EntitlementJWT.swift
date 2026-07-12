import Foundation
import Security

/// Minimal, dependency-free verification of a Keycloak-issued JWT (RS256), using only the
/// system `Security` framework so it runs identically on macOS and iOS and adds no SwiftPM deps.
///
/// We verify the signature LOCALLY (against the realm's public keys from JWKS) rather than
/// trusting a plain boolean, so a locked user can't forge an "entitled" token by editing the
/// on-disk cache — the cached artifact is the signed JWT, and a forged one fails this check.
enum EntitlementJWT {

    enum VerifyError: Error, Equatable {
        case malformed            // not three base64url segments / bad JSON
        case unsupportedAlg       // header alg is not RS256
        case unknownKey           // no JWKS key matches the token's `kid`
        case badKey               // JWKS entry couldn't be turned into a public key
        case signatureInvalid     // signature did not verify against the key
    }

    /// A decoded, signature-verified token. Claims are only trusted once the signature checks out.
    struct Verified {
        let claims: Claims
    }

    /// The subset of Keycloak claims the gate + profile care about. Keycloak's default `profile` +
    /// `email` client scopes put `name`/`preferred_username`/`email` into the access token, so the
    /// signed-in identity comes straight off the token we already verify.
    struct Claims: Decodable {
        let sub: String?
        let email: String?
        let name: String?
        let givenName: String?
        let preferredUsername: String?
        let picture: String?
        let exp: TimeInterval?
        let iss: String?
        let azp: String?          // authorized party = the client id
        let realmAccess: RealmAccess?

        struct RealmAccess: Decodable { let roles: [String] }

        enum CodingKeys: String, CodingKey {
            case sub, email, name, picture, exp, iss, azp
            case givenName = "given_name"
            case preferredUsername = "preferred_username"
            case realmAccess = "realm_access"
        }

        var roles: [String] { realmAccess?.roles ?? [] }

        /// Best display name: full name, else the username, else the local part of the email.
        var displayName: String? {
            name ?? givenName ?? preferredUsername ?? email.map { String($0.prefix(while: { $0 != "@" })) }
        }
    }

    // MARK: - JWKS

    /// One RSA public key from the realm's JWKS endpoint (`/protocol/openid-connect/certs`).
    /// `Codable` (not just `Decodable`) so tests can synthesize a JWKS from a generated key.
    struct JWK: Codable {
        let kty: String
        let kid: String
        let n: String    // modulus, base64url
        let e: String    // exponent, base64url
        let alg: String?
        let use: String?
    }

    struct JWKS: Codable { let keys: [JWK] }

    // MARK: - Verify

    /// Verify a token's RS256 signature against a JWKS and return its claims. Does NOT check
    /// expiry or roles — that's the caller's policy (see `Entitlement`).
    static func verify(_ token: String, jwks: JWKS) throws -> Verified {
        let parts = token.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 3 else { throw VerifyError.malformed }

        let headerData = try base64urlDecode(String(parts[0]))
        let payloadData = try base64urlDecode(String(parts[1]))
        let signature = try base64urlDecode(String(parts[2]))

        struct Header: Decodable { let alg: String; let kid: String? }
        guard let header = try? JSONDecoder().decode(Header.self, from: headerData) else {
            throw VerifyError.malformed
        }
        guard header.alg == "RS256" else { throw VerifyError.unsupportedAlg }

        // Match the signing key by kid when present; otherwise try every RSA key.
        let candidates: [JWK]
        if let kid = header.kid {
            candidates = jwks.keys.filter { $0.kid == kid }
            guard !candidates.isEmpty else { throw VerifyError.unknownKey }
        } else {
            candidates = jwks.keys.filter { $0.kty == "RSA" }
        }

        // Signed input is the ASCII of "header.payload" (the first two segments, verbatim).
        let signedInput = Data((String(parts[0]) + "." + String(parts[1])).utf8)

        for jwk in candidates where jwk.kty == "RSA" {
            guard let key = try? rsaPublicKey(n: jwk.n, e: jwk.e) else { continue }
            if verifyRS256(publicKey: key, signedInput: signedInput, signature: signature) {
                guard let claims = try? JSONDecoder().decode(Claims.self, from: payloadData) else {
                    throw VerifyError.malformed
                }
                return Verified(claims: claims)
            }
        }
        // A candidate existed but none verified (or none were usable).
        throw candidates.isEmpty ? VerifyError.badKey : VerifyError.signatureInvalid
    }

    /// Decode claims WITHOUT verifying — only for reading non-security-critical hints (e.g. showing
    /// the signed-in email in UI). Never gate on these.
    static func unverifiedClaims(_ token: String) -> Claims? {
        let parts = token.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 3, let data = try? base64urlDecode(String(parts[1])) else { return nil }
        return try? JSONDecoder().decode(Claims.self, from: data)
    }

    // MARK: - Crypto helpers

    private static func verifyRS256(publicKey: SecKey, signedInput: Data, signature: Data) -> Bool {
        SecKeyVerifySignature(
            publicKey,
            .rsaSignatureMessagePKCS1v15SHA256,
            signedInput as CFData,
            signature as CFData,
            nil
        )
    }

    /// Build a `SecKey` RSA public key from JWKS (n, e). `SecKeyCreateWithData` for an RSA public
    /// key expects PKCS#1 DER — `RSAPublicKey ::= SEQUENCE { modulus INTEGER, publicExponent INTEGER }`
    /// — so we DER-encode the two big-endian integers ourselves.
    static func rsaPublicKey(n nB64: String, e eB64: String) throws -> SecKey {
        let n = try base64urlDecode(nB64)
        let e = try base64urlDecode(eB64)
        let der = derRSAPublicKey(modulus: n, exponent: e)
        let attrs: [CFString: Any] = [
            kSecAttrKeyType: kSecAttrKeyTypeRSA,
            kSecAttrKeyClass: kSecAttrKeyClassPublic,
        ]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateWithData(der as CFData, attrs as CFDictionary, &error) else {
            throw VerifyError.badKey
        }
        return key
    }

    /// DER-encode `SEQUENCE { INTEGER(modulus), INTEGER(exponent) }`.
    private static func derRSAPublicKey(modulus: Data, exponent: Data) -> Data {
        var body = Data()
        body.append(derInteger(modulus))
        body.append(derInteger(exponent))
        return derTLV(tag: 0x30, value: body)   // 0x30 = SEQUENCE
    }

    /// DER INTEGER from an unsigned big-endian magnitude: strip leading zero bytes, then prepend a
    /// single 0x00 if the top bit is set (so it isn't read as negative).
    private static func derInteger(_ magnitude: Data) -> Data {
        var bytes = Array(magnitude)
        while bytes.count > 1 && bytes.first == 0x00 { bytes.removeFirst() }
        if let first = bytes.first, first & 0x80 != 0 { bytes.insert(0x00, at: 0) }
        return derTLV(tag: 0x02, value: Data(bytes))   // 0x02 = INTEGER
    }

    /// Tag-length-value with DER definite-length encoding.
    private static func derTLV(tag: UInt8, value: Data) -> Data {
        var out = Data([tag])
        let len = value.count
        if len < 0x80 {
            out.append(UInt8(len))
        } else {
            var lenBytes: [UInt8] = []
            var l = len
            while l > 0 { lenBytes.insert(UInt8(l & 0xFF), at: 0); l >>= 8 }
            out.append(UInt8(0x80 | lenBytes.count))
            out.append(contentsOf: lenBytes)
        }
        out.append(value)
        return out
    }

    /// base64url (no padding) → Data.
    static func base64urlDecode(_ s: String) throws -> Data {
        var b64 = s.replacingOccurrences(of: "-", with: "+")
                   .replacingOccurrences(of: "_", with: "/")
        let pad = (4 - b64.count % 4) % 4
        b64 += String(repeating: "=", count: pad)
        guard let data = Data(base64Encoded: b64) else { throw VerifyError.malformed }
        return data
    }
}
