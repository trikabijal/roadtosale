import SwiftUI
import DictationCore

@main
struct DictationApp: App {
    @StateObject private var appState = AppState()

    var body: some Scene {
        MenuBarExtra {
            MenuBarView()
                .environmentObject(appState)
        } label: {
            MenuBarIcon(state: appState.dictationState)
        }
        .menuBarExtraStyle(.window)

        Settings {
            SettingsView()
                .environmentObject(appState)
        }
    }
}

/// Mic icon that changes based on dictation state
struct MenuBarIcon: View {
    let state: DictationState

    var body: some View {
        switch state {
        case .idle:
            Image(systemName: "mic")
        case .recording:
            Image(systemName: "mic.fill")
                .foregroundStyle(.red)
        case .transcribing:
            Image(systemName: "waveform")
                .foregroundStyle(.orange)
        }
    }
}
