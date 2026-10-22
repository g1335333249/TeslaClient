# VoltMate 0.1.0

VoltMate 是中文 SwiftUI 特斯拉管家。仓库包含 Spring Boot 3.5 后端、MySQL 8.4 数据迁移、MQTT 遥测入口、iOS 工程和 Docker Compose。当前版本是可继续开发的 **0.1.0**，不是已通过实车验收的 V1.0。

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

## 当前实现范围与缺口

已实现：演示登录、应用会话、两辆 Mock 车辆、车辆总览、常见指令的模拟执行与审计、历史行程/充电及统计 API、iOS 五个主要页面、Tesla OAuth 授权码交换与 Token 加密/轮换框架、虚拟钥匙配对状态查询、经官方命令代理提交遥测配置、官方 MQTT topic 消费与 MySQL 原始事件/样本分离、基础行程和充电聚合、保留设置与位置删除。

仍需完成后才能宣称 V1.0：遥测配置与实车部署验收、车型与固件能力校验、充电计划、完整遥测乱序与批量处理、充电费率自动计费、权限变更同步、用户数据导出/删除、WebSocket 事件推送、所有界面状态与统计指标的补齐、更全面的自动化测试及实车验收。当前 Mock 历史数据是明确标识的演示数据。
