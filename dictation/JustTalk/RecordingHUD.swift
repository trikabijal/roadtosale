import AppKit
import SwiftUI

enum RecordingHUDPhase {
    case recording, processing, failed, done
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
    // Correction action, set when `phase == .done` (post-insert "mark wrong").
    var onMarkWrong: (() -> Void)?
}

/// A small always-on-top floating panel shown near the bottom of the screen while
/// dictating, so the user always knows the app is listening / working. Non-activating
/// and click-through — it never steals focus from the app you're dictating into.
@MainActor
final class RecordingHUD {
    private let model = RecordingHUDModel()
    private var panel: NSPanel?
    /// Tracks on-screen state so the open/close cues fire on true visibility transitions only —
    /// not on phase changes (e.g. recording→processing) while the pill stays up.
    private var isVisible = false
    /// Fired when the pill appears (hidden→visible) and disappears (visible→hidden). AppState
    /// wires these to the open/close sounds, mirroring Wispr Flow's HUD chimes.
    var onAppear: (() -> Void)?
    var onDisappear: (() -> Void)?

    func show(phase: RecordingHUDPhase, label: String) {
        model.phase = phase
        model.label = label
        model.level = 0
        model.previewText = ""
        model.lowInput = false
        present()
    }

    /// Bring the panel on screen, positioning it, and fire `onAppear` only on a hidden→visible edge.
    private func present() {
        let panel = ensurePanel()
        position(panel)
        panel.orderFrontRegardless()
        if !isVisible {
            isVisible = true
            onAppear?()
        }
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
        if isVisible {
            isVisible = false
            onDisappear?()
        }
    }

    /// After a successful insert, show a brief confirmation with a "mark wrong" button — the
    /// correction affordance lives here in the HUD (reachable no matter which app is focused),
    /// replacing the global ⌘⇧Z shortcut that collided with the foreground app's redo.
    func showCorrectionPrompt(onMarkWrong: @escaping () -> Void) {
        model.phase = .done
        model.label = "Inserted"
        model.level = 0
        model.previewText = ""
        model.lowInput = false
        model.onMarkWrong = onMarkWrong
        present()
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
        present()
    }

    // MARK: - Panel

    private func ensurePanel() -> NSPanel {
        if let panel { return panel }
        let p = NSPanel(
            // Wide enough that a full-length recording pill (content-hugging, capped text) never
            // clips; the pill itself hugs its content and centers within this panel.
            contentRect: NSRect(x: 0, y: 0, width: 480, height: 60),
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
        guard model.phase == .recording else { return Theme.Palette.warning }
        return model.lowInput ? Theme.Palette.warning : Theme.Palette.recording
    }

    private var micIcon: String {
        if model.phase == .recording { return model.lowInput ? "mic.slash.fill" : "mic.fill" }
        return "waveform"
    }

    var body: some View {
        Group {
            switch model.phase {
            case .failed: failedContent
            case .done:   doneContent
            default:      activeContent
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        // Dark frosted capsule: material for the blur, a darker raised surface layered over it,
        // and a strong hairline border — all from the design tokens. The capsule HUGS its content
        // (no fixed width) per the design, then centers within the panel.
        .background {
            Capsule()
                .fill(.ultraThinMaterial)
                .overlay(Capsule().fill(Theme.Palette.surfaceRaised.opacity(0.6)))
        }
        .overlay(Capsule().strokeBorder(Theme.Palette.strokeStrong))
        .fixedSize(horizontal: true, vertical: false)   // hug content width (texts self-cap below)
        // Smoothly grow/shrink the pill as the transcript streams in (until the text cap), and on
        // state changes — matches the design's growing HUD.
        .animation(.easeOut(duration: 0.15), value: displayText)
        .animation(.easeOut(duration: 0.15), value: model.phase)
        .frame(maxWidth: .infinity)                      // center the content-sized pill in the panel
    }

    private var activeContent: some View {
        HStack(spacing: 10) {
            Image(systemName: micIcon)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(micColor)

            LevelMeter(
                level: model.level,
                active: model.phase == .recording,
                color: model.phase == .recording ? micColor : Theme.Palette.textTertiary
            )
            .frame(width: 78, height: 18)

            // Live partial transcript (truncates from the head so the latest words show);
            // replaced by the low-input warning when the mic is too quiet.
            Text(displayText)
                .font(.callout)
                .foregroundStyle(model.phase == .recording && model.lowInput ? Theme.Palette.warning : Theme.Palette.textPrimary)
                .lineLimit(1)
                .truncationMode(.head)
                .frame(maxWidth: 300, alignment: .leading)   // grows with the transcript; caps + truncates long
        }
    }

    private var doneContent: some View {
        HStack(spacing: 10) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Theme.Palette.success)

            Text("Inserted")
                .font(.callout)
                .foregroundStyle(Theme.Palette.textPrimary)

            Button { model.onMarkWrong?() } label: {
                Label("Mark wrong", systemImage: "xmark")
            }
            .buttonStyle(.plain)
            .controlSize(.small)
            .foregroundStyle(Theme.Palette.textSecondary)
        }
    }

    private var failedContent: some View {
        HStack(spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Theme.Palette.caution)

            Text(model.label)
                .font(.callout)
                .foregroundStyle(Theme.Palette.textPrimary)
                .lineLimit(1)
                .frame(maxWidth: 220, alignment: .leading)

            Button("Retry") { model.onRetry?() }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
                .tint(Theme.Palette.accent)

            Button { model.onDismiss?() } label: {
                Image(systemName: "xmark")
            }
            .buttonStyle(.plain)
            .foregroundStyle(Theme.Palette.textSecondary)
        }
    }
}

