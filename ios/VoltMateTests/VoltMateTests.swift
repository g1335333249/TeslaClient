import XCTest
@testable import VoltMate

final class VoltMateTests: XCTestCase {
    func testVehicleDecoding() throws {
        let data = Data("{\"id\":2001,\"vin\":\"MOCKVIN0000000000\",\"name\":\"小白\",\"model\":\"Model Y L\",\"color\":null,\"firmware\":null,\"keyState\":\"PAIRED\",\"region\":\"CN\"}".utf8)
        XCTAssertEqual(try JSONDecoder().decode(Vehicle.self, from: data).model, "Model Y L")
    }
    func testVehicleStatusKeepsMissingSignalsUnavailable() throws {
        let data = Data("{\"vehicleId\":2001,\"source\":\"MOCK\",\"onlineState\":\"ASLEEP\",\"soc\":64,\"mock\":true}".utf8)
        let status = try JSONDecoder().decode(VehicleStatus.self, from: data)
        XCTAssertTrue(status.mock)
        XCTAssertEqual(status.onlineState, "ASLEEP")
        XCTAssertNil(status.latitude)
        XCTAssertNil(status.chargingKw)
    }
    func testCapabilitiesDoNotAssumeUnknownIsSupported() throws {
        let data = Data("{\"vehicleId\":2001,\"keyState\":\"UNKNOWN\",\"scopes\":\"\",\"capabilities\":{\"UNLOCK\":{\"state\":\"KEY_PAIRING_REQUIRED\",\"reason\":\"需要配对\"}},\"pairingUrl\":\"\",\"checkedAt\":\"2026-10-22T00:00:00Z\",\"mock\":false}".utf8)
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        let capabilities = try decoder.decode(VehicleCapabilities.self, from: data)
        XCTAssertEqual(capabilities.state("UNLOCK"), "KEY_PAIRING_REQUIRED")
        XCTAssertEqual(capabilities.state("FRUNK"), "UNKNOWN")
        XCTAssertFalse(capabilities.mock)
    }
    func testChargingCostPreservesEstimateFlag() throws {
        let data = Data("{\"sessionId\":1,\"amount\":12.34,\"currency\":\"CNY\",\"accuracy\":\"ESTIMATED\",\"source\":\"GRID\",\"tariffBreakdown\":{\"谷时\":12.34},\"dailyBreakdown\":{\"2026-10-22\":12.34}}".utf8)
        let cost = try JSONDecoder().decode(ChargingCost.self, from: data)
        XCTAssertEqual(cost.accuracy, "ESTIMATED")
        XCTAssertEqual(cost.amount, 12.34)
    }
}
