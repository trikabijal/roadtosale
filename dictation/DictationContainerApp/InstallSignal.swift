import UIKit
import os
import DictationCoreBase

private let log = Logger(subsystem: DictationHandoff.logSubsystem, category: "InstallSignal")

/// Records that someone set up the app, so we can track installs (the reason for the sign-in step).
///
/// Full "Continue with Google" needs a Google OAuth client id + the GoogleSignIn SDK; until that's
/// configured (`installPingURL` / a client id below), this fires an anonymous install ping keyed on
/// the vendor identifier so we still learn a device set the app up — no personal data. Wire the real
/// OAuth later and call `report(email:)` with the signed-in account.
enum InstallSignal {

    /// Optional endpoint that receives install pings (a Google Apps Script web app, etc.). Empty = no-op.
    /// Set at build time or via Info.plist `JustTalkInstallPingURL`, mirroring the macOS install ping.
    static var installPingURL: String {
        (Bundle.main.object(forInfoDictionaryKey: "JustTalkInstallPingURL") as? String) ?? ""
    }

    /// Called from the sign-in step. Records the install signal. When a Google OAuth client id is
    /// configured this is where the GoogleSignIn flow is launched; for now it pings anonymously.
    static func signInWithGoogle() {
        // TODO: wire GoogleSignIn SDK (needs an OAuth client id + a reversed-client-id URL scheme).
        //       On success call report(email:) with the account so installs are attributable.
        report(email: nil)
    }

    /// POST the install signal. Only the fields the user is aware of (their email, when signed in) plus
    /// an anonymous device id — never audio or transcripts.
    static func report(email: String?) {
        guard let urlString = normalizedURL(installPingURL), let url = URL(string: urlString) else {
            log.notice("install ping skipped — no JustTalkInstallPingURL configured")
            return
        }
        let idfv = UIDevice.current.identifierForVendor?.uuidString ?? "unknown"
        var payload: [String: String] = ["platform": "ios", "device": idfv]
        if let email, !email.isEmpty { payload["email"] = email }

        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try? JSONSerialization.data(withJSONObject: payload)
        URLSession.shared.dataTask(with: req) { _, resp, err in
            if let err { log.error("install ping failed: \(err.localizedDescription, privacy: .public)") }
            else { log.notice("install ping sent (\((resp as? HTTPURLResponse)?.statusCode ?? 0))") }
        }.resume()
    }

    private static func normalizedURL(_ s: String) -> String? {
        let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }
}
