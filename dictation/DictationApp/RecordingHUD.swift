import AppKit
import SwiftUI

enum RecordingHUDPhase {
    case recording, processing
}

/// Observable backing for the floating HUD.
@MainActor
final class RecordingHUDModel: ObservableObject {
    @Published var level: Float = 0          // 0…~1 mic RMS
    @Published var phase: RecordingHUDPhase = .recording
    @Published var label: String = "Listening…"
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
        let panel = ensurePanel()
        position(panel)
        panel.orderFrontRegardless()
    }

    func setPhase(_ phase: RecordingHUDPhase, label: String) {
        model.phase = phase
        model.label = label
        model.level = 0
    }

    func update(level: Float) {
        model.level = level
    }

    func hide() {
        panel?.orderOut(nil)
    }

    // MARK: - Panel

    private func ensurePanel() -> NSPanel {
        if let panel { return panel }
        let p = NSPanel(
            contentRect: NSRect(x: 0, y: 0, width: 240, height: 56),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        p.isFloatingPanel = true
        p.level = .statusBar
        p.backgroundColor = .clear
        p.isOpaque = false
        p.hasShadow = true
        p.ignoresMouseEvents = true
        p.hidesOnDeactivate = false
        p.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary]
        p.contentView = NSHostingView(rootView: HUDContentView(model: model))
        panel = p
        return p
    }

    private func position(_ panel: NSPanel) {
        guard let screen = NSScreen.main else { return }
        let visible = screen.visibleFrame
        let size = panel.frame.size
        panel.setFrameOrigin(NSPoint(
            x: visible.midX - size.width / 2,
            y: visible.minY + 120
        ))
    }
}

// MARK: - HUD content

private struct HUDContentView: View {
    @ObservedObject var model: RecordingHUDModel

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: model.phase == .recording ? "mic.fill" : "waveform")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(model.phase == .recording ? Color.red : Color.orange)

            LevelMeter(level: model.level, active: model.phase == .recording)
                .frame(width: 70, height: 20)

            Text(model.label)
                .font(.callout)
                .foregroundStyle(.primary)
                .lineLimit(1)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(.ultraThinMaterial, in: Capsule())
        .overlay(Capsule().strokeBorder(.white.opacity(0.08)))
        .fixedSize()
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
