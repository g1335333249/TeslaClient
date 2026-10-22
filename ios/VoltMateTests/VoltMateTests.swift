import XCTest
@testable import VoltMate

final class VoltMateTests: XCTestCase {
    func testVehicleDecoding() throws {
        let data = Data("{\"id\":2001,\"vin\":\"MOCKVIN0000000000\",\"name\":\"小白\",\"model\":\"Model Y L\",\"color\":null,\"firmware\":null,\"keyState\":\"PAIRED\",\"region\":\"CN\"}".utf8)
        XCTAssertEqual(try JSONDecoder().decode(Vehicle.self, from: data).model, "Model Y L")
    }
}
