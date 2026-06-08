import SwiftUI
import DictationCore

@main
struct JustTalkApp: App {
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

        Window("Just Talk History", id: "history") {
            HistoryView()
                .environmentObject(appState)
        }
        .windowResizability(.contentSize)
    }
}

/// Menu bar glyph that changes with dictation state.
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
