# 车辆 WebSocket 协议（0.2.0）

连接：`ws(s)://<host>/api/v1/ws`。连接后先发送 `{"type":"AUTH","token":"<VoltMate 应用会话>"}`。收到 `AUTH_OK` 后发送 `{"type":"SUBSCRIBE","vehicleId":2001}`。服务端验证应用会话及车辆归属；无权订阅返回 `{"type":"ERROR","code":"VEHICLE_ACCESS_DENIED"}`。令牌不放入 URL。

客户端每 20 秒发送 `{"type":"PING","token":"<会话>"}`，服务端回复 `PONG`，并重新检查会话是否仍有效。可发送 `UNSUBSCRIBE`。车辆事件包括 `STATUS_CHANGED`、`CHARGING_CHANGED`、`COMMAND_RESULT`，统一字段为 `type`、`vehicleId`、`at`、`payload`。状态事件只带变更提示；客户端应重新 GET `/api/v1/vehicles/{id}/status` 获取完整状态。重连后重新认证、订阅并通过 REST 恢复状态。iOS 进入后台会断开连接。

当前连接只在单个后端实例内广播。多实例生产部署需引入 Redis Pub/Sub 桥接与连接数、心跳超时的负载验证。
