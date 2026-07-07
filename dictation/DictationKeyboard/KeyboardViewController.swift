import UIKit
import SwiftUI

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
        let modern = NSSelectorFromString("openURL:options:completionHandler:")
        let legacy = NSSelectorFromString("openURL:")
        var responder: UIResponder? = self.next   // skip self
        while let r = responder {
            if r.responds(to: modern) {
                typealias OpenIMP = @convention(c) (NSObject, Selector, NSURL, NSDictionary, Any?) -> Void
                let imp = r.method(for: modern)
                let fn = unsafeBitCast(imp, to: OpenIMP.self)
                fn(r, modern, url as NSURL, NSDictionary(), nil)
                return
            }
            if r.responds(to: legacy) {
                _ = r.perform(legacy, with: url)
                return
            }
            responder = r.next
        }
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
