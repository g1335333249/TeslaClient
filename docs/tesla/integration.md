# Tesla 中国区接入

资料核对日期：2026-10-22。以 [中国区官方 Fleet API 文档](https://developer.tesla.cn/docs/fleet-api) 为最终依据。

## 端点

| 用途 | 中国区 |
|---|---|
| OAuth 授权 | `https://auth.tesla.cn/oauth2/v3/authorize` |
| OAuth Token | `https://auth.tesla.cn/oauth2/v3/token` |
| Fleet API | `https://fleet-api.prd.cn.vn.cloud.tesla.cn` |
| 虚拟钥匙配对 | `https://tesla.cn/_ak/<开发者域名>` |

官方 [第三方令牌说明](https://developer.tesla.cn/docs/fleet-api/authentication/third-party-tokens) 指出 refresh token 会轮换，旧 token 不能长期继续使用。请求范围包括 `openid offline_access vehicle_device_data vehicle_location vehicle_cmds vehicle_charging_cmds`。应按实际启用功能逐步缩小范围。

## 公钥和命令代理

运行 `scripts/generate-keys.sh` 生成 prime256v1 密钥对，或将现有 P-256 私钥安全导入 `infrastructure/secrets/fleet-key.pem`。公钥发布在 `https://<domain>/.well-known/appspecific/com.tesla.3p.public-key.pem`。按 [官方虚拟钥匙指南](https://developer.tesla.cn/docs/fleet-api/virtual-keys/developer-guide) 设置环境变量后运行 `scripts/register-partner.sh`（需要 jq）注册合作伙伴应用，并引导车主在 Tesla App 中配对。私钥只挂载到官方 [vehicle-command](https://github.com/teslamotors/vehicle-command) HTTP proxy。脚本会确认公钥支持 HTTP Range 请求。

## 遥测

仓库 `real` Compose profile 使用已核对拉取的官方 vehicle-command 与 fleet-telemetry 镜像摘要，后者配置见 `infrastructure/mqtt/fleet-server.json`。车辆通过公开有效 TLS 证书上的 mTLS WebSocket 连接；MQTT topic_base 为 `telemetry`，QoS 1。官方 MQTT topic 形式为 `telemetry/<VIN>/v/<field>`、`telemetry/<VIN>/connectivity` 等。后端只消费已绑定 VIN，保存原始事件和标准化样本。不要通过高频 `vehicle_data` 轮询模拟遥测。

配置 `TESLA_TELEMETRY_HOST` 为车端可访问的独立域名、`TESLA_TELEMETRY_PORT` 为实际 TLS 端口，并把该服务器的完整证书链保存到 `infrastructure/secrets/telemetry-ca.pem`。车主完成授权及虚拟钥匙配对后，显式调用 `POST /api/v1/vehicles/{id}/telemetry/configuration`。后端经官方 vehicle-command 代理提交配置并由代理签名；`GET` 查看同步状态，`DELETE` 断开配置。默认只申请行程、电量和充电字段，位置隐私设置关闭时不申请 Location。Tesla 对配置接口限制并发度为 1，生产部署需要在操作层串行调用与处理 429。当前流程尚未经过实车验收。

本机 profile 暂映射遥测端口 8443。实际车端接入应给遥测服务独立域名和 443 端口，或使用保留 mTLS 的四层负载均衡；不要通过 HTTP 反向代理转发车端 mTLS WebSocket。代理服务的 TLS 证书必须被后端 JVM 信任。配置证书、密钥和域名后执行 `docker compose --profile real up -d`。

当前仓库仍未完成真实 OAuth/配对及实车遥测验收。配置真实车辆前须完成密钥、域名、TLS、账号及车辆固件验收。官方镜像摘要需定期核对上游安全更新。

## 0.2.0 能力与数据时间

能力接口优先通过官方 `fleet_status` 获取虚拟钥匙配对、固件、遥测客户端版本和命令协议需求。某项指令未有足够证据时返回 `UNKNOWN`，不会仅按“Model Y L”名称推断。充电计划使用官方 `add_charge_schedule`、`remove_charge_schedule`，仅车辆确认后记为 `APPLIED`；无法从官方响应可靠读取的车辆已有计划，不伪装为本地已创建计划。

官方 Fleet Telemetry MQTT datastore 的指标消息是 JSON 值，通常不包含唯一事件 ID 和源时间。VoltMate 对带 `eventId`、`observedAt` 的 Mock/测试封套可严格去重与排序；对于官方原生值只能以接收时间排序，并在短时间窗口内做内容去重。数据质量字段会记录 `RECEIVED_ONLY`。因此真实车端乱序与丢失补偿仍需实车验收。`ACChargingEnergyIn` 是 AC 充电器测得的单次输入电量，`DCChargingEnergyIn` 是电池侧电量；两者分别存储，费用优先使用 AC 输入电量，其他来源会标识估算。
