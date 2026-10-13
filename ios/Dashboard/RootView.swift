import SwiftUI

/// Root tab surface — mirrors the Android app's destinations in the
/// same order (Grow sits between Actions and Windows). Each tab gets
/// its own NavigationStack; the connection status line lives in
/// Overview only (matching the Android header).
struct RootView: View {
    @EnvironmentObject private var vm: DashboardViewModel
    @State private var selection = 0

    var body: some View {
        TabView(selection: $selection) {
            NavigationStack {
                OverviewView()
                    .navigationTitle("Dashboard")
            }
            .tabItem { Label("Overview", systemImage: "square.grid.2x2") }
            .tag(0)

            NavigationStack {
                SystemView()
                    .navigationTitle("System")
            }
            .tabItem { Label("System", systemImage: "cpu") }
            .tag(1)

            NavigationStack {
                DarkbloomView()
                    .navigationTitle("Darkbloom")
            }
            .tabItem { Label("Darkbloom", systemImage: "dollarsign.circle") }
            .tag(2)

            NavigationStack {
                MiningView()
                    .navigationTitle("Mining")
            }
            .tabItem { Label("Mining", systemImage: "bolt") }
            .tag(3)

            NavigationStack {
                EarningsView()
                    .navigationTitle("Earnings")
            }
            .tabItem { Label("Earnings", systemImage: "wallet.pass") }
            .tag(4)

            NavigationStack {
                ActionsView()
                    .navigationTitle("Actions")
            }
            .tabItem { Label("Actions", systemImage: "play.circle") }
            .tag(5)

            NavigationStack {
                GrowView()
                    .navigationTitle("Grow")
            }
            .tabItem { Label("Grow", systemImage: "leaf.fill") }
            .tag(6)

            NavigationStack {
                WindowsView()
                    .navigationTitle("Windows")
            }
            .tabItem { Label("Windows", systemImage: "desktopcomputer") }
            .tag(7)

            NavigationStack {
                SettingsView()
                    .navigationTitle("Settings")
            }
            .tabItem { Label("Settings", systemImage: "gearshape") }
            .tag(8)
        }
        .tint(Theme.accent)
        .background(Theme.bg)
    }
}
