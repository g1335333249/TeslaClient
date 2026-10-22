import SwiftUI
import Charts

struct ChargingPlansView: View {
    @EnvironmentObject var model: AppModel
    @State private var plans: [ChargingPlan] = []
    @State private var start = Calendar.current.date(from: DateComponents(hour: 23)) ?? Date()
    @State private var end = Calendar.current.date(from: DateComponents(hour: 7)) ?? Date()
    @State private var message: String?
    @State private var pendingDelete: Int64?
    @State private var loading = false
    var body: some View {
        Form {
            Section("车辆计划") {
                if loading { ProgressView("加载中…") }
                if plans.isEmpty { Text("暂无已提交计划").foregroundStyle(.secondary) }
                ForEach(plans) { plan in
                    VStack(alignment: .leading) {
                        Text("\(clock(plan.settings.startMinute)) 至 \(clock(plan.settings.endMinute)) · \(plan.settings.daysOfWeek)")
                        Text(plan.applyState == "APPLIED" ? "车辆已确认" : "未写入车辆 · \(plan.lastError ?? "失败")")
                            .font(.caption).foregroundStyle(plan.applyState == "APPLIED" ? .green : .orange)
                        Button("删除计划", role: .destructive) { pendingDelete = plan.id }.font(.caption)
                    }
                }
            }
            Section("新建充电计划") {
                DatePicker("开始时间", selection: $start, displayedComponents: .hourAndMinute)
                DatePicker("结束时间", selection: $end, displayedComponents: .hourAndMinute)
                Text("默认每天重复。车辆位置用于创建 Tesla 充电计划；请确认定位已授权。")
                    .font(.footnote).foregroundStyle(.secondary)
                if model.status?.onlineState != "ONLINE" { Text("车辆休眠或离线，暂不能提交计划").font(.caption).foregroundStyle(.orange) }
                if let ability = model.capabilities?.capabilities["ADD_CHARGE_SCHEDULE"], ability.state != "SUPPORTED" {
                    Text("能力：\(ability.state) · \(ability.reason)").font(.caption).foregroundStyle(.secondary)
                }
                Button("写入车辆") { Task { await add() } }
                    .disabled(loading || model.status?.onlineState != "ONLINE" || model.status?.latitude == nil || model.status?.longitude == nil ||
                              ["UNSUPPORTED", "PERMISSION_REQUIRED", "KEY_PAIRING_REQUIRED"].contains(model.capabilities?.state("ADD_CHARGE_SCHEDULE") ?? "UNKNOWN"))
            }
            if let message { Text(message).foregroundStyle(.orange) }
        }
        .navigationTitle("充电计划")
        .task { await load() }
        .confirmationDialog("确认删除车辆充电计划？", isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } })) {
            Button("删除", role: .destructive) { if let id = pendingDelete { Task { await remove(id) } }; pendingDelete = nil }
        }
    }
    private func clock(_ minute: Int?) -> String { guard let minute else { return "未设置" }; return String(format: "%02d:%02d", minute / 60, minute % 60) }
    private func minute(_ date: Date) -> Int { let parts = Calendar.current.dateComponents([.hour,.minute], from: date); return (parts.hour ?? 0) * 60 + (parts.minute ?? 0) }
    private func load() async {
        guard let id = model.selectedVehicle?.id else { return }
        loading = true; defer { loading = false }
        do { plans = try await APIClient.shared.request("/api/v1/vehicles/\(id)/charging/plans") }
        catch { message = error.localizedDescription }
    }
    private func add() async {
        guard let id = model.selectedVehicle?.id, let lat = model.status?.latitude, let lon = model.status?.longitude else { return }
        do {
            let plan: ChargingPlan = try await APIClient.shared.request("/api/v1/vehicles/\(id)/charging/plans", method: "POST",
                body: ["latitude": lat, "longitude": lon, "startMinute": minute(start), "endMinute": minute(end), "startEnabled": true, "endEnabled": true, "daysOfWeek": "ALL", "enabled": true, "oneTime": false])
            message = plan.applyState == "APPLIED" ? "计划已提交并确认" : "车辆未确认计划，请查看错误"
            await load()
        } catch { message = error.localizedDescription }
    }
    private func remove(_ planId: Int64) async {
        guard let id = model.selectedVehicle?.id else { return }
        do { let _: [String:String] = try await APIClient.shared.request("/api/v1/vehicles/\(id)/charging/plans/\(planId)", method: "DELETE"); await load() }
        catch { message = error.localizedDescription }
    }
}

