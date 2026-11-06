# Surprising 交易系统：前后端通知与推送服务闭环设计与实现规范

## 1. 架构总览与设计原则

本项目为 Surprising 交易平台构建了从**事件监测/触发 -> 偏好过滤 -> 设备调度与出件箱 -> 多端深链接跳转**的生产级别前后端通知与推送服务体系。

```
┌────────────────────────────────────────────────────────────────────────┐
│                        事件触发层 (Event Triggers)                     │
│  - 异地/新设备登录预警 (DeviceLoginAlertService)                       │
│  - 行情大波动/急涨急跌预警 (MarketVolatilityAlertService)               │
│  - 撮合成交 / 强平预警 / 资产充提 / C2C 订单状态更新                   │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                      偏好过滤与调度 (PushDispatcher)                   │
│  - 查询 gateway_user_notification_preferences (行情阈值/渠道开关)      │
│  - 阻断未开启项 / 写入 gateway_user_notifications (站内信)             │
│  - 写入 gateway_push_notification_outbox (带乐观锁与重试的出件箱)      │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
            ┌───────────────────────┴───────────────────────┐
            ▼                                               ▼
┌───────────────────────────────┐               ┌───────────────────────────────┐
│       Flutter 移动客户端      │               │           Web 前端            │
│  - 设备 Token 登录自动注册    │               │  - 系统通知中心与分类筛选     │
│  - 通知中心 (毛玻璃科技风)    │               │  - 深度链接一键直达           │
│  - 通知偏好设置 (阈值滑块)    │               │  - 站内信已读状态管理         │
│  - 深链接精准跳转到交易/安全  │               │                               │
└───────────────────────────────┘               └───────────────────────────────┘
```

---

## 2. 数据库设计与持久化模型

数据库脚本位于 `deployment/migrations/20261106-push-notification-preferences.sql` 并合并入 `init.sql`：

### 2.1 设备 Token 登记表 (`gateway_user_device_tokens`)
- 记录用户移动端设备 Token、平台（`IOS` / `ANDROID` / `WEB`）、设备指纹与活跃状态。
- 复合唯一约束 `(user_id, device_token)`，支持登录自动续期与登出注销。

### 2.2 用户通知偏好配置表 (`gateway_user_notification_preferences`)
- **行情异动预警**：`market_volatility_push`、`market_volatility_in_app`、`market_volatility_threshold_pct`（默认 5.0% 阈值）。
- **账户与安全**：`security_login_push`、`security_login_email`。
- **交易与风险**：`trading_fill_push`、`trading_fill_in_app`、`liquidation_risk_push`、`liquidation_risk_in_app`、`liquidation_risk_email`。
- **资产与 C2C**：`asset_transfer_push`、`asset_transfer_in_app`、`c2c_order_push`、`c2c_order_in_app`。

### 2.3 推送出件箱表 (`gateway_push_notification_outbox`)
- 状态机：`PENDING` -> `SENDING` -> `SENT` / `FAILED`。
- 具备 `retry_count`、`max_retries`、`next_retry_at`、`payload_json` 以及乐观锁机制。

### 2.4 站内信表扩展 (`gateway_user_notifications`)
- 扩展 `MARKET` 分类；
- 新增 `route`（目标页面路由，如 `/trade/usd-perpetual`、`/security`、`/wallet`、`/c2c`）；
- 新增 `route_params`（路由参数，如 `{"symbol":"BTC-USDT-PERPETUAL"}`）。

---

## 3. 服务端调度与接口实现 (`surprising-gateway`)

### 3.1 核心服务
1. **`PushDispatcher`**：
   - 核心调度器，统一接收标题、正文、分类、用户偏好检查键、深链接路由与参数；
   - 校验用户开关与阈值；
   - 保证站内信入库，并为名下所有活跃设备生成出件箱记录；
   - 定时批量调度扫描 `processPendingOutbox()`。
2. **`DeviceLoginAlertService`**：
   - 比对本次登录的 IP 地址与设备指纹；
   - 发现新 IP 或新设备时，自动生成 `SECURITY` 预警，深链接直达 `/security`。
