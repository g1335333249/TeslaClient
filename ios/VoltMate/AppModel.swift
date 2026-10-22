import Foundation
import SwiftUI

@MainActor
final class AppModel: ObservableObject {
    @Published var vehicles: [Vehicle] = []
    @Published var selectedVehicle: Vehicle?
    @Published var status: VehicleStatus?
    @Published var trips: [Trip] = []
    @Published var charging: [ChargingSession] = []
    @Published var daily: [DailyStat] = []
    @Published var loading = false
    @Published var error: String?
    @Published var isDemo = UserDefaults.standard.bool(forKey: "demoMode")
    @Published var loggedIn = KeychainToken.load() != nil
    private let api = APIClient.shared
    private let teslaAuth = TeslaAuth()
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
                async let t: [Trip] = api.request("/api/v1/vehicles/\(id)/trips")
                async let c: [ChargingSession] = api.request("/api/v1/vehicles/\(id)/charging/sessions")
                async let d: [DailyStat] = api.request("/api/v1/vehicles/\(id)/statistics/daily")
                status = try await s; trips = try await t; charging = try await c; daily = try await d
            }
        } catch { self.error = error.localizedDescription }
        loading = false
    }
    func select(_ vehicle: Vehicle) async { selectedVehicle = vehicle; await refresh() }
    func command(_ type: String, value: Int? = nil) async {
        guard let id = selectedVehicle?.id else { return }
        loading = true; error = nil
        do {
            var body: [String: Any] = ["type": type, "idempotencyKey": UUID().uuidString]
            if let value { body["value"] = value }
            let result: CommandResult = try await api.request("/api/v1/vehicles/\(id)/commands", method: "POST", body: body)
            if result.outcome != "SUCCESS" { error = result.message }
            await refresh()
        } catch { self.error = error.localizedDescription }
        loading = false
    }
    func logout() async {
        if KeychainToken.load() != nil {
            let _: [String: String]? = try? await api.request("/api/v1/auth/logout", method: "POST")
        }
        KeychainToken.clear(); loggedIn = false; vehicles = []; selectedVehicle = nil; status = nil
        isDemo = false; UserDefaults.standard.set(false, forKey: "demoMode")
    }
}
