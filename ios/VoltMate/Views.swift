import SwiftUI
import Charts
import MapKit

private let accent = Color(red: 0.20, green: 0.70, blue: 0.87)

struct WelcomeView: View {
    @EnvironmentObject var model: AppModel
    @AppStorage("serverURL") private var serverURL = "http://localhost:8080"
    var body: some View {
        ZStack {
            LinearGradient(colors: [Color(red: 0.04, green: 0.09, blue: 0.17), Color(red: 0.08, green: 0.20, blue: 0.31)], startPoint: .top, endPoint: .bottom).ignoresSafeArea()
            VStack(alignment: .leading, spacing: 24) {
                Spacer()
                Image(systemName: "bolt.car.fill").font(.system(size: 74)).foregroundStyle(accent)
                Text("VoltMate").font(.system(size: 44, weight: .bold, design: .rounded)).foregroundStyle(.white)
                Text("你的特斯拉智能管家").font(.title2).foregroundStyle(.white.opacity(0.8))
                Text("车辆状态、行程、充电与能耗，一处掌握。").foregroundStyle(.white.opacity(0.65))
                Spacer()
                TextField("服务端地址", text: $serverURL)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.URL)
                    .padding()
                    .background(.white.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                    .foregroundStyle(.white)
                Button { Task { await model.demoLogin() } } label: {
                    Label("体验演示模式", systemImage: "sparkles").frame(maxWidth: .infinity).padding()
                }.buttonStyle(.borderedProminent)
                Button("连接 Tesla 账户") { Task { await model.connectTesla() } }
                    .frame(maxWidth: .infinity).padding().foregroundStyle(.white)
                if model.loading { ProgressView().tint(.white) }
                if let error = model.error { Text(error).foregroundStyle(.orange).font(.footnote) }
            }.padding(28)
        }
        .preferredColorScheme(.dark)
    }
}

struct MainTabs: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        TabView {
            NavigationStack { HomeView() }.tabItem { Label("车辆", systemImage: "car.side.fill") }
            NavigationStack { TripsView() }.tabItem { Label("行程", systemImage: "point.topleft.down.to.point.bottomright.curvepath") }
            NavigationStack { ChargingView() }.tabItem { Label("充电", systemImage: "bolt.fill") }
            NavigationStack { StatisticsView() }.tabItem { Label("统计", systemImage: "chart.bar.fill") }
            NavigationStack { SettingsView() }.tabItem { Label("设置", systemImage: "gearshape.fill") }
        }
        .overlay(alignment: .top) {
            if model.isDemo { Text("演示数据").font(.caption.bold()).padding(.horizontal, 14).padding(.vertical, 5).background(.orange, in: Capsule()).padding(.top, 4) }
        }
    }
}

struct StatusTile: View {
    let title: String
    let value: String
    let icon: String
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Image(systemName: icon).foregroundStyle(accent)
            Text(value).font(.title3.bold()).lineLimit(1).minimumScaleFactor(0.8)
            Text(title).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading).padding(16)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18))
    }
}

