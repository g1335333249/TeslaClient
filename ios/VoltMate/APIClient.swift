import Foundation
import Security

enum KeychainToken {
    static let key = "cn.voltmate.session"
    static func save(_ value: String) {
        let data = Data(value.utf8)
        SecItemDelete([kSecClass: kSecClassGenericPassword, kSecAttrAccount: key] as CFDictionary)
        SecItemAdd([kSecClass: kSecClassGenericPassword, kSecAttrAccount: key, kSecValueData: data, kSecAttrAccessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly] as CFDictionary, nil)
    }
    static func load() -> String? {
        var result: CFTypeRef?
        let status = SecItemCopyMatching([kSecClass: kSecClassGenericPassword, kSecAttrAccount: key, kSecReturnData: true, kSecMatchLimit: kSecMatchLimitOne] as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
    static func clear() {
        SecItemDelete([kSecClass: kSecClassGenericPassword, kSecAttrAccount: key] as CFDictionary)
    }
}

final class APIClient {
    static let shared = APIClient()
    var baseURL: URL {
        let saved = UserDefaults.standard.string(forKey: "serverURL") ?? "http://localhost:8080"
        return URL(string: saved) ?? URL(string: "http://localhost:8080")!
    }
    private let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .custom { decoder in
            let text = try decoder.singleValueContainer().decode(String.self)
            if let date = ISO8601DateFormatter.fractional.date(from: text) ?? ISO8601DateFormatter().date(from: text) { return date }
            if let date = DateFormatter.day.date(from: text) { return date }
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "无效日期"))
        }
        return d
    }()
    func request<T: Decodable>(_ path: String, method: String = "GET", body: [String: Any]? = nil) async throws -> T {
        guard let url = URL(string: path, relativeTo: baseURL)?.absoluteURL else { throw URLError(.badURL) }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 20
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token = KeychainToken.load() { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let body {
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        if !(200..<300).contains(http.statusCode) {
            throw (try? decoder.decode(APIError.self, from: data)) ?? APIError(code: "HTTP_\(http.statusCode)", message: "服务暂不可用")
        }
        return try decoder.decode(T.self, from: data)
    }
}

extension ISO8601DateFormatter {
    static let fractional: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
}

extension DateFormatter {
    static let day: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.timeZone = .current
        return f
    }()
}
