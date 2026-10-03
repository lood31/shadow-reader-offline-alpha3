import SwiftUI

@main @MainActor struct ShadowReaderApp: App {
    @StateObject private var controller = ReaderController()
    @Environment(\.scenePhase) private var scenePhase
    var body: some Scene {
        WindowGroup { ReaderRootView(controller: controller).onChange(of: scenePhase) { _, phase in
            if phase == .active { controller.refresh() } else if phase == .background { controller.background() }
        } }
    }
}
