import AppKit
import SwiftUI

enum RecordingHUDPhase {
    case recording, processing, failed
}

/// Observable backing for the floating HUD.
@MainActor
final class RecordingHUDModel: ObservableObject {
    @Published var level: Float = 0          // 0…~1 mic RMS
    @Published var phase: RecordingHUDPhase = .recording
    @Published var label: String = "Listening…"
    @Published var previewText: String = ""  // live partial transcript while recording
    @Published var lowInput: Bool = false    // mic level too low to transcribe reliably
    // Failure actions, set when `phase == .failed`.
    var onRetry: (() -> Void)?
    var onDismiss: (() -> Void)?
}

/// A small always-on-top floating panel shown near the bottom of the screen while
/// dictating, so the user always knows the app is listening / working. Non-activating
/// and click-through — it never steals focus from the app you're dictating into.
@MainActor
final class RecordingHUD {
    private let model = RecordingHUDModel()
    private var panel: NSPanel?

    func show(phase: RecordingHUDPhase, label: String) {
        model.phase = phase
        model.label = label
        model.level = 0
        model.previewText = ""
        model.lowInput = false
        let panel = ensurePanel()
        position(panel)
        panel.orderFrontRegardless()
    }

    func setPhase(_ phase: RecordingHUDPhase, label: String) {
        model.phase = phase
        model.label = label
        model.level = 0
        model.previewText = ""
        model.lowInput = false
    }

    func update(level: Float) {
        model.level = level
    }

    /// Show/clear the "I can barely hear you" warning while recording.
    func setLowInput(_ low: Bool) {
        if model.lowInput != low { model.lowInput = low }
    }

    func update(previewText: String) {
        model.previewText = previewText
    }

    func hide() {
        panel?.orderOut(nil)
    }

    /// Show a persistent failure state with Retry / dismiss actions. Does NOT auto-hide —
    /// the user decides whether to retry the preserved audio or discard it.
    func showFailed(message: String, onRetry: @escaping () -> Void, onDismiss: @escaping () -> Void) {
        model.phase = .failed
        model.label = message
        model.level = 0
        model.previewText = ""
        model.onRetry = onRetry
        model.onDismiss = onDismiss
        let panel = ensurePanel()
        position(panel)
        panel.orderFrontRegardless()
    }

    // MARK: - Panel

    private func ensurePanel() -> NSPanel {
        if let panel { return panel }
        let p = NSPanel(
            contentRect: NSRect(x: 0, y: 0, width: 380, height: 60),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        p.isFloatingPanel = true
        // .screenSaver (1000) floats the HUD above full-screen apps too — `.statusBar` (25)
        // sits *below* a full-screen window, so the HUD was hidden behind e.g. full-screen
        // Terminal. `.canJoinAllSpaces` (below) makes it follow onto the full-screen Space.
        p.level = .screenSaver
        p.backgroundColor = .clear
        p.isOpaque = false
        p.hasShadow = true
        p.ignoresMouseEvents = false          // let the user grab it…
        p.isMovableByWindowBackground = true  // …and drag anywhere on the pill to reposition
        p.hidesOnDeactivate = false
        p.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary]
        p.contentView = NSHostingView(rootView: HUDContentView(model: model))
        // Remember wherever the user drags it, so it stays put across future recordings.
        NotificationCenter.default.addObserver(
            forName: NSWindow.didMoveNotification, object: p, queue: .main
        ) { [weak self] note in
            guard let window = note.object as? NSWindow else { return }
            self?.saveOrigin(window.frame.origin)
        }
        panel = p
        return p
    }

    private func position(_ panel: NSPanel) {
        let size = panel.frame.size
        // Respect a position the user has dragged it to (if still on a connected display);
        // otherwise default to bottom-centre.
        if let origin = savedOrigin(), RecordingHUD.isOnScreen(origin, size: size) {
            panel.setFrameOrigin(origin)
            return
        }
        guard let screen = NSScreen.main ?? NSScreen.screens.first else { return }
        let visible = screen.visibleFrame
        panel.setFrameOrigin(NSPoint(
            x: visible.midX - size.width / 2,
            y: visible.minY + 120
        ))
    }

