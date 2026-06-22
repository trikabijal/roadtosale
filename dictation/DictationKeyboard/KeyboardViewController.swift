import UIKit

// ⚠️ DIAGNOSTIC STUB — temporarily replaces the real keyboard to isolate whether the heavy
// DictationCore/WhisperKit dependency tree is what stops iOS registering the extension.
// The real implementation is in git; restore with `git checkout dictation/DictationKeyboard`.
public final class KeyboardViewController: UIInputViewController {

    public override func viewDidLoad() {
        super.viewDidLoad()

        let label = UILabel()
        label.text = "Just Talk (test build)"
        label.textAlignment = .center
        label.translatesAutoresizingMaskIntoConstraints = false

        let next = UIButton(type: .system)
        next.setTitle("🌐 Next Keyboard", for: .normal)
        next.translatesAutoresizingMaskIntoConstraints = false
        next.addTarget(self, action: #selector(handleInputModeList(from:with:)), for: .allTouchEvents)

        view.addSubview(label)
        view.addSubview(next)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            label.topAnchor.constraint(equalTo: view.topAnchor, constant: 24),
            next.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            next.topAnchor.constraint(equalTo: label.bottomAnchor, constant: 16),
        ])
    }

    public override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        view.heightAnchor.constraint(equalToConstant: 200).isActive = true
    }
}