/// A slim, LIVING waveform: thin vertical bars that continuously animate while recording, with
/// their amplitude scaling to the live mic level (center bars taller). When not recording it shows
/// a flat idle state in a muted color. Honors Reduce Motion (falls back to a static, level-driven
/// meter that eases on level changes instead of the continuous wave).
private struct LevelMeter: View {
    let level: Float
    let active: Bool
    var color: Color = Theme.Palette.recording

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private let bars = 13
    private let maxBar: CGFloat = 16

    var body: some View {
        if active && !reduceMotion {
            TimelineView(.animation) { timeline in
                bar { i in waveHeight(i, t: timeline.date.timeIntervalSinceReferenceDate) }
            }
        } else {
            bar { i in staticHeight(i) }
                .animation(.easeOut(duration: 0.12), value: level)
        }
    }

    private func bar(_ height: @escaping (Int) -> CGFloat) -> some View {
        HStack(spacing: 3) {
            ForEach(0..<bars, id: \.self) { i in
                Capsule()
                    .fill(active ? color : Theme.Palette.textTertiary)
                    .frame(width: 2.5, height: height(i))
            }
        }
    }

    /// Center-weight: 1.0 at the middle bar, tapering toward the edges.
    private func weight(_ index: Int) -> CGFloat {
        let center = Double(bars - 1) / 2
        let distance = abs(Double(index) - center) / center
        return CGFloat(1.0 - 0.6 * distance)
    }

    /// Live amplitude from mic RMS (~0…0.3 typical speech) → 0…1, with a small floor so the
    /// waveform stays visibly alive even between words.
    private var amplitude: CGFloat { 0.28 + 0.72 * min(1, CGFloat(level) / 0.3) }

    /// Continuous animated bar — a travelling sine per bar, amplitude-scaled by the mic level.
    private func waveHeight(_ index: Int, t: Double) -> CGFloat {
        let wave = 0.5 + 0.5 * sin(t * 7 + Double(index) * 0.7)
        return max(3, maxBar * amplitude * weight(index) * CGFloat(wave))
    }

    /// Reduce-Motion / idle: heights track the mic level only (no continuous animation).
    private func staticHeight(_ index: Int) -> CGFloat {
        guard active else { return 4 }
        return max(3, maxBar * amplitude * weight(index))
    }
}
