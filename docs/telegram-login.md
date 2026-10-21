# Telegram 快捷登录

Web 与 Flutter 使用同一条服务端 OIDC 授权链路，注册不要求密码、邮箱或手机号。昵称放在 `user.displayName`，`subject` 唯一绑定交易账户，昵称变化不会新建账户，也不根据昵称或联系方式自动合并账户。

## 部署配置

先在 BotFather 的管理小程序中选择机器人并进入 Login Widget。如果页面只显示域名以及 **Switch to OpenID Connect Login**，当前仍是旧版模式；点击该按钮切换到 OIDC 后，登记 Allowed URLs，并读取该页的 Client ID 与 Client Secret。Allowed URLs 必须包含带 `/api/v1/auth/telegram/callback` 的完整 HTTPS 回调地址。Bot API Token 不能代替登录 Secret。

先执行 `deployment/migrations/20261020-telegram-login.sql` 和 `deployment/migrations/20261020-primary-authentication.sql`，新数据库使用 `init.sql`。在服务端配置环境变量：

- `TELEGRAM_CLIENT_ID`：BotFather > Login Widget 的 Client ID。
- `TELEGRAM_CLIENT_SECRET`：登录专用 Secret，不能放在前端、日志或版本库。
- `TELEGRAM_REDIRECT_URI`：已在 BotFather Allowed URLs 登记的完整 HTTPS 回调，路径固定为 `/api/v1/auth/telegram/callback`。

配置不完整时 `/config` 返回 `enabled: false`。本地手机联调可运行 `python3 deployment/telegram-callback-proxy.py --upstream http://127.0.0.1:19094`，再用 Cloudflare Tunnel 转发 `127.0.0.1:19847`。代理只接受登录回调路径，不公开其他交易接口，不记录回调查询参数。临时隧道重建后需要同步 Allowed URLs 和回调配置。

## 接口与业务步骤

1. `GET /api/v1/auth/telegram/config` 获取开通状态。
2. `POST /start` 携带现有 `X-Device-Id`，返回 `flowId`、`verifier`、`authorizationUrl`、`expiresAt`。授权地址包含独立的 state、nonce 与 S256 PKCE，领取用 verifier 仅在发起端内存保存。
3. 用系统浏览器打开授权地址。Telegram 跳转 `GET /callback?state=...&code=...`。`TelegramOidcClient` 交换 code，验证 RS256 签名、issuer、audience、有效期、iat 和 nonce。回调页面不输出会话令牌。
4. 发起端轮询 `POST /complete`，正文为 `{flowId, verifier}`，同样携带 `X-Device-Id`。未授权返回 `{pending:true}`；成功返回现有 `AuthResponse` 或 `LoginVerificationService.ChallengeResponse`。
5. 创建新账户或读取绑定账户、更新昵称，沿用冻结账户、IP/设备限制、二次验证、会话和刷新令牌。一次性领取与用户创建在同一事务内提交。数据库行锁和按 subject 的事务锁分别阻止重复领取与并发重复注册。

`TelegramLoginRepository` 负责跨实例共享的一次性流程和身份绑定；`TelegramOidcClient` 是远程协议与验签边界；`TelegramLoginService` 按以上顺序完成登录；`AuthService.loginTelegram` 复用现有安全策略。这些职责没有进入交易、撮合或资金核算路径。

## 验证记录（2026-10-20）

HotSpot JDK 27，执行：

