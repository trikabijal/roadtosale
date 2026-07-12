import UIKit
import DictationCoreBase
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

        postEnablementSignal()   // onboarding: tell the app we're enabled + whether we have Full Access

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
        postEnablementSignal()
        // Start continuously reflecting the shared App-Group variables into the UI. iOS recreated us and
        // wiped local memory, but that's fine — the poll re-reads the truth (`capturing`, `pendingText`)
        // from scratch, so the wave/button/label reappear in exactly the right state and any finished
        // transcript is picked up. There is nothing to "sync": the UI is a pure function of the variables.
        viewModel.startReflecting()
    }

    /// Tell the container app (onboarding) that this keyboard is enabled, and whether it has Full Access
    /// — both facts in one Darwin post, which crosses processes without needing Full Access itself.
    /// `hasFullAccess` is UIInputViewController's own reliable answer.
    private func postEnablementSignal() {
        let fa = hasFullAccess
        // PERSISTED flag (survives the Settings round-trip; the write needs Full Access, so it only
        // lands when granted). iOS loads the keyboard when Full Access is toggled, so this is written
        // there and the app detects it on foreground — no need to switch to the keyboard.
        if fa { DictationHandoff.markKeyboardFullAccess() }
        // Live doorbell too (instant while the app is already foreground).
        DictationHandoff.post(fa ? DictationHandoff.keyboardEnabledFullAccess
                                 : DictationHandoff.keyboardEnabledLimited)
    }

    public override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        // Leaving the screen — stop polling. The next appear re-reads the shared variables from scratch.
        viewModel.stopReflecting()
    }

    /// Open a URL from inside a keyboard extension by walking the responder chain (starting PAST self)
    /// and calling `openURL:` on the first responder that answers — UIApplication, up the chain. The
    /// standard voice-keyboard technique to launch the container app.
    private func openURLFromKeyboard(_ url: URL) {
        // On iOS 26 the deprecated openURL: still RESPONDS but no-ops; the modern
        // open(_:options:completionHandler:) is what actually launches. The compiler blocks calling
        // UIApplication.open directly from an extension, so invoke it through its IMP.
        let sel = NSSelectorFromString("openURL:options:completionHandler:")
        var responder: UIResponder? = self.next   // skip self
        while let r = responder {
            // Target UIApplication by concrete class: other responders (e.g. _UIScreenBasedWindowScene)
            // also answer the modern selector but aren't the app. UIApplication.open is unavailable to
            // extensions at compile time, so invoke it through its IMP.
            if let app = r as? UIApplication {
                typealias OpenIMP = @convention(c)
                    (NSObject, Selector, NSURL, NSDictionary, (@convention(block) (Bool) -> Void)?) -> Void
                let imp = app.method(for: sel)
                let fn = unsafeBitCast(imp, to: OpenIMP.self)
                fn(app, sel, url as NSURL, NSDictionary(), { ok in
                    if !ok { KBLog.error("openURL returned false for \(url.absoluteString)") }
                })
                return
            }
            responder = r.next
        }
        KBLog.error("could not launch container app: no UIApplication in responder chain")
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
