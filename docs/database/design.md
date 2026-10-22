# MySQL 设计

MySQL 8.4、InnoDB、utf8mb4、UTC。Flyway V1 建立 20 张核心业务表；V2 增加按车辆和 UTC 日期汇总的 `vehicle_daily_statistics`；V3 增加一次性 OAuth 登录票据；V4 增加行程与充电聚合状态；V5 记录登录票据的客户端类型。所有历史表按车辆与时间建立复合索引，遥测原始事件按 `event_key` 去重。金额、电量与坐标使用 DECIMAL，原始载荷使用 JSON。

默认尚未启用分区。MySQL 分区表要求所有唯一键包含分区列，当前遥测事件全局唯一键不满足该规则；数据量增长后应以月度归档表或重新设计唯一键后再分区。

备份：`scripts/backup.sh backup.sql`。恢复：`scripts/restore.sh backup.sql`。恢复会覆盖目标库数据，应先在独立环境演练。原始遥测与标准化样本按 `user_settings.retention_days` 每日清理；行程与充电记录暂不自动删除。
