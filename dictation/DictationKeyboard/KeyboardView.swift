import SwiftUI

/// The keyboard extension's full UI. The area is entirely ours (we don't reimplement typing — users
/// switch to Apple's keyboard via the globe for that, keeping swipe-to-type). So it's a big, calm
/// dictation surface: tap anywhere to start/stop, a living waveform driven by the real mic level while
/// listening. Chrome uses system dynamic colors so it inherits the app's light/dark appearance.
struct KeyboardView: View {
    @ObservedObject var viewModel: KeyboardViewModel
    let onNextKeyboard: () -> Void

    /// Subtle Just Talk brand accent on the wave/mic only — reads on both light and dark grounds.
    private let accent = Color(red: 0.86, green: 0.62, blue: 0.20)

    var body: some View {
        ZStack {
            Color(.systemGroupedBackground).ignoresSafeArea()
            VStack(spacing: 0) {
                // The whole surface is the tap target (easy to hit) — start when idle, finish when dictating.
                stage
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .contentShape(Rectangle())
                    .onTapGesture { if viewModel.state != .awaiting { viewModel.toggleRecording() } }
                bottomBar
            }
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - Stage (changes with state)

    @ViewBuilder private var stage: some View {
        VStack(spacing: 16) {
            switch viewModel.state {
            case .idle:
                ZStack {
                    Circle().fill(Color(.secondarySystemGroupedBackground))
                        .frame(width: 74, height: 74)
                        .shadow(color: .black.opacity(0.10), radius: 5, y: 2)
                    Image(systemName: "mic.fill").font(.system(size: 28, weight: .semibold))
                        .foregroundStyle(accent)
                }
                Text("Tap anywhere to dictate")
                    .font(.callout).foregroundStyle(.secondary)

            case .dictating:
                Waveform(level: viewModel.micLevel, active: true, accent: accent)
                    .frame(height: 68)
                    .padding(.horizontal, 22)
                Label("Listening… tap to finish", systemImage: "stop.circle")
                    .font(.callout.weight(.medium)).foregroundStyle(accent)

            case .awaiting:
                Waveform(level: 0, active: false, accent: .secondary)
                    .frame(height: 68).padding(.horizontal, 22)
                    .opacity(0.6)
                Text("Transcribing…").font(.callout).foregroundStyle(.secondary)
            }
        }
        .animation(.easeInOut(duration: 0.22), value: viewModel.state)
    }

    // MARK: - Bottom bar (globe is required by iOS)

    private var bottomBar: some View {
        HStack(spacing: 4) {
            Button(action: onNextKeyboard) {
                Image(systemName: "globe").font(.system(size: 19))
                    .foregroundStyle(.secondary).frame(width: 42, height: 38)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Text("Switch keyboard to type").font(.caption2).foregroundStyle(.tertiary)
            Spacer()
        }
        .padding(.horizontal, 10).padding(.bottom, 6)
    }
}

// MARK: - Waveform

/// A row of bars that breathe with the live mic level. `active` = responding to voice; otherwise a
/// gentle idle shimmer (used while transcribing).
private struct Waveform: View {
    let level: Float
    let active: Bool
    let accent: Color
    private let bars = 27

    var body: some View {
        TimelineView(.animation) { timeline in
            let t = timeline.date.timeIntervalSinceReferenceDate
            GeometryReader { geo in
                let w = geo.size.width
                let h = geo.size.height
                let barW = max(3, (w - CGFloat(bars - 1) * 5) / CGFloat(bars))
                HStack(alignment: .center, spacing: 5) {
                    ForEach(0..<bars, id: \.self) { i in
                        Capsule()
                            .fill(accent)
                            .frame(width: barW, height: barHeight(i, t: t, maxH: h))
                    }
                }
                .frame(width: w, height: h)
            }
        }
        .animation(.easeOut(duration: 0.08), value: level)
    }

    private func barHeight(_ i: Int, t: TimeInterval, maxH: CGFloat) -> CGFloat {
        let p = Double(i) / Double(bars - 1)
        let envelope = 0.35 + 0.65 * sin(p * .pi)               // taller in the middle
        let wobble = 0.55 + 0.45 * sin(t * 7 + Double(i) * 0.55) // lively motion
        let loud = active ? Double(min(1, level * 6)) : 0.16     // scale by voice (or idle shimmer)
        let frac = envelope * wobble * (0.12 + 0.88 * loud)
        return max(4, CGFloat(frac) * maxH)
    }
}
