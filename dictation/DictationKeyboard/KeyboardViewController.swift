import UIKit
import SwiftUI

public final class KeyboardViewController: UIInputViewController {

    private var viewModel = KeyboardViewModel()
    private var hostingController: UIHostingController<KeyboardView>?

    public override func viewDidLoad() {
        super.viewDidLoad()

        // Wire text insertion callbacks back through textDocumentProxy
        viewModel.insertTextCallback = { [weak self] text in
            self?.textDocumentProxy.insertText(text)
        }
        viewModel.deleteBackCallback = { [weak self] in
            self?.textDocumentProxy.deleteBackward()
        }

        let keyboardView = KeyboardView(viewModel: viewModel) {
            self.advanceToNextInputMode()
        }

        let host = UIHostingController(rootView: keyboardView)
        hostingController = host

        addChild(host)
        view.addSubview(host.view)
        host.didMove(toParent: self)

        host.view.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            host.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            host.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            host.view.topAnchor.constraint(equalTo: view.topAnchor),
            host.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
    }

    public override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        // Preferred height — standard iOS keyboard is ~260pt portrait
        view.heightAnchor.constraint(equalToConstant: 260).isActive = true
    }
}
