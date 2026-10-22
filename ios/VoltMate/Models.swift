import Foundation

struct Vehicle: Codable, Identifiable {
    let id: Int64
    let vin: String
    let name: String
    let model: String?
    let color: String?
    let firmware: String?
    let keyState: String
    let region: String
}

struct VehicleStatus: Codable {
    let vehicleId: Int64
    let source: String
    let observedAt: Date?
    let receivedAt: Date?
    let onlineState: String?
    let soc: Double?
    let rangeKm: Double?
    let odometerKm: Double?
    let insideC: Double?
    let outsideC: Double?
    let climateOn: Bool?
    let locked: Bool?
    let chargingState: String?
    let chargingKw: Double?
    let chargeLimit: Int?
    let latitude: Double?
    let longitude: Double?
    let mock: Bool
}

struct Trip: Codable, Identifiable {
    let id: Int64
    let startedAt: Date
    let endedAt: Date?
    let distanceKm: Double?
    let energyKwh: Double?
    let completeness: String
}

struct TripPoint: Codable {
    let observedAt: Date
    let latitude: Double
    let longitude: Double
}

struct TripDetail: Codable {
    let trip: Trip
    let points: [TripPoint]
}

struct ChargingSession: Codable, Identifiable {
    let id: Int64
    let startedAt: Date
    let endedAt: Date?
    let gridKwh: Double?
    let batteryKwh: Double?
    let estimatedKwh: Double?
    let estimatedCost: Double?
    let currency: String
}

struct DailyStat: Codable, Identifiable {
    var id: Date { day }
    let day: Date
    let distanceKm: Double
    let drivingKwh: Double
    let chargingKwh: Double
    let chargingCost: Double
    let tripCount: Int
    let chargeCount: Int
}

struct CommandResult: Codable {
    let id: Int64
    let type: String
    let outcome: String
    let message: String
}

struct LoginResult: Codable {
    let token: String
    let expiresAt: Date
    let userId: Int64
}

struct APIError: Codable, LocalizedError {
    let code: String
    let message: String
    var errorDescription: String? { message }
}

struct UserSettings: Codable {
    var retentionDays: Int
    var locationEnabled: Bool
    var sampleIntervalSeconds: Int
}

struct Tariff: Codable, Identifiable {
    let id: Int64
    let name: String
    let pricePerKwh: Double
    let startsLocal: String
    let endsLocal: String
    let timezone: String
}

struct VehicleCapabilities: Codable {
    let vehicleId: Int64
    let keyState: String
    let commandsAvailable: Bool
    let pairingUrl: String
}

struct MonthlyStat: Codable, Identifiable {
    var id: String { month }
    let month: String
    let distanceKm: Double
    let drivingKwh: Double
    let chargingKwh: Double
    let chargingCost: Double
}

struct TeslaAccount: Codable, Identifiable {
    let id: Int64
    let region: String
    let scopes: String
    let authorizationState: String
}