struct ChargingCostsView: View {
    private struct CostDay: Identifiable { let day: String; let amount: Double; var id: String { day } }
    @EnvironmentObject var model: AppModel
    @State private var summary: ChargingCostSummary?
    @State private var costs: [Int64: ChargingCost] = [:]
    @State private var manualSession: Int64?
    @State private var manualAmount = ""
    @State private var message: String?
    @State private var loading = false
    var body: some View {
        List {
            if loading { ProgressView("计算费用…") }
            if let summary {
                Section("近 30 天") {
                    LabeledContent("充电费用", value: String(format: "¥%.2f", summary.total))
                    Text("\(summary.estimatedCount) 笔估算 · \(summary.unavailableCount) 笔数据不足").font(.caption).foregroundStyle(.secondary)
                    if !summary.daily.isEmpty {
                        Chart(summary.daily.map { CostDay(day: $0.key, amount: $0.value) }.sorted { $0.day < $1.day }) { item in
                            BarMark(x: .value("日期", item.day), y: .value("费用", item.amount))
                        }.frame(height: 180)
                    }
                    ForEach(summary.tariffBreakdown.keys.sorted(), id: \.self) { name in
                        LabeledContent(name, value: String(format: "¥%.2f（估算）", summary.tariffBreakdown[name] ?? 0))
                    }
                }
            }
            Section("每次充电") {
                ForEach(model.charging) { session in
                    VStack(alignment: .leading, spacing: 5) {
                        Text(session.startedAt, format: .dateTime.year().month().day().hour().minute())
                        if let cost = costs[session.id] {
                            Text(cost.amount.map { String(format: "¥%.2f", $0) } ?? "无法计算")
                            Text(cost.accuracy == "ACTUAL" ? "手填实际费用" : cost.accuracy == "ESTIMATED" ? "估算费用 · \(cost.source)" : "数据不足")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        Button("填写实际费用") { manualSession = session.id }
                    }
                }
            }
            if let message { Text(message).foregroundStyle(.orange) }
        }
        .navigationTitle("充电费用")
        .task { await load() }
        .alert("实际费用（元）", isPresented: Binding(get: { manualSession != nil }, set: { if !$0 { manualSession = nil } })) {
            TextField("金额", text: $manualAmount).keyboardType(.decimalPad)
            Button("保存") { if let id = manualSession { Task { await save(id) } }; manualSession = nil }
            Button("取消", role: .cancel) { manualSession = nil }
        }
    }
    private func load() async {
        guard let id = model.selectedVehicle?.id else { return }
        loading = true; defer { loading = false }
        do {
            summary = try await APIClient.shared.request("/api/v1/vehicles/\(id)/charging/cost-summary")
            for session in model.charging {
                costs[session.id] = try await APIClient.shared.request("/api/v1/vehicles/\(id)/charging/sessions/\(session.id)/cost")
            }
        } catch { message = error.localizedDescription }
    }
    private func save(_ session: Int64) async {
        guard let id = model.selectedVehicle?.id, let amount = Double(manualAmount) else { message = "请输入有效金额"; return }
        do { let cost: ChargingCost = try await APIClient.shared.request("/api/v1/vehicles/\(id)/charging/sessions/\(session)/cost", method: "PUT", body: ["amount": amount]); costs[session] = cost; await load() }
        catch { message = error.localizedDescription }
    }
}

struct PersonalDataView: View {
    @EnvironmentObject var model: AppModel
    @State private var exportURL: URL?
    @State private var message: String?
    @State private var deletion: String?
    var body: some View {
        Form {
            Section("导出 VoltMate 保存的数据") {
                Button("导出 JSON") { Task { await export("json") } }
                Button("导出 CSV 压缩包") { Task { await export("csv") } }
                if let exportURL { ShareLink("分享导出文件", item: exportURL) }
            }
            Section("删除历史数据") {
                Button("删除当前车辆行程与轨迹", role: .destructive) { deletion = "trips" }
                Button("删除当前车辆充电历史", role: .destructive) { deletion = "charging" }
                Button("删除全部历史与个人设置", role: .destructive) { deletion = "data" }
            }
            Section("注销") {
                Button("删除 VoltMate 账户及个人数据", role: .destructive) { deletion = "account" }
                Text("删除仅作用于 VoltMate 服务端，不会清除车机中的数据。共享车辆数据需先解除共享。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            if let message { Text(message).foregroundStyle(.orange) }
        }
        .navigationTitle("个人数据")
        .confirmationDialog("删除后无法恢复，确认继续？", isPresented: Binding(get: { deletion != nil }, set: { if !$0 { deletion = nil } })) {
            Button("确认删除", role: .destructive) { if let type = deletion { Task { await remove(type) } }; deletion = nil }
        }
    }
    private func export(_ format: String) async {
        do {
            let data = try await APIClient.shared.requestData("/api/v1/privacy/export?format=\(format)", accept: format == "csv" ? "application/zip" : "application/json")
            let filename = format == "json" ? "voltmate-data.json" : "voltmate-data-csv.zip"
            let url = FileManager.default.temporaryDirectory.appendingPathComponent(filename)
            if let exportURL { try? FileManager.default.removeItem(at: exportURL) }
            try data.write(to: url, options: .atomic); exportURL = url; message = "导出文件已就绪"
        } catch { message = error.localizedDescription }
    }
    private func remove(_ type: String) async {
        guard let id = model.selectedVehicle?.id else { return }
        let path = type == "account" ? "/api/v1/privacy/account" : type == "data" ? "/api/v1/privacy/data" : "/api/v1/privacy/vehicles/\(id)/\(type)"
        do {
            _ = try await APIClient.shared.requestData(path, method: "DELETE", confirmDelete: true)
            if let exportURL { try? FileManager.default.removeItem(at: exportURL) }
            exportURL = nil
            if type == "account" { await model.logout() } else { await model.refresh() }
            message = "已删除 VoltMate 服务端数据"
        } catch { message = error.localizedDescription }
    }
}
