# MySQL 初始化

Compose 使用官方 `mysql:8.4` 镜像创建 `voltmate` 数据库、应用用户和持久化卷。后端启动时由 Flyway 执行 `backend/src/main/resources/db/migration/V1__initial.sql` 及后续迁移；不要同时手动执行建表 SQL。生产环境应为数据库设置独立强密码，并定期运行仓库 `scripts/backup.sh`。
