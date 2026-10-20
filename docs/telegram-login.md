# Telegram 快捷登录

Web 与 Flutter 使用同一条服务端 OIDC 授权链路，注册不要求密码、邮箱或手机号。昵称放在 `user.displayName`，`subject` 唯一绑定交易账户，昵称变化不会新建账户，也不根据昵称或联系方式自动合并账户。

## 部署配置

先执行 `deployment/migrations/20261020-telegram-login.sql`，新数据库使用 `init.sql`。在服务端配置环境变量：

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

65 项通过、0 跳过。覆盖真实 RSA 签名及伪造签名、错误 issuer/audience、过期/nonce、MFA 保留、冻结账户、无密码注册、跨设备/错误 verifier/重复领取、重复回调，以及真实 PostgreSQL 并发首登只创建一个账户。

真实 Telegram 用户授权仍需配置有效的登录专用 Secret 后验收；模拟身份提供方和数据库测试不代替该项验收。此特性当前未满足整体移动端改版及合并条件。

协议来源：https://core.telegram.org/bots/telegram-login

## App 用户二维码

App 的公开名片二维码仅包含 `surprising://user/<UID>`，不含会话、邮箱、手机号或余额。`POST /api/v1/users/scan` 要求现有 Bearer 会话，正文 `{qrCode}`；服务端严格解析平台 URI 并返回 `{userId, displayName}`。客户端显示服务端解析的昵称，不信任二维码中附加的名称，也不执行转账、外部跳转或登录动作。冻结或不存在的账户返回不可用。

`UserCardControllerTest` 4 项通过，覆盖公开资料范围、外部地址、非法/溢出 UID、冻结或不存在账户，以及无效会话。