```sh
TELEGRAM_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/postgres TELEGRAM_TEST_DB_USER=atomex \
  mvn -pl surprising-gateway -am \
  -Dtest=TelegramOidcClientTest,TelegramLoginServiceTest,TelegramLoginDatabaseTest,AuthServiceTest,AuthPersistenceServiceTest,LoginVerificationServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

最新联合验收加入 `TelegramLoginControllerTest`、`UserCardControllerTest` 后为 73 项通过、0 跳过。覆盖真实 RSA 签名及伪造签名、错误 issuer/audience、过期/nonce、Unicode 昵称、MFA 保留、冻结账户、无密码注册、跨设备/错误 verifier/重复领取、重复回调，以及真实 PostgreSQL 并发首登只创建一个账户。浏览器以 `Accept: text/html` 访问过期/异常回调时返回不含授权代码、令牌及内部异常的 HTML，避免 JSON 异常处理造成 500。

真实 Telegram 用户授权仍需配置有效的登录专用 Secret 后验收；模拟身份提供方和数据库测试不代替该项验收。此特性当前未满足整体移动端改版及合并条件。

协议来源：https://core.telegram.org/bots/telegram-login

## 无密码账户绑定安全验证方式

`GET /api/v1/security/login-verification/authentication` 返回 `passwordRequired`、`reauthenticationRequired` 和 `authenticatedUntil`，响应禁止缓存。有密码的账户始终需要当前密码；无密码账户需要当前有效会话在最近 5 分钟内完成首次身份验证，过期后应重新登录。绑定发起及确认会分别校验，邮箱、手机和 TOTP 的验证码、已有验证因素、失败次数、指纹及一次性挑战保护继续生效。绑定 TOTP 前仍需先绑定邮箱或手机。

服务端从经过 Bearer 验证的用户及 session ID 读取数据库 `authenticated_at`。新登录在所有登录验证完成后写入该时间；刷新令牌轮换严格继承原值，不能把 JWT 的新签发时间或新会话行的创建时间当作重新验证。迁移前的旧会话保持空值，需要重新登录，不回填为当前时间。请求正文不能提供此证明。

JDK 27 的联合验证包含 `PrimaryAuthenticationDatabaseTest`、`LoginVerificationSettingsControllerTest`、既有登录因素/Telegram/认证/用户安全测试，94 项通过、0 跳过。真实 PostgreSQL 覆盖迁移重复执行、旧会话空值、用户归属、撤销/过期、实际 `AuthService` 连续刷新保持原验证时间。Web/App 的安全设置已消费该上下文：无密码账户无需填写不存在的密码，过期则提示重新登录；跨账户的迟到响应不会打开旧账户绑定对话框。

## App 用户二维码

App 的公开名片二维码仅包含 `surprising://user/<UID>`，不含会话、邮箱、手机号或余额。`POST /api/v1/users/scan` 要求现有 Bearer 会话，正文 `{qrCode}`；服务端严格解析平台 URI 并返回 `{userId, displayName}`。客户端显示服务端解析的昵称，不信任二维码中附加的名称，也不执行转账、外部跳转或登录动作。冻结或不存在的账户返回不可用。

`UserCardControllerTest` 4 项通过，覆盖公开资料范围、外部地址、非法/溢出 UID、冻结或不存在账户，以及无效会话。


## 本地真实配置验收（2026-10-21）

登录专用 Secret 已放入工作树之外、权限为 `0600` 的本地配置文件，并重载专用 QA 网关。该值不会写入前端、截图、文档或 Git。配置检查返回 `enabled: true` 且禁止缓存。

实际 HTTP 验证覆盖发起流程的 state、nonce、S256 PKCE、待授权轮询、错误设备/错误领取凭据拒绝，以及经 Cloudflare 公网隧道到专用网关的取消授权回调和重复回调拒绝。真实 Chrome 已从 Web 的 Telegram 按钮进入 `oauth.telegram.org` 的 OIDC 授权页，取消后按钮恢复可用；随后用户已在真实 Telegram 完成授权；专用数据库确认创建了一个 Telegram 身份绑定、非空昵称、无密码账户与有效会话。没有记录身份 subject、昵称内容或令牌。

本地 Web 使用 `http://127.0.0.1:5173`，代理至 `http://127.0.0.1:19094`。网关的 `GatewayCorsConfig` 已允许标准开发端口；此前临时端口 `15474` 的浏览器 POST 请求被 Origin 白名单拒绝，而没有 Origin 的接口请求可以成功，因此接口冒烟测试不能代替实际浏览器测试。当前改用空闲的标准开发端口，保留既有跨域校验。

用户已确认实际登录成功，并明确要求合并主分支。iOS 真机扫码、相册与防截图验收仍单独保留为未验收项，合并不能表述为这些项目已通过。详见 `docs/validation/telegram-configuration-20261021.json`。
