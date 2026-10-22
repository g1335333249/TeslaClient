import Foundation
import SwiftUI

@MainActor
final class AppModel: ObservableObject {
    @Published var vehicles: [Vehicle] = []
    @Published var selectedVehicle: Vehicle?
    @Published var status: VehicleStatus?
    @Published var capabilities: VehicleCapabilities?
    @Published var trips: [Trip] = []
    @Published var charging: [ChargingSession] = []
    @Published var daily: [DailyStat] = []
    @Published var loading = false
    @Published var error: String?
    @Published var commandFeedback: String?
    @Published var isDemo = UserDefaults.standard.bool(forKey: "demoMode")
    @Published var loggedIn = KeychainToken.load() != nil
    private let api = APIClient.shared
    private let teslaAuth = TeslaAuth()
    private let realtime = VehicleRealtime()
    func foreground() async {
        await refresh()
        realtime.onChange = { [weak self] in await self?.refreshStatus() }
        realtime.onDisconnect = { [weak self] in await self?.refreshStatus() }
        realtime.onCommandResult = { [weak self] result in self?.commandFeedback = result }
        if let id = selectedVehicle?.id { realtime.connect(vehicleId: id) }
    }
    func background() { realtime.disconnect() }
    private func refreshStatus() async {
        guard let id = selectedVehicle?.id else { return }
        do { status = try await api.request("/api/v1/vehicles/\(id)/status") }
        catch { self.error = error.localizedDescription }
    }
    func connectTesla() async { await teslaAuth.start(model: self) }
    func demoLogin() async {
        loading = true; error = nil
        do {
            let result: LoginResult = try await api.request("/api/v1/auth/demo", method: "POST", body: ["clientType": "IOS"])
            KeychainToken.save(result.token)
            isDemo = true; UserDefaults.standard.set(true, forKey: "demoMode")
            loggedIn = true
            await refresh()
        } catch { self.error = error.localizedDescription }
        loading = false
    }
    func refresh() async {
        guard loggedIn else { return }
        loading = true; error = nil
        do {
            vehicles = try await api.request("/api/v1/vehicles")
            selectedVehicle = vehicles.first(where: { $0.id == selectedVehicle?.id }) ?? vehicles.first
            if let id = selectedVehicle?.id {
                async let s: VehicleStatus = api.request("/api/v1/vehicles/\(id)/status")
                async let a: VehicleCapabilities = api.request("/api/v1/vehicles/\(id)/capabilities")
                async let t: [Trip] = api.request("/api/v1/vehicles/\(id)/trips")
                async let c: [ChargingSession] = api.request("/api/v1/vehicles/\(id)/charging/sessions")
                async let d: [DailyStat] = api.request("/api/v1/vehicles/\(id)/statistics/daily")
                status = try await s; capabilities = try await a; trips = try await t; charging = try await c; daily = try await d
                realtime.connect(vehicleId: id)
            }
        } catch { self.error = error.localizedDescription }
        loading = false
    }
    func select(_ vehicle: Vehicle) async { selectedVehicle = vehicle; await refresh() }
    func command(_ type: String, value: Int? = nil) async {
        guard let id = selectedVehicle?.id else { return }
        if let capability = capabilities?.capabilities[type], ["UNSUPPORTED","PERMISSION_REQUIRED","KEY_PAIRING_REQUIRED"].contains(capability.state) {
            error = capability.reason; return
        }
        loading = true; error = nil
        do {
            var body: [String: Any] = ["type": type, "idempotencyKey": UUID().uuidString]
            if let value { body["value"] = value }
            let result: CommandResult = try await api.request("/api/v1/vehicles/\(id)/commands", method: "POST", body: body)
            if result.outcome != "SUCCESS" { error = result.message }
            commandFeedback = "\(type)：\(result.outcome == "SUCCESS" ? "已确认" : "失败")"
            await refresh()
        } catch { self.error = error.localizedDescription }
        loading = false
    }
    func logout() async {
        realtime.disconnect()
        if KeychainToken.load() != nil {
            let _: [String: String]? = try? await api.request("/api/v1/auth/logout", method: "POST")
        }
        KeychainToken.clear(); loggedIn = false; vehicles = []; selectedVehicle = nil; status = nil; capabilities = nil
        isDemo = false; UserDefaults.standard.set(false, forKey: "demoMode")
    }
}
