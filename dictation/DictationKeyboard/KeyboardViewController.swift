import UIKit
import SwiftUI

/// Type-safe handle for UIApplication's `open(_:options:completionHandler:)` — the compiler blocks
/// calling it directly in an extension, but a responder cast to this @objc protocol (whose selector
/// matches) invokes it cleanly, no unsafe bit-casting.
@objc private protocol AppLauncher {
    @objc(openURL:options:completionHandler:)
    func openURL(_ url: URL, options: [AnyHashable: Any], completionHandler: ((Bool) -> Void)?)
}

/// The extension's principal class. Hosts `KeyboardView` in a `UIHostingController`
/// and wires the text-insertion callback to `textDocumentProxy`.
///
/// Note: the keyboard needs Full Access (already declared in Info.plist via
/// `RequestsOpenAccess = true`) so that `SFSpeechRecognizer` and the App Group
/// shared database are reachable. The container app (`DictationContainerApp`) should
/// prompt for microphone and speech recognition permissions before first use.
public final class KeyboardViewController: UIInputViewController {

    private var viewModel: KeyboardViewModel!
    private var heightConstraint: NSLayoutConstraint?

    public override func viewDidLoad() {
        super.viewDidLoad()
        KBLog.log("=== keyboard viewDidLoad, hasFullAccess=\(hasFullAccess) ===")
        KBLog.probe()

        viewModel = KeyboardViewModel()
        // Route final text into whatever text field the user has focused.
        viewModel.insertText = { [weak self] text in
            self?.textDocumentProxy.insertText(text)
        }
        // Open the container app for a Flow Session (the keyboard can't use the mic itself).
        // extensionContext.open doesn't work from a keyboard extension, so walk the responder chain to
        // find UIApplication and call openURL: on it (the standard keyboard URL-open workaround).
        viewModel.openApp = { [weak self] url in
            self?.openURLFromKeyboard(url)
        }

        let rootView = KeyboardView(
            viewModel: viewModel,
            onNextKeyboard: { [weak self] in
                self?.advanceToNextInputMode()
            }
        )

        let host = UIHostingController(rootView: rootView)
        host.view.translatesAutoresizingMaskIntoConstraints = false
        host.view.backgroundColor = .clear

        addChild(host)
        view.addSubview(host.view)
        host.didMove(toParent: self)

        NSLayoutConstraint.activate([
            host.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            host.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            host.view.topAnchor.constraint(equalTo: view.topAnchor),
            host.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])

        // Pin the keyboard height. The constraint is retained so it can be updated
        // for different device orientations without creating duplicate constraints.
        let h = view.heightAnchor.constraint(equalToConstant: 216)
        h.priority = .required
        h.isActive = true
        heightConstraint = h
    }

    public override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        // Returning from a Flow Session: pull any transcript the container app left and insert it.
        viewModel.checkForHandoff()
    }

    /// Open a URL from inside a keyboard extension by walking the responder chain (starting PAST self)
    /// and calling `openURL:` on the first responder that answers — UIApplication, up the chain. The
    /// standard voice-keyboard technique to launch the container app.
    private func openURLFromKeyboard(_ url: URL) {
        // On iOS 26 the deprecated openURL: still RESPONDS but no-ops; the modern
        // open(_:options:completionHandler:) is what actually launches. The compiler blocks calling
        // UIApplication.open directly from an extension, so invoke it through its IMP.
        KBLog.log("openURLFromKeyboard START url=\(url.absoluteString)")
        let sel = NSSelectorFromString("openURL:options:completionHandler:")
        var responder: UIResponder? = self.next   // skip self
        while let r = responder {
            // Target UIApplication by concrete class (`as? AppLauncher` needs formal conformance,
            // which it lacks; and other responders like _UIScreenBasedWindowScene also answer the
            // modern selector but aren't the app). UIApplication.open is unavailable to extensions at
            // compile time, so invoke it through its IMP.
            if let app = r as? UIApplication {
                KBLog.log("found UIApplication — invoking modern open via IMP")
                typealias OpenIMP = @convention(c)
                    (NSObject, Selector, NSURL, NSDictionary, (@convention(block) (Bool) -> Void)?) -> Void
                let imp = app.method(for: sel)
                let fn = unsafeBitCast(imp, to: OpenIMP.self)
                fn(app, sel, url as NSURL, NSDictionary(), { [weak self] ok in
                    KBLog.log("open completion ok=\(ok)")
                    DispatchQueue.main.async { self?.viewModel.setDiagnostic("open: \(ok ? "TRUE" : "FALSE")") }
                })
                KBLog.log("open call returned synchronously")
                viewModel.setDiagnostic("called open…")
                return
            }
            responder = r.next
        }
        KBLog.log("UIApplication NOT found in chain")
        viewModel.setDiagnostic("no UIApplication in chain")
    }

    public override func viewWillTransition(
        to size: CGSize,
        with coordinator: UIViewControllerTransitionCoordinator
    ) {
        super.viewWillTransition(to: size, with: coordinator)
        // Landscape is wider but the same interaction, so keep 216 pt tall.
        heightConstraint?.constant = 216
    }
}
