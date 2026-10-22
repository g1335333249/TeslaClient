import AuthenticationServices
import Foundation

@MainActor
final class TeslaAuth: NSObject, ASWebAuthenticationPresentationContextProviding {
    private var session: ASWebAuthenticationSession?
    private let api = APIClient.shared
    func start(model: AppModel) async {
        do {
            let start: StartResponse = try await api.request("/api/v1/auth/tesla/start", method: "POST", body: ["clientType": "IOS"])
            guard let url = URL(string: start.authorizationUrl) else { throw APIError(code: "URL", message: "授权链接无效") }
            session = ASWebAuthenticationSession(url: url, callbackURLScheme: "voltmate") { callback, error in
                Task { @MainActor in
                    if let error { model.error = error.localizedDescription; return }
                    guard let callback,
                          let ticket = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "ticket" })?.value else {
                        model.error = "授权回执无效"; return
                    }
                    do {
                        let login: LoginResult = try await self.api.request("/api/v1/auth/tesla/complete", method: "POST", body: ["ticket": ticket])
                        KeychainToken.save(login.token)
                        model.isDemo = false
                        UserDefaults.standard.set(false, forKey: "demoMode")
                        model.loggedIn = true
                        await model.refresh()
                    } catch { model.error = error.localizedDescription }
                }
            }
            session?.presentationContextProvider = self
            session?.prefersEphemeralWebBrowserSession = true
            session?.start()
        } catch { model.error = error.localizedDescription }
    }
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first?.keyWindow ?? ASPresentationAnchor()
    }
}

private struct StartResponse: Decodable {
    let authorizationUrl: String
    let state: String
}
