import SwiftUI

struct TVSettingsView: View {
    @ObservedObject var model: CompanionModel
    let previewItem: LocalPhoto?
    @State private var showAddressDetails = false
    @State private var addressInput = ""

    var body: some View {
        ScrollView(.vertical) {
            HStack(alignment: .top, spacing: 24) {
                VStack(alignment: .leading, spacing: 18) {
                    header
                    connectionCard
                }
                .frame(minWidth: 380, maxWidth: .infinity, alignment: .topLeading)

                previewCard
                    .frame(minWidth: 334, maxWidth: .infinity, alignment: .topLeading)
            }
            .frame(maxWidth: .infinity, alignment: .topLeading)
            .padding(.horizontal, AppDesign.pageInset)
            .padding(.top, AppDesign.pageTopInset)
            .padding(.bottom, 28)
        }
        .foregroundStyle(.white)
        .onAppear { addressInput = model.host }
        .onChange(of: model.host) { addressInput = $0 }
        .onChange(of: addressInput) { _ in
            model.setConnectionAddressEditing(hasAddressDraft)
        }
        .onDisappear { model.setConnectionAddressEditing(false) }
    }

    private var header: some View {
        PageHeading("TV", subtitle: "Connect your TV to send local media and playback settings over Wi-Fi.")
    }

