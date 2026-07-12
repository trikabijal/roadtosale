import SwiftUI
import DictationCoreBase

/// The keyboard extension's full UI. The area is entirely ours (we don't reimplement typing — users
/// switch to Apple's keyboard via the globe for that, keeping swipe-to-type). So it's a big, calm
/// dictation surface: tap anywhere to start/stop, a living waveform driven by the real mic level while
/// listening.
///
/// EVERY element here reads from ONE value — `viewModel.presentation`. There is no per-element state:
/// the wave, the accent colour, the button, and the label are all facets of the same
/// `KeyboardPresentation` snapshot, which is derived wholesale from the shared `capturing` variable on
/// every poll. Two modes only: speaking or not. See KeyboardViewModel / ios-dictation-architecture.md.
struct KeyboardView: View {
    @ObservedObject var viewModel: KeyboardViewModel
    let onNextKeyboard: () -> Void

    /// Subtle Just Talk brand accent — reads on both light and dark grounds.
    private let accent = Color(red: 0.86, green: 0.62, blue: 0.20)

    private var p: KeyboardPresentation { viewModel.presentation }

    var body: some View {
        ZStack {
            Color(.systemGroupedBackground).ignoresSafeArea()
            VStack(spacing: 0) {
                // The whole surface is the tap target (easy to hit) — start when idle, finish when speaking.
                stage
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .contentShape(Rectangle())
                    .onTapGesture { viewModel.toggleRecording() }
                bottomBar
            }
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - Stage — the ONLY two states: speaking / not speaking

    @ViewBuilder private var stage: some View {
        VStack(spacing: 16) {
            switch p.mode {
            case .notSpeaking:
                ZStack {
                    Circle().fill(Color(.secondarySystemGroupedBackground))
                        .frame(width: 70, height: 70)
                        .shadow(color: .black.opacity(0.10), radius: 5, y: 2)
                    Image(systemName: "mic.fill").font(.system(size: 27, weight: .semibold))
                        .foregroundStyle(accent)
                }
                Text(p.label).font(.callout).foregroundStyle(.secondary)
                if let stats = viewModel.stats {
                    StatStrip(stats: stats, accent: accent)
                        .frame(height: 30)
                        .padding(.horizontal, 24)
                        .padding(.top, 2)
                }

            case .speaking:
                // The wave shows ONLY while speaking and is ALWAYS the accent colour — there is no
                // separate "gray/transcribing" wave that could disagree with the button. Its amplitude
                // is p.level, straight from the same snapshot.
                Waveform(level: p.level, accent: accent)
                    .frame(height: 62)
                    .padding(.horizontal, 22)
                HStack(spacing: 8) {
                    Image(systemName: "stop.fill").font(.system(size: 13, weight: .bold))
                    Text("Tap to finish").font(.callout.weight(.semibold))
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 20).padding(.vertical, 11)
                .background(accent, in: Capsule())
                .shadow(color: accent.opacity(0.35), radius: 5, y: 2)
            }
        }
        .animation(.easeInOut(duration: 0.22), value: p.mode)
    }

    // MARK: - Bottom bar (globe is required by iOS)

    private var bottomBar: some View {
        HStack {
            Spacer()
            Button(action: onNextKeyboard) {
                Image(systemName: "globe").font(.system(size: 15))
                    .foregroundStyle(.tertiary).frame(width: 34, height: 30)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 8).padding(.bottom, 5)
    }
}

// MARK: - Stat strip (idle carousel)

/// A compact, swipeable one-line carousel of usage stats shown while idle (words / WPM / streak). Pure
/// display of numbers the app published to the App Group — reading them can't desync anything.
private struct StatStrip: View {
    let stats: DictationHandoff.KbdStats
    let accent: Color

    var body: some View {
        TabView {
            if stats.streak > 0 {
                item("flame.fill", "\(stats.streak)-day streak", accent)
            } else {
                item("flame", "Dictate daily for a streak", .secondary)
            }
            item("text.bubble.fill", "\(stats.words.formatted()) words", accent)
            if stats.wpm > 0 { item("bolt.fill", "\(stats.wpm) wpm", accent) }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
    }

    private func item(_ icon: String, _ text: String, _ color: Color) -> some View {
        HStack(spacing: 6) {
            Image(systemName: icon).font(.system(size: 12, weight: .semibold))
            Text(text).font(.footnote.weight(.medium))
        }
        .foregroundStyle(color)
        .frame(maxWidth: .infinity)
    }
}

// MARK: - Waveform

/// A row of bars that breathe with the live mic level. Only ever drawn while speaking, so it needs no
/// "active" flag — its presence *is* the speaking state, and its amplitude is the shared `level`.
private struct Waveform: View {
    let level: Float
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
        let loud = Double(min(1, level * 6))                    // scale by voice
        let frac = envelope * wobble * (0.12 + 0.88 * loud)
        return max(4, CGFloat(frac) * maxH)
    }
}
