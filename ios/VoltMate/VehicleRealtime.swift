import Foundation

@MainActor
final class VehicleRealtime {
    private var socket: URLSessionWebSocketTask?
    private var reader: Task<Void, Never>?
    private var heartbeat: Task<Void, Never>?
    private var reconnect = true
    private var vehicleId: Int64?
    var onChange: (() async -> Void)?
    var onDisconnect: (() async -> Void)?
    var onCommandResult: ((String) -> Void)?

    func connect(vehicleId: Int64) {
        guard KeychainToken.load() != nil else { return }
        if self.vehicleId == vehicleId, socket != nil { return }
        disconnect()
        self.vehicleId = vehicleId
        reconnect = true
        reader = Task { await run() }
    }
    func disconnect() {
        reconnect = false
        reader?.cancel(); heartbeat?.cancel()
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil; vehicleId = nil
    }
    private func run() async {
        var delay: UInt64 = 1
        while reconnect && !Task.isCancelled {
            guard let token = KeychainToken.load(), let id = vehicleId,
                  var components = URLComponents(url: APIClient.shared.baseURL, resolvingAgainstBaseURL: false) else { return }
            components.scheme = components.scheme == "https" ? "wss" : "ws"
            components.path = "/api/v1/ws"
            guard let url = components.url else { return }
            let task = URLSession.shared.webSocketTask(with: url)
            socket = task; task.resume()
            do {
                try await send(["type": "AUTH", "token": token], to: task)
                try await send(["type": "SUBSCRIBE", "vehicleId": id], to: task)
                heartbeat = Task {
                    while !Task.isCancelled {
                        try? await Task.sleep(for: .seconds(20))
                        if Task.isCancelled { break }
                        try? await send(["type": "PING", "token": token], to: task)
                    }
                }
                while reconnect && !Task.isCancelled {
                    let message = try await task.receive()
                    if case .string(let text) = message,
                       let data = text.data(using: .utf8),
                       let event = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                       let type = event["type"] as? String,
                       ["STATUS_CHANGED", "CHARGING_CHANGED", "COMMAND_RESULT"].contains(type) {
                        if type == "COMMAND_RESULT", let payload = event["payload"] as? [String: Any] {
                            onCommandResult?("\(payload["type"] as? String ?? "指令")：\(payload["outcome"] as? String ?? "未知")")
                        }
                        await onChange?()
                    }
                }
            } catch {
                if reconnect { await onDisconnect?() }
            }
            heartbeat?.cancel(); task.cancel(with: .goingAway, reason: nil)
            if !reconnect || Task.isCancelled { break }
            try? await Task.sleep(for: .seconds(delay))
            delay = min(delay * 2, 30)
        }
    }
    private func send(_ value: [String: Any], to task: URLSessionWebSocketTask) async throws {
        let data = try JSONSerialization.data(withJSONObject: value)
        try await task.send(.string(String(decoding: data, as: UTF8.self)))
    }
}