struct HomeView: View {
    @EnvironmentObject var model: AppModel
    @State private var pendingCommand: String?
    private let columns = [GridItem(.flexible()), GridItem(.flexible())]
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                if let vehicle = model.selectedVehicle {
                    HStack {
                        VStack(alignment: .leading) {
                            Text(vehicle.name).font(.largeTitle.bold())
                            Text(vehicle.model ?? "车型暂不支持").foregroundStyle(.secondary)
                        }
                        Spacer()
                        Menu {
                            ForEach(model.vehicles) { car in Button(car.name) { Task { await model.select(car) } } }
                        } label: { Image(systemName: "chevron.down.circle.fill").font(.title2) }
                    }
                    CarSilhouette().frame(height: 170).padding(.vertical, 8)
                    HStack(alignment: .firstTextBaseline) {
                        Text(model.status?.soc.map { "\(Int($0))%" } ?? "暂不支持").font(.system(size: 50, weight: .bold, design: .rounded))
                        Spacer()
                        Text(model.status?.rangeKm.map { "续航约 \(Int($0)) km" } ?? "续航暂不支持").foregroundStyle(.secondary)
                    }
                    HStack {
                        Circle().fill(model.status?.onlineState == "ONLINE" ? .green : .gray).frame(width: 8, height: 8)
                        Text(model.status?.onlineState == "ONLINE" ? "在线" : "休眠或离线")
                        Spacer()
                        if let date = model.status?.observedAt { Text(date, style: .relative).foregroundStyle(.secondary) }
                    }.font(.caption)
                    LazyVGrid(columns: columns, spacing: 12) {
                        StatusTile(title: "车内温度", value: model.status?.insideC.map { "\(Int($0))°C" } ?? "暂不支持", icon: "thermometer.medium")
                        StatusTile(title: "车外温度", value: model.status?.outsideC.map { "\(Int($0))°C" } ?? "暂不支持", icon: "sun.max")
                        StatusTile(title: "车门锁", value: model.status?.locked.map { $0 ? "已上锁" : "已解锁" } ?? "暂不支持", icon: "lock")
                        StatusTile(title: "充电", value: model.status?.chargingState == "CHARGING" ? "充电中" : "未充电", icon: "bolt")
                    }
                    NavigationLink {
                        VehicleDetailView()
                    } label: {
                        Label("查看车辆详情", systemImage: "list.bullet.rectangle").frame(maxWidth: .infinity).padding(12)
                    }.buttonStyle(.bordered)
                    Text("快捷操作").font(.title2.bold())
                    LazyVGrid(columns: columns, spacing: 12) {
                        commandButton("解锁", "lock.open", "UNLOCK", sensitive: true)
                        commandButton("上锁", "lock", "LOCK")
                        commandButton("空调开启", "fanblades", "CLIMATE_ON")
                        commandButton("空调关闭", "fanblades.slash", "CLIMATE_OFF")
                        commandButton("闪灯", "light.beacon.max", "FLASH")
                        commandButton("鸣笛", "speaker.wave.3", "HONK")
                        commandButton("后备箱", "car.rear", "TRUNK", sensitive: true)
                        commandButton("开始充电", "bolt.car", "CHARGE_START")
                    }
                } else if model.loading { ProgressView("正在加载车辆…").frame(maxWidth: .infinity).padding(50) }
                else { ContentUnavailableView("暂无车辆", systemImage: "car.side", description: Text("请绑定 Tesla 车辆或进入演示模式")) }
                if let error = model.error { Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(.orange).font(.footnote) }
                if let feedback = model.commandFeedback { Text(feedback).font(.footnote).foregroundStyle(.secondary) }
            }.padding()
        }
        .navigationTitle("车辆总览")
        .toolbar { Button { Task { await model.refresh() } } label: { Image(systemName: "arrow.clockwise") } }
        .refreshable { await model.refresh() }
        .confirmationDialog("确认执行该车辆操作？", isPresented: Binding(get: { pendingCommand != nil }, set: { if !$0 { pendingCommand = nil } })) {
            Button("确认执行", role: .destructive) { if let command = pendingCommand { Task { await model.command(command) } }; pendingCommand = nil }
            Button("取消", role: .cancel) { pendingCommand = nil }
        }
    }
    private func commandButton(_ title: String, _ icon: String, _ type: String, sensitive: Bool = false) -> some View {
        Button {
            if sensitive { pendingCommand = type } else { Task { await model.command(type) } }
        } label: {
            Label(title, systemImage: icon).frame(maxWidth: .infinity).padding(15)
        }.buttonStyle(.bordered)
            .disabled(model.loading || model.status?.onlineState != "ONLINE" ||
                      ["UNSUPPORTED","PERMISSION_REQUIRED","KEY_PAIRING_REQUIRED"].contains(model.capabilities?.state(type) ?? "UNKNOWN"))
    }
}

