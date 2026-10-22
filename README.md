# VoltMate 0.2.0

VoltMate 是中文 SwiftUI 特斯拉管家。仓库包含 Spring Boot 3.5 后端、MySQL 8.4 数据迁移、MQTT 遥测入口、iOS 工程和 Docker Compose。当前开发版本为 **0.2.0**，尚未通过 V1.0 实车验收。

## 本地 Mock 启动

1. `cp .env.example .env`
2. `docker compose up -d --build`
3. 等待 `http://localhost:8080/actuator/health` 返回 `UP`。
4. 用 Xcode 打开 `ios/VoltMate.xcodeproj`，选择 iPhone Simulator 运行，点“体验演示模式”。
5. Swagger UI：`http://localhost:8080/swagger-ui/index.html`。

模拟器访问宿主机使用 `http://localhost:8080`。真机必须在设置中填入可访问的 HTTPS 服务地址。默认 iOS 最低版本为 17.0。Mock 模式明确标识“演示数据”，所有指令仅修改模拟车辆。

## 真实 Tesla 模式

真实模式要求中国区 Tesla 开发者应用、允许的 HTTPS 回调域名、Client ID/Secret、公开的公钥和私有命令代理。填写 `.env` 中的 Tesla 配置，并设置 `TESLA_MODE=real`。缺少配置时后端拒绝启动。**现阶段真实模式尚未经过实车端到端验证；请勿用于实际车辆控制。**

中国区 OAuth 使用 `auth.tesla.cn`，Fleet API 使用 `fleet-api.prd.cn.vn.cloud.tesla.cn`。虚拟钥匙公钥发布于 `/.well-known/appspecific/com.tesla.3p.public-key.pem`，私钥仅存在服务端密钥挂载，不进入 App 和 Git。配对链接为 `https://tesla.cn/_ak/<domain>`。具体接入步骤见 [Tesla 文档](docs/tesla/integration.md)。

## 项目结构

- `backend/`：Java 21 模块化单体、REST API、Flyway、MQTT。
- `ios/`：SwiftUI 工程；`project.yml` 可用 XcodeGen 再生成工程。
- `infrastructure/`：MySQL/MQTT/Nginx 配置。
- `scripts/`：MySQL 备份与恢复。
- `.github/workflows/`：后端与 iOS 构建验证。

## 0.2.0 功能

新增 Fleet 状态能力快照与命令前校验；遥测原始事件幂等、逐信号时间顺序和无效时间隔离；Tesla 充电计划提交与结果记录；按中国时区、日期及电价时段估算电费，支持手动填写实际费用；JSON/CSV 个人数据导出及确认后删除；按车辆授权订阅的 WebSocket 通知。iOS 增加充电计划、费用和个人数据入口，前台订阅车辆事件。API 详情见 [0.2 接口文档](docs/api/0.2.md) 和 [WebSocket 协议](docs/api/websocket.md)。

数据库只增量执行 Flyway V6–V9，不修改 V1–V5，也不清空 MySQL 卷。CSV 导出为包含多个表的 ZIP。费用缺少分时电量时按总电量和时长估算，并标记“估算费用”。删除全部历史与个人设置后会暂停车辆采集，可在设置中再次开启。

本地构建与验收状态见 [0.2 验收记录](docs/deployment/0.2-validation.md)。

## 当前实现范围与缺口

已实现：演示登录、应用会话、两辆 Mock 车辆、车辆总览、常见指令的模拟执行与审计、历史行程/充电及统计 API、iOS 五个主要页面、Tesla OAuth 授权码交换与 Token 加密/轮换框架、虚拟钥匙配对状态查询、经官方命令代理提交遥测配置、官方 MQTT topic 消费与 MySQL 原始事件/样本分离、基础行程和充电聚合、保留设置与位置删除。

仍需完成后才能宣称 V1.0：真实 OAuth、钥匙配对、充电计划与遥测的实车联调；官方 MQTT 原生指标不带源时间和唯一消息 ID 时的顺序验证；缺失遥测的行程补偿及跨天统计；实时分段电量采样与更准确的峰谷账单；更多权限变更、共享数据删除和 WebSocket 负载验证；完整 iOS 状态与更全面自动化测试。当前 Mock 历史数据是明确标识的演示数据。
