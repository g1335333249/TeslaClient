# 部署说明

## 本地演示

在仓库根目录执行 `cp .env.example .env`，然后执行 `docker compose up -d --build`。等待 `docker compose ps` 中 MySQL、Redis、MQTT 和后端健康，再访问 `http://localhost:8080/actuator/health` 与 Swagger UI。默认后端仅监听宿主机 `127.0.0.1:8080`，Mock 指令不会调用 Tesla。

执行后端 Testcontainers 集成测试与 Compose 镜像重建时，建议顺序运行；它们都要启动 MySQL 容器。本地 Docker Desktop 应预留足够内存和磁盘空间。

## 真实车辆

1. 准备中国区 Tesla 开发者应用、受控 HTTPS 域名、Tesla OAuth 回调地址，以及车主授权。
2. 运行 `scripts/generate-keys.sh`，将公钥公开在 `/.well-known/appspecific/com.tesla.3p.public-key.pem`，并完成 Partner 注册。密钥文件存放在 Git 忽略的 `infrastructure/secrets/` 中。
3. 配置公开有效的 Nginx TLS 证书与 Tesla 命令代理 TLS 证书；后端 JVM 必须信任代理证书，代理证书的 SAN 必须覆盖 `TESLA_PROXY_URL` 主机名。
4. 为 Fleet Telemetry 配置独立公网域名和车端可用的 443/TLS 端口。将该证书完整链写入 `infrastructure/secrets/telemetry-ca.pem`，设置 `TESLA_TELEMETRY_HOST` 与端口。当前 Compose 的 `8443:443` 映射仅用于部署准备，不是车端推荐入口。
5. 填写 `.env` 中真实模式参数并执行 `docker compose --profile real up -d --build`。完成 Tesla 授权、车辆同步、虚拟钥匙配对后，从 App 的车辆配对页显式提交遥测配置。
6. 检查遥测配置的 `synced` 状态、Fleet Telemetry 服务日志、MQTT 和数据库样本。执行任何真实指令前须完成车辆能力与实车验收。

真实模式目前尚未经过实车端到端验证。生产使用前需要补齐 README 中列出的 V1.0 缺口。

## 数据库

MySQL 数据库由 Flyway 迁移，使用持久化卷。备份：`scripts/backup.sh <输出.sql>`；恢复：`scripts/restore.sh <备份.sql>`。恢复前应停止后端写入并核对备份文件，恢复后验证 Flyway 版本和行数。