struct VehicleDetailView: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        Form {
            if let status = model.status {
                Section("数据状态") {
                    LabeledContent("来源", value: status.mock ? "演示数据" : status.source)
                    LabeledContent("车辆连接", value: status.onlineState == "ONLINE" ? "在线" : "休眠或离线")
                    if let received = status.receivedAt { LabeledContent("接收时间", value: received.formatted(date: .abbreviated, time: .shortened)) }
                }
                Section("车身") {
                    LabeledContent("车锁", value: status.locked.map { $0 ? "已上锁" : "已解锁" } ?? "暂不支持")
                    LabeledContent("车门", value: "暂不支持")
                    LabeledContent("车窗", value: "暂不支持")
                }
                Section("空调") {
                    LabeledContent("状态", value: status.climateOn.map { $0 ? "已开启" : "已关闭" } ?? "暂不支持")
                    LabeledContent("车内温度", value: status.insideC.map { "\(Int($0))°C" } ?? "暂不支持")
                    LabeledContent("车外温度", value: status.outsideC.map { "\(Int($0))°C" } ?? "暂不支持")
                }
                Section("电池与充电") {
                    LabeledContent("电量", value: status.soc.map { "\(Int($0))%" } ?? "暂不支持")
                    LabeledContent("续航", value: status.rangeKm.map { "\(Int($0)) km" } ?? "暂不支持")
                    LabeledContent("充电状态", value: status.chargingState ?? "暂不支持")
                    LabeledContent("充电上限", value: status.chargeLimit.map { "\($0)%" } ?? "暂不支持")
                }
                Section("车辆信息") {
                    LabeledContent("VIN", value: model.selectedVehicle?.vin ?? "暂不支持")
                    LabeledContent("固件", value: model.selectedVehicle?.firmware ?? "暂不支持")
                    LabeledContent("里程", value: status.odometerKm.map { String(format: "%.0f km", $0) } ?? "暂不支持")
                }
            } else {
                ContentUnavailableView("暂无状态", systemImage: "car.side", description: Text(model.error ?? "请稍后刷新"))
            }
        }
        .navigationTitle("车辆详情")
        .refreshable { await model.refresh() }
    }
}

struct CarSilhouette: View {
    var body: some View {
        GeometryReader { g in
            ZStack {
                RoundedRectangle(cornerRadius: 80).fill(accent.opacity(0.12)).frame(width: g.size.width * 0.9, height: 115)
                Image(systemName: "car.side.fill").resizable().scaledToFit().foregroundStyle(.primary.opacity(0.85)).padding(.horizontal, 25)
            }.frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .accessibilityLabel("车辆轮廓示意图")
    }
}

struct TripsView: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        List {
            if model.trips.isEmpty { ContentUnavailableView("暂无行程", systemImage: "map", description: Text("收到车辆行驶遥测后会显示在这里")) }
            ForEach(model.trips) { trip in
                NavigationLink {
                    TripDetailView(trip: trip)
                } label: {
                    VStack(alignment: .leading, spacing: 7) {
                        Text(trip.startedAt, format: .dateTime.year().month().day().hour().minute()).font(.headline)
                        HStack {
                            Text(trip.distanceKm.map { String(format: "%.1f km", $0) } ?? "里程不完整")
                            Spacer()
                            Text(trip.energyKwh.map { String(format: "%.1f kWh", $0) } ?? "能耗暂不支持")
                        }.font(.subheadline).foregroundStyle(.secondary)
                    }.padding(.vertical, 4)
                }
            }
        }.navigationTitle("行程").refreshable { await model.refresh() }
    }
}

struct TripDetailView: View {
    let trip: Trip
    @State private var detail: TripDetail?
    @State private var error: String?
    @EnvironmentObject var model: AppModel
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                if let points = detail?.points, points.count >= 2 {
                    Map {
                        MapPolyline(coordinates: points.map { CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude) })
                            .stroke(accent, lineWidth: 4)
                    }.frame(height: 280).clipShape(RoundedRectangle(cornerRadius: 18))
                } else {
                    ContentUnavailableView("轨迹不完整", systemImage: "map", description: Text("缺少真实定位点，无法绘制路径"))
                }
                Text(trip.startedAt, format: .dateTime.year().month().day().hour().minute()).font(.title2.bold())
                Text("里程：\(trip.distanceKm.map { String(format: "%.1f km", $0) } ?? "暂不支持")")
                Text("耗电：\(trip.energyKwh.map { String(format: "%.1f kWh", $0) } ?? "暂不支持")")
                if let error { Text(error).foregroundStyle(.orange) }
            }.padding()
        }.navigationTitle("行程详情").task {
            guard let id = model.selectedVehicle?.id else { return }
            do { detail = try await APIClient.shared.request("/api/v1/vehicles/\(id)/trips/\(trip.id)") }
            catch { self.error = error.localizedDescription }
        }
    }
}