3. **`MarketVolatilityAlertService`**：
   - 监听行情急涨急跌或超阈值波动；
   - 动态扫描满足波动阈值的订阅用户，生成 `MARKET` 预警，深链接直达 `/trade/usd-perpetual?symbol=...`。

### 3.2 REST API 端点
- `POST /api/v1/user/devices/register`：设备注册与活跃状态激活；
- `POST /api/v1/user/devices/unregister`：设备注销；
- `GET /api/v1/user/devices`：查询用户当前绑定的活跃设备；
- `GET /api/v1/user/notification-preferences`：获取细粒度偏好配置；
- `PUT /api/v1/user/notification-preferences`：更新细粒度偏好配置与行情阈值；
- `GET /api/v1/notifications?category=...`：分类筛选通知；
- `GET /api/v1/notifications/unread-count`：获取未读通知数。

---

## 4. 移动客户端实现 (`surprising-client`)

### 4.1 自动生命周期与设备注册
- 用户登录成功或会话恢复时，`AppState._registerDevicePushToken()` 自动上报设备 Token；
- 用户主动退出登录时，`AppState._unregisterDevicePushToken()` 自动向网关注销推送 Token。

### 4.2 深链接分发 (`NotificationRouter`)
- 通过 `NotificationRouter.navigate(context, item.route, item.routeParams)` 实现原生级精准分发：
  - `/trade` / `/trade/usd-perpetual`：自动选中对应币对（`state.selectSymbol`），并在 Widget 树中分发 `ShellTabNotification(2)` 切换至交易 Tab；
  - `/security`：自动压栈弹出 `DeviceAccessPanel` 设备与安全中心；
  - `/wallet` / `/assets`：切换至钱包资产 Tab；
  - `/c2c`：切换至 C2C 交易 Tab；
  - `/notifications/settings`：直达通知设置页。

### 4.3 界面与交互设计（未来科技风毛玻璃规范）
1. **`NotificationsPage` (`lib/src/notifications_page.dart`)**：
   - 暗黑毛玻璃背景卡片（`#0A0A12`、`#1E1E2E80`、`#00C6FF→#7B4397`）；
   - 水平分类过滤条：全部、行情异动、安全通知、交易成交、风险预警、资产充提、C2C订单；
   - 未读红点与数量角标指示；
   - 点击通知自动标记已读并执行深链接跳转；
   - 右上角设置齿轮按钮，一键进入通知偏好配置。
2. **`NotificationSettingsPage` (`lib/src/notification_settings_page.dart`)**：
   - 行情异动推送开关与波动百分比阈值连续调节滑块（`±1.0%` 至 `±30.0%`）；
   - 新设备/异地登录安全警报与邮件通知开关；
   - 订单撮合成交、爆仓风险预警细粒度开关；
   - 资产出入金与 C2C 订单状态更新开关；
   - 即时保存与反馈提示。

---

## 5. Web 前端配合 (`surprising-ex-web`)

1. **类型扩展**：`NotificationSchema` 兼容接收 `route` 与 `routeParams`；
2. **通知中心升级**：`NotificationsPage.tsx` 扩展 `MARKET`、`RISK`、`WALLET`、`C2C` 类别标签映射，并在卡片上提供「查看详情 →」深链接跳转功能。

---

## 6. 测试与验证结果

1. **服务端单元测试**：
   - `PushNotificationServiceUnitTest`（默认偏好、渠道拦截、出件箱重试、设备注销）：100% 通过；
   - `surprising-gateway` 模块 660 个类与 305 个测试类全量编译通过。
2. **移动端 Flutter 测试**：
   - `test/notification_push_system_test.dart`（模型反序列化、默认偏好、深链接分发通知）：100% 通过。
3. **Web 端测试与 Linter**：
   - `vitest run`：67 个测试文件全部通过，586 个测试用例全部通过；
   - `biome check`：0 错误，0 警告。
