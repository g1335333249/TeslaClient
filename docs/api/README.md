# REST API 0.1.0

Base path: `/api/v1`. 除演示登录、Tesla OAuth 起始/回调/完成外，业务端点要求 `Authorization: Bearer <应用会话>`。OpenAPI 交互文档位于 `/swagger-ui/index.html`，JSON 位于 `/v3/api-docs`。

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | /auth/demo | Mock 演示登录 |
| POST | /auth/tesla/start | 取得 Tesla 官方授权 URL |
| GET | /auth/tesla/callback | 后端授权码回调 |
| POST | /auth/tesla/complete | 一次性票据换应用会话 |
| GET | /auth/tesla/accounts | 查询已关联 Tesla 账户 |
| DELETE | /auth/tesla/accounts/{accountId} | 解除账户授权并删除本地令牌 |
| POST | /auth/refresh | 更新应用会话 |
| POST | /auth/logout | 撤销应用会话 |
| GET | /vehicles | 当前用户车辆 |
| POST | /vehicles/sync | 同步真实 Tesla 车辆 |
| GET | /vehicles/{id}/status | 最新缓存/遥测状态 |
| GET | /vehicles/{id}/capabilities | 钥匙状态和配对链接 |
| POST | /vehicles/{id}/commands | 执行远程指令，需 idempotencyKey |
| GET/POST/DELETE | /vehicles/{id}/telemetry/configuration | 真实模式查询、提交或删除车辆遥测配置 |
| GET | /vehicles/{id}/trips | 分页行程 |
| GET | /vehicles/{id}/trips/{tripId} | 行程详情和轨迹点 |
| GET | /vehicles/{id}/charging/sessions | 分页充电记录 |
| GET | /vehicles/{id}/statistics/daily | 最近 N 天或 from/to 日期范围统计 |
| GET | /vehicles/{id}/statistics/monthly | 按月统计 |
| GET | /vehicles/{id}/statistics/overview | 总览统计 |
| GET/PUT | /settings | 数据保留、位置隐私和采样设置 |
| GET/POST | /settings/tariffs | 电价配置 |
| DELETE | /vehicles/{id}/location-history | 删除位置历史 |
| GET | /notifications | 分页通知 |
| POST | /notifications/{id}/read | 标记通知已读 |
| POST | /mock/vehicles/{id}/scenario | 仅 Mock：切换在线、休眠、低电量等场景 |

错误响应包含 `code`、`message` 和 ISO 8601 `time`。命令结果含 `SUCCESS` 或 `FAILED`，失败不会伪装成成功。车辆状态的空字段表示暂无数据或暂不支持。