    private var connectionCard: some View {
        VStack(alignment: .leading, spacing: 18) {
            statusPanel
            connectionContent
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var statusPanel: some View {
        VStack(alignment: .leading, spacing: 12) {
            statusHeader
            if shouldShowMessage {
                Text(model.connectionMessage)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .nativeGlass(corner: 18)
    }

    private var statusHeader: some View {
        HStack(spacing: 12) {
            Icon(statusIcon, fallback: "TV")
                .frame(width: 28, height: 28)
                .frame(width: 48, height: 48)
                .background(statusColor.opacity(0.16), in: Circle())
            VStack(alignment: .leading, spacing: 3) {
                Text(statusTitle)
                    .font(.title3.weight(.semibold))
                Text(statusSubtitle)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Circle()
                .fill(statusColor)
                .frame(width: 10, height: 10)
                .accessibilityLabel(statusTitle)
        }
    }

    @ViewBuilder
    private var connectionContent: some View {
        switch model.tvConnectionState {
        case .unpaired:
            contentPanel { unpairedContent }
        case .disconnected, .connected:
            connectionActions
        case .checking:
            contentPanel { progressContent("Checking the TV connection…") }
        case .discovering:
            contentPanel { progressContent("Reading the TV certificate…") }
        case .awaitingCode:
            contentPanel { pairingCodeContent }
        case .pairing:
            contentPanel { progressContent("Connecting to your TV…") }
        }
    }

    private func contentPanel<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        content()
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
            .nativeGlass(corner: 18)
    }

    private var unpairedContent: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("On your TV, open Settings and choose Pair Mac. Then enter its address below.")
                .foregroundStyle(.secondary)
            addressDetails(expanded: true)
            Button {
                guard saveAddress() else { return }
                model.beginCertificateDiscovery()
            } label: {
                TVIconLabel(icon: "wifi", text: "Pair TV")
            }
            .nativeGlassButton()
            .disabled(isBusy || !validAddress)
        }
    }

    private var connectionActions: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 10) {
                Button {
                    if model.isConnected { model.refresh() } else { model.connect() }
                } label: {
                    TVIconLabel(icon: "refresh", text: model.isConnected ? "Check connection" : "Connect")
                        .frame(maxWidth: .infinity, minHeight: 24)
                }
                .nativeGlassButton()
                .disabled(isBusy || hasAddressDraft)

                Button {
                    model.beginRePairing()
                } label: {
                    TVIconLabel(icon: "wifi", text: "Re-pair TV")
                        .frame(maxWidth: .infinity, minHeight: 24)
                }
                .nativeGlassButton()
                .disabled(isBusy || hasAddressDraft)
            }
            addressDetails(expanded: false)
        }
    }

    private var pairingCodeContent: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Compare this fingerprint with the one shown on the TV. Then enter the six-digit code from the TV.")
                .foregroundStyle(.secondary)

            if let pin = model.discoveredPin {
                Text(groupedFingerprint(pin))
                    .font(.system(.caption, design: .monospaced))
                    .textSelection(.enabled)
                    .padding(10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .nativeGlass(corner: 10)
                    .accessibilityLabel("TV certificate fingerprint")
            }

            TextField("Enter TV code", text: $model.pairingCode)
                .textFieldStyle(.roundedBorder)
                .font(.system(.title3, design: .monospaced))
                .onSubmit(confirmPairing)
                .accessibilityLabel("Enter six-digit TV code")

            HStack(spacing: 10) {
                Button("Connect TV", action: confirmPairing)
                    .nativeGlassButton()
                    .disabled(!hasPairingCode || isBusy)
                Button("Cancel") {
                    model.cancelPairing()
                }
                .nativeGlassButton()
                .disabled(model.connectionActivity != .idle)
            }
        }
    }

    private func progressContent(_ title: String) -> some View {
        HStack(spacing: 12) {
            ProgressView()
                .controlSize(.regular)
            Text(title)
            Spacer()
        }
        .foregroundStyle(.secondary)
    }

    @ViewBuilder
    private func addressDetails(expanded: Bool) -> some View {
        if expanded {
            addressField
        } else {
            DisclosureGroup("TV address", isExpanded: $showAddressDetails) {
                addressField.padding(.top, 8)
            }
            .font(.subheadline)
        }
    }

    private var addressField: some View {
        HStack {
            Text("Address").foregroundStyle(.secondary)
            TextField("TV address", text: $addressInput)
                .textFieldStyle(.roundedBorder)
                .disabled(isBusy)
                .onSubmit { _ = saveAddress() }
            if hasAddressDraft {
                Button("Use address") { _ = saveAddress() }
                    .nativeGlassButton()
                    .disabled(isBusy || !validAddress)
            }
        }
    }

    private var hasAddressDraft: Bool {
        addressInput.trimmingCharacters(in: .whitespacesAndNewlines) != model.host
    }

    private var validAddress: Bool {
        let parts = addressInput.trimmingCharacters(in: .whitespacesAndNewlines)
            .split(separator: ".", omittingEmptySubsequences: false)
        return parts.count == 4 && parts.allSatisfy {
            !$0.isEmpty && $0.allSatisfy(\.isNumber) && Int($0).map { (0...255).contains($0) } == true
        }
    }

    @discardableResult
    private func saveAddress() -> Bool {
        guard !isBusy, validAddress else { return false }
        let address = addressInput.trimmingCharacters(in: .whitespacesAndNewlines)
        if address != model.host {
            guard model.addTV(host: address) else { return false }
        }
        model.setConnectionAddressEditing(false)
        return true
    }

    private var previewCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Preview")
                .font(.headline)
            TVPreviewFrame(item: previewItem)
                .frame(maxWidth: .infinity)
            Text(previewItem == nil ? "Displays the included launcher background." : "Displays the selected local media.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(16)
        .nativeGlass(corner: 18)
    }

    private func confirmPairing() {
        guard hasPairingCode, !isBusy else { return }
        model.confirmPairing()
    }

    private var hasPairingCode: Bool {
        model.pairingCode.range(of: "^[0-9]{6}$", options: .regularExpression) != nil
    }

    private var isBusy: Bool {
        if model.isWorking { return true }
        switch model.connectionActivity {
        case .idle: return false
        case .checking, .discovering, .pairing: return true
        }
    }

    private var shouldShowMessage: Bool {
        guard !model.connectionMessage.isEmpty else { return false }
        switch model.tvConnectionState {
        case .disconnected: return true
        case .unpaired, .awaitingCode: return model.errorMessage != nil
        default: return false
        }
    }

    private var hasAuthenticatedStatus: Bool {
        model.tvConnectionState == .connected && model.isPaired && model.serverStatus != nil
    }

    private var statusTitle: String {
        if hasAuthenticatedStatus { return "TV connected" }
        switch model.tvConnectionState {
        case .connected: return "Not connected"
        case .checking: return "Checking connection"
        case .discovering: return "Finding your TV"
        case .awaitingCode: return "Confirm pairing"
        case .pairing: return "Pairing TV"
        case .disconnected: return "Not connected"
        case .unpaired: return "Connect your TV"
        }
    }

    private var statusSubtitle: String {
        if hasAuthenticatedStatus {
            let checked = model.lastStatusCheck.map { "Last checked \($0.formatted(date: .omitted, time: .shortened))" } ?? "Authenticated connection"
            return "\(model.host) · \(checked)"
        }
        if model.tvConnectionState == .checking { return "Verifying the saved TV connection." }
        if model.tvConnectionState == .discovering { return "Reading the certificate from \(model.host)." }
        if model.tvConnectionState == .pairing { return "Confirming the code with your TV." }
        if model.tvConnectionState == .unpaired { return "Pair this Mac from the TV settings." }
        if model.tvConnectionState == .awaitingCode { return "Verify the certificate, then use the TV code." }
        return model.isPaired ? "Saved pairing. Check the connection to continue." : "Waiting for TV setup."
    }

    private var statusIcon: String {
        hasAuthenticatedStatus ? "check" : "tv"
    }

    private var statusColor: Color {
        hasAuthenticatedStatus ? .green : .secondary
    }
}

private struct TVIconLabel: View {
    let icon: String
    let text: String

    var body: some View {
        HStack(spacing: 8) {
            Icon(icon, fallback: text)
                .frame(width: 17, height: 17)
            Text(text)
        }
    }
}
