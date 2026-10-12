import SwiftUI

@main
struct DashboardApp: App {
    @StateObject private var vm = DashboardViewModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(vm)
                .preferredColorScheme(.dark)
                .task { vm.start() }
        }
    }
}
