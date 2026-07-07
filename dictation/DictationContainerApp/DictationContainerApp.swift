import DictationCoreBase
import SwiftUI

@main
struct DictationContainerApp: App {
    @State private var recording = false

    var body: some Scene {
        WindowGroup {
            ContentView()
                .fullScreenCover(isPresented: $recording) {
                    RecordSessionView(onClose: { recording = false })
                }
                .onOpenURL { url in
                    // justtalk://record — the keyboard's Flow-Session trigger.
                    if url.scheme == DictationHandoff.urlScheme, url.host == "record" {
                        recording = true
                    }
                }
        }
    }
}
