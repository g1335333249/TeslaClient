# 架构

```
iOS SwiftUI → REST /api/v1 → Spring Boot → MySQL 8.4
                                  ↑
Tesla 车辆 → 官方 Fleet Telemetry → MQTT
                                  ↓
                             原始事件 + 样本
```

后端不依赖 iOS。会话模型支持 IOS、ANDROID、WEB，OAuth 完成后按客户端类型返回一次性票据回调，客户端再换取应用会话。真实命令通过官方 vehicle-command 代理，Mock 命令仅更新演示车辆。位置字段与普通状态分离返回；仍需完善授权范围变化时的权限细分。未来 Android/Web 使用同一 REST API。