struct ChargingView: View {
    @EnvironmentObject var model: AppModel
    @State private var limit = 80.0
    var body: some View {
        List {
            Section("当前充电") {
                LabeledContent("状态", value: model.status?.chargingState == "CHARGING" ? "充电中" : "未充电")
                LabeledContent("充电功率", value: model.status?.chargingKw.map { String(format: "%.1f kW", $0) } ?? "暂不支持")
                LabeledContent("电量", value: model.status?.soc.map { "\(Int($0))%" } ?? "暂不支持")
                HStack {
                    Button("开始充电") { Task { await model.command("CHARGE_START") } }
                        .disabled(["UNSUPPORTED","PERMISSION_REQUIRED","KEY_PAIRING_REQUIRED"].contains(model.capabilities?.state("CHARGE_START") ?? "UNKNOWN"))
                    Spacer()
                    Button("停止充电") { Task { await model.command("CHARGE_STOP") } }
                        .disabled(["UNSUPPORTED","PERMISSION_REQUIRED","KEY_PAIRING_REQUIRED"].contains(model.capabilities?.state("CHARGE_STOP") ?? "UNKNOWN"))
                }.disabled(model.status?.onlineState != "ONLINE")
                VStack(alignment: .leading) {
                    Text("充电上限 \(Int(limit))%")
                    Slider(value: $limit, in: 50...100, step: 1)
                    Button("设置上限") { Task { await model.command("SET_CHARGE_LIMIT", value: Int(limit)) } }
                        .disabled(["UNSUPPORTED","PERMISSION_REQUIRED","KEY_PAIRING_REQUIRED"].contains(model.capabilities?.state("SET_CHARGE_LIMIT") ?? "UNKNOWN"))
                }
            }
            Section("计划与费用") {
                NavigationLink("充电计划") { ChargingPlansView() }
                NavigationLink("充电费用") { ChargingCostsView() }
            }
            Section("历史充电") {
                if model.charging.isEmpty { Text("暂无充电记录").foregroundStyle(.secondary) }
                ForEach(model.charging) { item in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(item.startedAt, format: .dateTime.year().month().day()).font(.headline)
                        Text("电网输入 \(item.gridKwh.map { String(format: "%.1f", $0) } ?? "—") kWh · 电池增量 \(item.batteryKwh.map { String(format: "%.1f", $0) } ?? "—") kWh").font(.caption)
                        Text("估算费用 ¥\(item.estimatedCost.map { String(format: "%.2f", $0) } ?? "—")").font(.caption).foregroundStyle(.secondary)
                    }.padding(.vertical, 4)
                }
            }
        }.navigationTitle("充电").onAppear { limit = Double(model.status?.chargeLimit ?? 80) }.refreshable { await model.refresh() }
    }
}

