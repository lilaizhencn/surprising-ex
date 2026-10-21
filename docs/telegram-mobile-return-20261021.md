# Telegram 移动端授权回跳

移动端 `POST /api/v1/auth/telegram/start` 发送 `returnToApp: true`。Web 保持空对象请求，缺省为 false。

`TelegramLoginRepository` 在数据库保存回跳类型；执行新迁移 `deployment/migrations/20261021-telegram-mobile-return.sql`。已有流程默认保持 Web 行为，迁移可重复执行。

`TelegramLoginService.callback` 仍校验 state、一次性 callback、PKCE、OIDC 签名与 nonce。只有数据库中已认领的移动端流程会生成固定 `surprising://auth/telegram?flowId=...` 链接；不接收任意 returnUrl。回调页尝试自动返回，浏览器阻止时可点“返回 Surprising”。Web 流程不会唤起 App。

链接只含公开的流程编号，不携带授权码、verifier 或登录令牌。Flutter 在系统安全存储保留五分钟有效的 verifier，并绑定原 API 地址和 X-Device-Id；热启动由原有轮询完成，进程被回收后从匹配的回跳继续。过期、陌生和重复参数链接不能登录，已有登录或生物锁定会话不能被链接替换。完成、取消、失败、保存其他登录及退出登录会清除待授权状态。账户风控和 MFA 沿用原登录逻辑。

Android 注册 VIEW/BROWSABLE intent，iOS 注册 URL scheme，使用 app_links 处理初次和运行中链接，并关闭 Flutter 默认路由处理，避免回跳被当作交易页面路由。

直接唤起 Telegram 与回跳 Surprising 是两项配置。Telegram 原生 SDK 还要求 BotFather 的 Login Widget 登记 Android 包名和签名 SHA-256（iOS 为 Bundle ID、Team ID），当前修复保留现有浏览器 OIDC 认证。相关官方说明：https://core.telegram.org/bots/telegram-login#native-sdks 。本次不修改 BotFather 配置，也不把客户端传入的 Telegram ID 当作可信身份。

发布需要同时更新 Gateway、执行迁移及安装新版客户端。用户要求停止的本地后台服务保持停止；单元测试和隔离数据库测试不能代替 Telegram 真账号的手机授权验收。

验证记录：Corretto HotSpot JDK 27 下，Telegram 控制器、服务、OIDC、隔离 PostgreSQL schema 及 AuthService 共 50 项通过，0 跳过；客户端 `flutter analyze` 无问题，`flutter test` 531 项通过、12 项外部环境测试跳过。数据库临时 schema 已清除。原生构建和手机回跳结果在安装后另行记录，真账号授权需网关上线。
