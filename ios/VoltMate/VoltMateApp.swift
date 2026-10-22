import SwiftUI

@main
struct VoltMateApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase
    var body: some Scene {
        WindowGroup {
            Group {
                if model.loggedIn { MainTabs() }
                else { WelcomeView() }
            }
            .environmentObject(model)
            .tint(Color(red: 0.21, green: 0.68, blue: 0.87))
            .task { if model.loggedIn { await model.foreground() } }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { Task { if model.loggedIn { await model.foreground() } } }
                else { model.background() }
            }
        }
    }
}
