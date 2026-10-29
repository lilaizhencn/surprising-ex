# Google 登录接入与配置

交易端接入 Google Identity Services（Web 按钮与首页 One Tap）和 Flutter `google_sign_in` 7.2（Android Credential Manager / iOS Google Sign-In）。只使用身份认证，不申请 Google 联系人、Drive 或 Gmail 权限，不需要 Google Client Secret，也不使用社交项目的 Client ID。

## 已实现的链路

- Web：首页未登录时展示 One Tap；登录/注册页同时提供 Google 按钮与 One Tap。开始输入账号时收起提示；Google SDK 加载或授权失败时可以重试。浏览器是否展示 One Tap 仍由 Google、浏览器、账号状态及冷却期决定，按钮始终是主动登录入口。
- App：登录/注册弹层中的“通过 Google 继续”调用原生账号选择器。取消、缺少 Google 服务、配置缺失、关闭页面以及晚到的回调均不会恢复旧会话。
- 服务端：验签 Google 公钥，校验 issuer、Web audience、有效期与 native authorized party；Web 额外校验绑定设备、五分钟有效且只能消费一次的 nonce。Google `sub` 是唯一身份键，并发首次登录只创建一个交易用户。
- 已有账号的冻结、访问限制、二次验证和安全会话保存继续执行；浏览器 refresh token 使用现有 HttpOnly Cookie，App 走安全存储。首次 Google 注册接受普通邀请码。

不会仅凭 Google 返回的邮箱自动合并已有交易账户，也不会把昵称当作身份凭据。首次 Google 授权会创建独立的无密码账号；现有邮箱账户与 Google 账户的主动绑定不在本次功能范围内。密码、联系方式与安全验证沿用账户安全页面的既有能力。

## 1. 数据库与服务端

升级现有数据库时执行（`init.sql` 已包含新表，已有数据库仍需迁移）：

```sh
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f deployment/migrations/20261029-google-login.sql
```

设置 Gateway 环境变量后重启：

```dotenv
GOOGLE_LOGIN_WEB_CLIENT_ID=<Web 类型的 OAuth Client ID>
GOOGLE_LOGIN_IOS_CLIENT_ID=<iOS 类型的 OAuth Client ID>
GOOGLE_LOGIN_ANDROID_CLIENT_IDS=<Android debug Client ID>,<Android release Client ID>
```

三个客户端应在同一 Google Cloud 项目中创建。Android 列表可以只填写实际使用的一项；正式发布时应包含 Play App Signing 对应的客户端。空 Web ID 会关闭所有 Google 入口；缺少 Android/iOS ID 时只关闭对应 App 平台。`GET /api/v1/auth/google/config` 只返回公开客户端信息，不返回密钥。

## 2. Google Cloud：Web

1. 在 Google Cloud Console 的 Google Auth Platform 中填写应用名称、支持邮箱、已验证域名、主页、隐私政策、服务条款及 Audience；测试阶段加入测试账号。
2. 创建类型为 **Web application** 的 OAuth Client。
3. 在 **Authorized JavaScript origins** 中填写交易网站的完整 origin，例如 `https://<交易站域名>`；开发环境按实际 Vite 地址分别添加 `http://localhost:5173` / `http://127.0.0.1:5173`。origin 不带路径，端口须匹配。
4. 将 Client ID 填入 `GOOGLE_LOGIN_WEB_CLIENT_ID`。本实现接收 GIS ID token，不使用 OAuth code redirect，不需要填写一个虚构的 `/google/callback` 地址。
5. Web 静态响应的 `public/_headers` 已放行 GIS 所需脚本、样式、连接与 iframe，并允许 Google 登录弹窗。部署时核对实际托管层保留这些响应头，API 使用现有 CORS 白名单与 CSRF 机制。

官方说明：[GIS 配置](https://developers.google.com/identity/gsi/web/guides/get-google-api-clientid)、[One Tap](https://developers.google.com/identity/gsi/web/guides/display-google-one-tap)、[服务端验签](https://developers.google.com/identity/gsi/web/guides/verify-google-id-token)。

## 3. Google Cloud：Android

1. 创建类型为 **Android** 的 OAuth Client，包名使用当前工程的 `com.surprising.surprising_client`。如果发布包名修改，Google 配置也须同步。
2. 填写签名证书的 SHA-1。debug、正式签名、Google Play App Signing 可能不同，分别创建客户端；不要把 keystore 密码或私钥放入配置文档。
3. 将各 Android Client ID 填入 `GOOGLE_LOGIN_ANDROID_CLIENT_IDS`。
4. App 从 Gateway 获取 Web Client ID 并将它作为 `serverClientId` 传入原生 SDK。此路径不依赖 Firebase，也不要求增加 `google-services.json`。
5. 使用带 Google Play services 的设备验证账号选择与取消。没有 Google 服务的设备会提示授权未完成，不会用本地模拟凭据登录。

官方说明：[Flutter Android 插件配置](https://pub.dev/packages/google_sign_in_android)、[Credential Manager Google 登录](https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation)。

## 4. Google Cloud：iOS

1. 创建类型为 **iOS** 的 OAuth Client，Bundle ID 使用当前工程的 `com.surprising.surprisingClient`。
2. 将 iOS Client ID 填入 `GOOGLE_LOGIN_IOS_CLIENT_ID`。
3. 在 `surprising-client/ios/Flutter/GoogleSignIn.xcconfig` 中设置 `GOOGLE_REVERSED_CLIENT_ID`。例如 `123-abc.apps.googleusercontent.com` 对应 `com.googleusercontent.apps.123-abc`。这只是公开的 URL scheme，不是密钥。
4. Debug/Release 均已引入该配置，`Info.plist` 已注册独立的 Google URL scheme，并保留原有 `surprising://` 链接。AppDelegate 在授权前确认安装包内的回调 scheme 与服务端 iOS Client ID 一致；修改后须重新构建、安装 App。
5. App 原生 SDK 使用服务端 Web ID 作为 `serverClientId`。不要把 iOS ID 填到 Web audience 环境变量中。

官方说明：[Flutter iOS 插件配置](https://pub.dev/packages/google_sign_in_ios)、[iOS SDK 的 serverClientID 与 ID token audience](https://developers.google.com/identity/sign-in/ios/reference/Classes/GIDConfiguration)。

## 5. 配置后的真实授权验收

代码和设备 UI 验证不等同于真实 Google 授权。配置完成后，分别验证：Web Google 按钮、首页 One Tap、Android 原生选择器、iOS 原生授权；覆盖首次注册、重复登录、取消、普通邀请码、已启用二次验证、冻结/限制账户和退出后的再次登录。重复登录及跨端登录必须保持同一 UID。

本次实际命令、结果、设备截图及未测范围见 [验证记录](validation/google-login-themes-20261029/README.md)。