struct StatisticsView: View {
    @EnvironmentObject var model: AppModel
    @State private var metric = 0
    @State private var range = 1
    @State private var from = Calendar.current.date(byAdding: .day, value: -30, to: Date()) ?? Date()
    @State private var to = Date()
    @State private var daily: [DailyStat] = []
    @State private var monthly: [MonthlyStat] = []
    @State private var costSummary: ChargingCostSummary?
    @State private var error: String?
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                Picker("时间范围", selection: $range) {
                    Text("7 天").tag(0); Text("30 天").tag(1); Text("本月").tag(2); Text("本年").tag(3); Text("自定义").tag(4)
                }.pickerStyle(.menu)
                if range == 4 {
                    DatePicker("开始", selection: $from, displayedComponents: .date)
                    DatePicker("结束", selection: $to, displayedComponents: .date)
                    Button("应用日期") { Task { await load() } }
                }
                Picker("指标", selection: $metric) {
                    Text("里程").tag(0); Text("能耗").tag(1); Text("充电").tag(2); Text("费用").tag(3)
                }.pickerStyle(.segmented)
                if daily.isEmpty && (metric != 3 || costSummary?.daily.isEmpty != false) { ContentUnavailableView("暂无统计数据", systemImage: "chart.bar", description: Text("车辆数据会逐日汇总")) }
                else {
                    if metric == 3, let costSummary {
                        Chart(costSummary.daily.keys.sorted(), id: \.self) { day in
                            BarMark(x: .value("日期", day), y: .value("费用", costSummary.daily[day] ?? 0))
                                .foregroundStyle(accent.gradient)
                        }.frame(height: 260)
                    } else {
                        Chart(daily) { day in
                            BarMark(x: .value("日期", day.day), y: .value("数值", value(day)))
                                .foregroundStyle(accent.gradient)
                        }.frame(height: 260)
                    }
                    Text("所选时段合计").font(.title2.bold())
                    Text(String(format: "%.1f %@", metric == 3 ? (costSummary?.total ?? 0) : daily.reduce(0) { $0 + value($1) }, unit))
                        .font(.system(size: 38, weight: .bold, design: .rounded))
                    if !monthly.isEmpty {
                        Text("月度里程趋势").font(.title2.bold())
                        Chart(monthly) { item in
                            LineMark(x: .value("月份", item.month), y: .value("里程", item.distanceKm))
                                .foregroundStyle(accent)
                        }.frame(height: 180)
                    }
                    Text("所有图表数据来自服务端 MySQL 汇总。").font(.caption).foregroundStyle(.secondary)
                }
                if let error { Text(error).foregroundStyle(.orange) }
            }.padding()
        }
        .navigationTitle("统计")
        .task { await load() }
        .onChange(of: range) { _, _ in Task { await load() } }
        .refreshable { await load() }
    }
    private var unit: String { ["km", "kWh", "kWh", "元"][metric] }
    private func value(_ day: DailyStat) -> Double { [day.distanceKm, day.drivingKwh, day.chargingKwh, day.chargingCost][metric] }
    private func load() async {
        guard let id = model.selectedVehicle?.id else { return }
        let today = Date()
        let calendar = Calendar.current
        let start: Date = switch range {
        case 0: calendar.date(byAdding: .day, value: -6, to: today) ?? today
        case 2: calendar.date(from: calendar.dateComponents([.year, .month], from: today)) ?? today
        case 3: calendar.date(from: calendar.dateComponents([.year], from: today)) ?? today
        case 4: from
        default: calendar.date(byAdding: .day, value: -29, to: today) ?? today
        }
        let end = range == 4 ? to : today
        let path = "/api/v1/vehicles/\(id)/statistics/daily?from=\(DateFormatter.day.string(from: start))&to=\(DateFormatter.day.string(from: end))"
        do {
            daily = try await APIClient.shared.request(path)
            monthly = try await APIClient.shared.request("/api/v1/vehicles/\(id)/statistics/monthly")
            costSummary = try await APIClient.shared.request("/api/v1/vehicles/\(id)/charging/cost-summary?from=\(DateFormatter.day.string(from: start))&to=\(DateFormatter.day.string(from: end))")
            error = nil
        } catch { self.error = error.localizedDescription }
    }
}