    // MARK: - Position persistence

    nonisolated private static let originKey = "hudOrigin"

    nonisolated private func saveOrigin(_ p: NSPoint) {
        UserDefaults.standard.set(NSStringFromPoint(p), forKey: RecordingHUD.originKey)
    }

    nonisolated private func savedOrigin() -> NSPoint? {
        guard let s = UserDefaults.standard.string(forKey: RecordingHUD.originKey) else { return nil }
        return NSPointFromString(s)
    }

    /// True only if the saved origin lands the panel's CENTER within a screen's visible area —
    /// so a position saved on a since-disconnected/rearranged monitor, or dragged mostly
    /// off-screen, falls back to the default bottom-centre instead of hiding the HUD.
    nonisolated private static func isOnScreen(_ origin: NSPoint, size: NSSize) -> Bool {
        let center = NSPoint(x: origin.x + size.width / 2, y: origin.y + size.height / 2)
        return NSScreen.screens.contains { $0.visibleFrame.contains(center) }
    }
}

// MARK: - HUD content

private struct HUDContentView: View {
    @ObservedObject var model: RecordingHUDModel

    private var displayText: String {
        if model.phase == .recording && model.lowInput {
            return "Speak up — I can barely hear you"
        }
        return (model.phase == .recording && !model.previewText.isEmpty) ? model.previewText : model.label
    }

    private var micColor: Color {
        guard model.phase == .recording else { return .orange }
        return model.lowInput ? .orange : .red
    }

    private var micIcon: String {
        if model.phase == .recording { return model.lowInput ? "mic.slash.fill" : "mic.fill" }
        return "waveform"
    }

    var body: some View {
        Group {
            if model.phase == .failed {
                failedContent
            } else {
                activeContent
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .frame(width: 360)
        .background(.ultraThinMaterial, in: Capsule())
        .overlay(Capsule().strokeBorder(.white.opacity(0.08)))
    }

    private var activeContent: some View {
        HStack(spacing: 10) {
            Image(systemName: micIcon)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(micColor)

            LevelMeter(level: model.level, active: model.phase == .recording)
                .frame(width: 60, height: 18)

            // Live partial transcript (truncates from the head so the latest words show);
            // replaced by the low-input warning when the mic is too quiet.
            Text(displayText)
                .font(.callout)
                .foregroundStyle(model.phase == .recording && model.lowInput ? Color.orange : Color.primary)
                .lineLimit(1)
                .truncationMode(.head)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var failedContent: some View {
        HStack(spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(.yellow)

            Text(model.label)
                .font(.callout)
                .foregroundStyle(.primary)
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)

            Button("Retry") { model.onRetry?() }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)

            Button { model.onDismiss?() } label: {
                Image(systemName: "xmark")
            }
            .buttonStyle(.plain)
            .foregroundStyle(.secondary)
        }
    }
}

/// Seven bars whose height tracks the live mic level. When processing (not recording)
/// it shows a flat idle state.
private struct LevelMeter: View {
    let level: Float
    let active: Bool

    private let bars = 7

    var body: some View {
        HStack(spacing: 3) {
            ForEach(0..<bars, id: \.self) { i in
                Capsule()
                    .fill(active ? Color.red.opacity(0.85) : Color.secondary.opacity(0.5))
                    .frame(height: barHeight(i))
            }
        }
    }

    private func barHeight(_ index: Int) -> CGFloat {
        guard active else { return 4 }
        // Normalize RMS (~0…0.3 typical speech) to 0…1, emphasize center bars.
        let normalized = min(1, CGFloat(level) / 0.3)
        let center = Double(bars - 1) / 2
        let distance = abs(Double(index) - center) / center      // 0 center … 1 edges
        let weight = 1.0 - 0.6 * distance
        return max(4, 20 * normalized * weight)
    }
}
