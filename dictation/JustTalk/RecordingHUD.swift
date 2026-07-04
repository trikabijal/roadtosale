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
    @Published var previewText: String = ""  // live transcript while recording (the pill text) — TARGET
    // How many characters of the live text are currently revealed. A reveal driver eases
    // this toward the target length a few chars per frame, so the pill grows/shrinks GRADUALLY no
    // matter how chunky the STT updates land (the "jumps" fix — PRD 0008).
    @Published var revealedCount: Int = 0
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

    /// Per-frame driver that eases `revealedCount` toward the live text length — the "gradual, not
    /// jumps" fix. Runs only while the live pill is up.
    private var revealTask: Task<Void, Never>?

    private func liveLength() -> Int { model.previewText.count }

    private func ensureRevealRunning() {
        guard revealTask == nil else { return }
        revealTask = Task { @MainActor [weak self] in
            var exact = 0.0
            // CONSTANT reveal speed (~1 char/frame ≈ 60 chars/s, roughly speech rate) so the pill
            // types continuously — NOT proportional, which burst-typed each phrase then paused (the
            // "5 jumps"). Only if we've fallen far behind (fast speech) do we speed up to catch up.
            let steadyRate = 1.0
            let catchUpBacklog = 50.0
            while !Task.isCancelled {
                if let self {
                    let target = Double(self.liveLength())
                    if exact < target {
                        let gap = target - exact
                        let step = gap > catchUpBacklog ? gap / 12.0 : steadyRate
                        exact = Swift.min(target, exact + step)
                    } else if exact > target {
                        // Text shrank (rare with confirmed-only) — ease back gradually, not a snap.
                        exact = Swift.max(target, exact - 2.0)
                    }
                    self.model.revealedCount = Int(exact)
                }
                try? await Task.sleep(for: .milliseconds(16))   // ~60fps
            }
        }
    }

    private func stopReveal() {
        revealTask?.cancel()
        revealTask = nil
        model.revealedCount = 0
    }

    func show(phase: RecordingHUDPhase, label: String) {
        model.phase = phase
        model.label = label
        model.level = 0
        model.previewText = ""
        model.lowInput = false
        model.revealedCount = 0
        if phase == .recording { ensureRevealRunning() } else { stopReveal() }
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
        model.revealedCount = 0
        if phase == .recording { ensureRevealRunning() } else { stopReveal() }
    }

    func update(level: Float) {
        model.level = level
    }

    /// Show/clear the "I can barely hear you" warning while recording.
    func setLowInput(_ low: Bool) {
        if model.lowInput != low { model.lowInput = low }
    }

    /// Set the live pill text (streaming confirmed text, or the per-segment fallback preview). The
    /// reveal driver eases the shown length toward it so growth is gradual.
    func update(previewText: String) {
        model.previewText = previewText
        ensureRevealRunning()
    }

    func hide() {
        stopReveal()
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
        stopReveal()
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
        stopReveal()
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

    /// Neutral near-black used as the frosted-glass tint (kept low-opacity so the pill stays
    /// translucent — the blur does the legibility work, not an opaque fill).
    private static let glassTint = Color(red: 0.055, green: 0.063, blue: 0.078)

    /// Only the currently-revealed prefix of the live text — the typewriter grows this a few chars per
    /// frame, so the pill moves gradually (the "no jumps" fix). Head-truncation keeps the newest tail
    /// visible; the oldest words scroll off the front.
    private var shownLive: String { String(model.previewText.prefix(model.revealedCount)) }

    /// Plain-string form of what the pill shows — the low-input warning, the revealed live text, or
    /// the status label. Also the `.animation` value, so the capsule resizes smoothly as it reveals.
    private var displayText: String {
        if model.phase == .recording && model.lowInput {
            return "Speak up — I can barely hear you"
        }
        let shown = shownLive
        return (model.phase == .recording && !shown.isEmpty) ? shown : model.label
    }

    /// Whether the pill should render the live transcript vs the status label / warning.
    private var showsLiveTranscript: Bool {
        model.phase == .recording && !model.lowInput && !model.previewText.isEmpty
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
            // Frosted GLASS, not a solid fill: the material blurs whatever's behind the floating
            // pill, and a light ~32% neutral-dark tint gives just enough backing for the text/wave to
            // stay legible over any desktop — see-through everywhere, never reads black.
            Capsule()
                .fill(.ultraThinMaterial)
                .overlay(
                    Capsule().fill(
                        LinearGradient(
                            colors: [Self.glassTint.opacity(0.36), Self.glassTint.opacity(0.30)],
                            startPoint: .top, endPoint: .bottom)))
                // Subtle top highlight — the glass edge catching light.
                .overlay(
                    Capsule().fill(
                        LinearGradient(colors: [Color.white.opacity(0.06), Color.clear],
                                       startPoint: .top, endPoint: .center)))
        }
        // Recording → a shimmering gold border (a moving shine sweeps the capsule edge). Other
        // states keep the quiet hairline. Honors Reduce Motion (static gold, no sweep).
        .overlay {
            if model.phase == .recording {
                GoldShimmerBorder()
            } else {
                Capsule().strokeBorder(Theme.Palette.strokeStrong)
            }
        }
        .fixedSize(horizontal: true, vertical: false)   // hug content width (texts self-cap below)
        // Smoothly grow/shrink the pill as the transcript streams in (until the text cap), and on
        // state changes — matches the design's growing HUD.
        // Short linear so per-frame reveal steps blend without rubber-banding against each other
        // (the reveal driver already provides the smooth growth).
        .animation(.linear(duration: 0.05), value: displayText)
        .animation(.easeOut(duration: 0.15), value: model.phase)
        .frame(maxWidth: .infinity)                      // center the content-sized pill in the panel
    }

    private var activeContent: some View {
        HStack(spacing: 10) {
            Image(systemName: micIcon)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(micColor)

            WaveMeter(
                level: model.level,
                active: model.phase == .recording,
                color: model.phase == .recording ? micColor : Theme.Palette.textTertiary
            )
            .frame(width: 78, height: 18)

            // Live transcript roll-up (PRD 0008): confirmed solid + hypothesis dimmed, one line,
            // head-truncated so the newest words show and the oldest scroll off the front. Falls
            // back to the status label / low-input warning when there's no live text.
            Text(showsLiveTranscript ? shownLive : displayText)
                .font(.callout)
                .foregroundStyle(model.phase == .recording && model.lowInput ? Theme.Palette.warning : Theme.Palette.textPrimary)
                .lineLimit(1)
                .truncationMode(.head)   // newest words show, oldest scroll off the front
                .frame(maxWidth: 300, alignment: .leading)
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

/// A flowing waveform: a continuous sine curve that travels while recording, its amplitude scaling to
/// the live mic level, tapered at the edges so it reads as a self-contained wave. Idle / Reduce
/// Motion → a quiet flat line.
private struct WaveMeter: View {
    let level: Float
    let active: Bool
    var color: Color = Theme.Palette.recording

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Live amplitude from mic RMS → 0…1. Low floor so it goes nearly FLAT when you're quiet and
    /// swells with loudness — the wave visibly tracks your voice. Reaches full around RMS 0.2.
    private var amplitude: CGFloat { 0.08 + 0.92 * min(1, CGFloat(level) / 0.2) }

    var body: some View {
        if active && !reduceMotion {
            TimelineView(.animation) { timeline in
                Canvas { ctx, size in
                    ctx.stroke(wavePath(in: size, t: timeline.date.timeIntervalSinceReferenceDate),
                               with: .color(color), style: StrokeStyle(lineWidth: 2, lineCap: .round))
                }
            }
        } else {
            Canvas { ctx, size in
                var path = Path()
                let mid = size.height / 2
                path.move(to: CGPoint(x: 0, y: mid))
                path.addLine(to: CGPoint(x: size.width, y: mid))
                ctx.stroke(path, with: .color(active ? color : Theme.Palette.textTertiary),
                           style: StrokeStyle(lineWidth: 2, lineCap: .round))
            }
        }
    }

    /// A travelling sine across the width, edge-tapered (sin envelope) so both ends settle to center.
    private func wavePath(in size: CGSize, t: Double) -> Path {
        var path = Path()
        let mid = size.height / 2
        let maxAmp = size.height / 2 - 1
        let steps = max(2, Int(size.width))
        let phase = t * 6
        for i in 0...steps {
            let frac = CGFloat(i) / CGFloat(steps)
            let x = frac * size.width
            let envelope = sin(frac * .pi)                       // 0 at edges, 1 at center
            let y = mid + amplitude * maxAmp * envelope * CGFloat(sin(Double(x) * 0.35 + phase))
            if i == 0 { path.move(to: CGPoint(x: x, y: y)) }
            else { path.addLine(to: CGPoint(x: x, y: y)) }
        }
        return path
    }
}

/// A shimmering gold border for the recording pill: a gold gradient with bright highlights rotates
/// around the capsule edge, giving a moving "shine". Reduce Motion → a static gold stroke.
private struct GoldShimmerBorder: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    // Warm golds only — the shine is gold-on-gold, no near-white highlight.
    private let deepGold   = Color(red: 0.50, green: 0.34, blue: 0.08)
    private let gold       = Color(red: 0.83, green: 0.63, blue: 0.22)
    private let brightGold = Color(red: 1.00, green: 0.82, blue: 0.38)
    private let lineWidth: CGFloat = 1.8

    var body: some View {
        Group {
            if reduceMotion {
                Capsule().strokeBorder(gold, lineWidth: lineWidth)
            } else {
                TimelineView(.animation) { timeline in
                    let t = timeline.date.timeIntervalSinceReferenceDate
                    let angle = Angle.degrees((t.truncatingRemainder(dividingBy: 4) / 4) * 360)
                    Capsule()
                        .strokeBorder(
                            AngularGradient(
                                gradient: Gradient(colors: [deepGold, gold, brightGold, gold, deepGold,
                                                            gold, brightGold, gold, deepGold]),
                                center: .center,
                                angle: angle),
                            lineWidth: lineWidth)
                }
            }
        }
        // A soft golden glow so the shine reads even against a bright backdrop.
        .shadow(color: gold.opacity(0.55), radius: 4)
    }
}