struct SettingsView: View {
    @EnvironmentObject var model: AppModel
    @AppStorage("serverURL") private var serverURL = "http://localhost:8080"
    @State private var settings = UserSettings(retentionDays: 90, locationEnabled: true, sampleIntervalSeconds: 60)
    @State private var tariffs: [Tariff] = []
    @State private var accounts: [TeslaAccount] = []
    @State private var consentURL: String?
    @State private var tariffName = "谷时"
    @State private var tariffPrice = "0.55"
    @State private var tariffStart = "00:00"
    @State private var tariffEnd = "00:00"
    @State private var message: String?
    @State private var confirmLocationDelete = false
    @State private var accountToUnlink: TeslaAccount?
    var body: some View {
        Form {
            Section("车辆") {
                ForEach(model.vehicles) { vehicle in
                    NavigationLink {
                        KeyPairingView(vehicle: vehicle)
                    } label: {
                        HStack { Text(vehicle.name); Spacer(); Text(vehicle.keyState == "PAIRED" ? "已配对" : "待确认").foregroundStyle(.secondary) }
                    }
                }
            }
            Section("连接") {
                TextField("服务器地址", text: $serverURL).textInputAutocapitalization(.never).keyboardType(.URL)
                LabeledContent("模式", value: model.isDemo ? "演示数据" : "真实车辆")
                Button("刷新数据") { Task { await model.refresh() } }
            }
            if !model.isDemo {
                Section("Tesla 授权") {
                    if accounts.isEmpty { Text("尚未关联 Tesla 账户").foregroundStyle(.secondary) }
                    ForEach(accounts) { account in
                        VStack(alignment: .leading) {
                            Text("\(account.region) · \(account.authorizationState)")
                            Text(account.scopes).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    Button("重新授权") { Task { await model.connectTesla() } }
                    if let consentURL, let url = URL(string: consentURL) {
                        Link("在 Tesla 官网撤销应用授权", destination: url)
                    }
                    ForEach(accounts) { account in
                        Button("解除账户 \(account.id) 授权", role: .destructive) { accountToUnlink = account }
                    }
                }
            }
            Section("数据与隐私") {
                NavigationLink("个人数据导出与删除") { PersonalDataView() }
                Toggle("保存车辆位置", isOn: $settings.locationEnabled)
                Stepper("保留 \(settings.retentionDays) 天", value: $settings.retentionDays, in: 7...3650, step: 7)
                Stepper("采样间隔 \(settings.sampleIntervalSeconds) 秒", value: $settings.sampleIntervalSeconds, in: 30...3600, step: 30)
                Button("保存设置") {
                    Task {
                        do {
                            let saved: UserSettings = try await APIClient.shared.request("/api/v1/settings", method: "PUT",
                                body: ["retentionDays": settings.retentionDays, "locationEnabled": settings.locationEnabled, "sampleIntervalSeconds": settings.sampleIntervalSeconds])
                            settings = saved; message = "设置已保存"
                        } catch { message = error.localizedDescription }
                    }
                }
                Button("删除当前位置与历史轨迹", role: .destructive) { confirmLocationDelete = true }
                if let message { Text(message).font(.caption).foregroundStyle(.secondary) }
            }
            Section("峰谷电价") {
                ForEach(tariffs) { tariff in
                    HStack { Text("\(tariff.name) · \(tariff.startsLocal.prefix(5))–\(tariff.endsLocal.prefix(5))"); Spacer(); Text(String(format: "¥%.2f/kWh", tariff.pricePerKwh)).foregroundStyle(.secondary) }
                }.onDelete { offsets in
                    let targets = offsets.map { tariffs[$0] }
                    Task {
                        for tariff in targets {
                            let _: [String: String]? = try? await APIClient.shared.request("/api/v1/settings/tariffs/\(tariff.id)", method: "DELETE")
                        }
                        await load()
                    }
                }
                TextField("名称", text: $tariffName)
                TextField("电价（元/kWh）", text: $tariffPrice).keyboardType(.decimalPad)
                TextField("开始时间 HH:mm", text: $tariffStart).keyboardType(.numbersAndPunctuation)
                TextField("结束时间 HH:mm", text: $tariffEnd).keyboardType(.numbersAndPunctuation)
                Text("开始与结束均为 00:00 表示全天；结束时间早于开始时间表示跨午夜。")
                    .font(.caption).foregroundStyle(.secondary)
                Button("添加电价时段") {
                    Task {
                        do {
                            let _: Tariff = try await APIClient.shared.request("/api/v1/settings/tariffs", method: "POST",
                                body: ["name": tariffName, "pricePerKwh": Double(tariffPrice) ?? 0, "startsLocal": tariffStart, "endsLocal": tariffEnd, "timezone": "Asia/Shanghai"])
                            await load()
                        } catch { message = error.localizedDescription }
                    }
                }
            }
            Section("关于") {
                LabeledContent("版本", value: "0.2.0")
                Text("位置与行程数据由你控制，请在服务端配置保留周期。").font(.footnote).foregroundStyle(.secondary)
            }
            Button("退出登录", role: .destructive) { Task { await model.logout() } }
        }
        .navigationTitle("设置")
        .task { await load() }
        .confirmationDialog("将删除此车辆的历史轨迹和最新位置，确定继续？", isPresented: $confirmLocationDelete) {
            Button("删除位置数据", role: .destructive) {
                Task {
                    guard let id = model.selectedVehicle?.id else { return }
                    do {
                        let _: [String: String] = try await APIClient.shared.request("/api/v1/vehicles/\(id)/location-history", method: "DELETE")
                        message = "位置数据已删除"
                        await model.refresh()
                    } catch { message = error.localizedDescription }
                }
            }
        }
        .alert(item: $accountToUnlink) { account in
            Alert(title: Text("解除 Tesla 授权？"), message: Text("此账户关联的车辆将无法再执行远程指令。"),
                  primaryButton: .destructive(Text("解除")) {
                      Task {
                          do {
                              let _: [String: String] = try await APIClient.shared.request("/api/v1/auth/tesla/accounts/\(account.id)", method: "DELETE")
                              await load()
                          } catch { message = error.localizedDescription }
                      }
                  }, secondaryButton: .cancel())
        }
    }
    private func load() async {
        do {
            settings = try await APIClient.shared.request("/api/v1/settings")
            tariffs = try await APIClient.shared.request("/api/v1/settings/tariffs")
            if !model.isDemo { accounts = try await APIClient.shared.request("/api/v1/auth/tesla/accounts") }
            if !model.isDemo {
                let consent: [String: String] = try await APIClient.shared.request("/api/v1/auth/tesla/consent-management")
                consentURL = consent["url"]
            }
        } catch { message = error.localizedDescription }
    }
}

struct KeyPairingView: View {
    let vehicle: Vehicle
    @EnvironmentObject var model: AppModel
    @Environment(\.openURL) private var openURL
    @State private var capabilities: VehicleCapabilities?
    @State private var error: String?
    @State private var telemetryMessage: String?
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Image(systemName: "key.horizontal.fill").font(.system(size: 50)).foregroundStyle(accent)
                Text("虚拟钥匙配对").font(.largeTitle.bold())
                Text("在 Tesla 官方 App 中确认将此应用的公钥添加到车辆。配对后才能发送需要签名的远程指令。")
                    .foregroundStyle(.secondary)
                StatusTile(title: "钥匙状态", value: statusText, icon: "checkmark.shield")
                if let url = capabilities?.pairingUrl, !url.isEmpty {
                    Button("打开 Tesla 配对页面") { if let link = URL(string: url) { openURL(link) } }
                        .buttonStyle(.borderedProminent)
                }
                Button("查询配对状态") { Task { await load() } }.buttonStyle(.bordered)
                if capabilities?.keyState == "PAIRED" && !model.isDemo {
                    Button("配置车辆遥测") { Task { await configureTelemetry() } }.buttonStyle(.bordered)
                    Text("车辆数据会通过已配置的安全服务器传输；请先在服务端设置遥测域名与证书。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                if let telemetryMessage { Text(telemetryMessage).foregroundStyle(.secondary) }
                if let error { Text(error).foregroundStyle(.orange) }
            }.padding()
        }.navigationTitle(vehicle.name).task { await load() }
    }
    private var statusText: String {
        switch capabilities?.keyState {
        case "PAIRED": "已配对"
        case "UNPAIRED": "未配对"
        default: "暂无法确认"
        }
    }
    private func load() async {
        do { capabilities = try await APIClient.shared.request("/api/v1/vehicles/\(vehicle.id)/capabilities") }
        catch { self.error = error.localizedDescription }
    }
    private func configureTelemetry() async {
        do {
            let _: TelemetryResponse = try await APIClient.shared.request("/api/v1/vehicles/\(vehicle.id)/telemetry/configuration", method: "POST")
            telemetryMessage = "遥测配置已提交，请稍后查询车辆同步状态"
        } catch { telemetryMessage = error.localizedDescription }
    }
}

private struct TelemetryResponse: Decodable {}
