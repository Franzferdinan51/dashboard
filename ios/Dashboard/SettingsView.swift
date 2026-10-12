import SwiftUI
import Foundation

/// Connection + about, mirroring Android's SettingsScreen (trimmed to
/// what the iOS contract exposes): the bridge URL editor and the
/// about card. Styled as a Form on the dark theme — the system
/// grouped background is hidden so Theme.bg shows through and rows
/// sit on the card color.
struct SettingsView: View {
    @EnvironmentObject private var vm: DashboardViewModel
    @State private var draft = ""
    @State private var seeded = false

    private var appVersion: String {
        (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? "—"
    }

    var body: some View {
        Form {
            Section {
                HStack {
                    Text("Status")
                        .foregroundStyle(Theme.textDim)
                    Spacer()
                    StatusChip(text: vm.connected ? "CONNECTED" : "UNREACHABLE",
                               color: vm.connected ? Theme.good : Theme.bad)
                }
                .listRowBackground(Theme.card)

                TextField("Bridge URL", text: $draft)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled(true)
                    .keyboardType(.URL)
                    .foregroundStyle(Theme.mist)
                    .listRowBackground(Theme.card)

                Button("Apply") {
                    vm.setBridgeURL(draft.trimmingCharacters(in: .whitespacesAndNewlines))
                }
                .foregroundStyle(Theme.accent)
                .listRowBackground(Theme.card)
            } header: {
                Text("Bridge URL")
                    .foregroundStyle(Theme.textDim)
            } footer: {
                Text("Bridge address (the dashboard bridge running on the Mac). The default is the Mac's tailnet address, http://100.68.208.113:8901 — the LAN address, http://192.168.1.96:8901, works at home.")
                    .foregroundStyle(Theme.textFaint)
            }

            Section {
                KVRow("App", "Mac Studio Dashboard v\(appVersion)")
                    .listRowBackground(Theme.card)
                KVRow("Bridge", vm.bridgeURLString)
                    .listRowBackground(Theme.card)
                KVRow("Data", "System vitals · Darkbloom · LM Studio")
                    .listRowBackground(Theme.card)
                Text("Talks only to your bridge on your tailnet/LAN. The Actions tab can run commands on your own machines.")
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textDim)
                    .listRowBackground(Theme.card)
                Text("Earnings come from Darkbloom's account ledger — the same numbers behind the web console. Only settled data is shown; estimates are not.")
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textFaint)
                    .listRowBackground(Theme.card)
            } header: {
                Text("About")
                    .foregroundStyle(Theme.textDim)
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.bg)
        .onAppear {
            // Seed the draft once from the live setting; after that the
            // field is the user's, untouched by polls.
            if !seeded {
                draft = vm.bridgeURLString
                seeded = true
            }
        }
    }
}
