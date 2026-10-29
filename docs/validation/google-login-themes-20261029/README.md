# Google 登录与六套主题验证（2026-10-29）

本记录覆盖 Gateway、交易 Web 和 Flutter App。参考 surprising-social / timely-landing 的产品入口，交易账户仍由交易 Gateway 创建与鉴权，未复用社交端会话或 Client ID。

## 验证边界

没有配置真实 Google OAuth 客户端，因此未声称真实 Google 账号授权通过。浏览器使用合成 GIS/API 夹具；Gateway 使用本地签名、公钥服务与隔离 PostgreSQL schema；原生界面使用离线数据和关闭的 Google 配置。真实 Web 按钮、One Tap、Android/iOS 账号选择器的授权结果须在配置后验收，步骤见 [配置说明](../../google-login.md)。

按用户要求等待安全加固在三个仓库提交并推送后，才进行最后联调，全部原有改动均保留。其 Web 基线 ad2aa19 的独立源码副本执行完整 lint 报告 98 个错误；本轮后为 93 个，均在未修改的旧页面。本轮涉及的 16 个 Web 源码文件检查为 0 error。没有声明全仓 lint 通过，也未扩大为交易页面的 Hook 重构。

## 已执行的检查

| 范围 | 命令 / 场景 | 结果 |
| --- | --- | --- |
| Gateway | HotSpot JDK 27、Maven 3.9.16，9 个鉴权相关测试类 | 89 tests，0 failure/error/skip；详见 backend-tests.json |
| Flutter | flutter analyze | No issues found |
| Flutter | 10 个受影响测试文件 | 82 tests passed |
| Web | bun run typecheck / bun run test / bun run build | 通过；65 files / 555 tests |
| Web 新增功能 | Biome 检查 16 个本轮修改文件 | 0 error；3 个既有风格提示为字面量键写法 |
| Web 浏览器 | 12 个完整交互场景，24 次配色切换后观察 90 秒 | 全部通过，无 pageerror；详见 web/evidence.json |
| iOS | iPhone 17 Pro / iOS 26.5，六主题、1.4 倍字体、原生图表、弹层反复开关 | 49 秒流程通过；截图见 ios/ |
| iOS 正常 App | Maestro 真实键盘、长邮箱、Google 入口、关闭与返回 | 通过；截图 ios/native-keyboard.png |
| Android | 六主题、1.4 倍字体、首页、原生图表、文字输入、反复开关 | 最新 72 秒流程通过；截图见 android/ |
| 原生正常入口构建 | flutter build apk --debug --no-pub；flutter build ios --simulator --debug --no-pub | 均通过；iOS FLUTTER_TARGET 已恢复 lib/main.dart |
| Android 正常 App | Maestro 点击、真实输入、系统键盘截图、滚动、关闭返回 | 通过；Android 16，720×1280 / density 320（360×640 逻辑尺寸） |

后端在本轮独立源码副本中构建，避免另一任务在同一 target 目录执行构建/clean 导致结果互相污染。数据库仅创建和删除测试专用 schema，没有启动 wallet、交易 Core 或做市程序。

```sh
GOOGLE_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/postgres \
GOOGLE_TEST_DB_USER=atomex GOOGLE_TEST_DB_PASSWORD='' \
mvn -pl surprising-gateway -am \
  -Dtest=GoogleLoginHttpTest,GoogleAuthGateTest,GoogleIdentityClientTest,GoogleIdentityFailureTest,GoogleLoginServiceTest,GoogleLoginDatabaseTest,AuthServiceTest,TelegramLoginServiceTest,LoginVerificationServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

覆盖：签名/issuer/audience/azp/nonce/时效校验，公钥服务失败与非法凭据区分；nonce 设备绑定、单次消费、过期、并发与保留上限；并发首次登录只创建一个 UID；迁移可重复；冻结/访问门槛、二次验证；真实 HTTP 安全链、CSRF、浏览器 Cookie 与原生 token 响应。

```sh
flutter test --no-pub test/google_login_test.dart test/client_theme_test.dart \
  test/auth_state_test.dart test/telegram_login_test.dart test/app_presentation_test.dart \
  test/passwordless_security_test.dart test/price_chart_test.dart \
  test/withdrawal_security_test.dart test/wallet_state_test.dart test/http_lifecycle_test.dart
```

覆盖：Android/iOS token 请求、取消/关闭/退出后的晚到回调、邀请码、MFA、会话保存；原生 Google 授权请求不携带旧 bearer；iOS scheme 缺失时阻止 SDK 初始化；六套主题、文字与语义色对比度、游客选择主题后返回首页、图表颜色。

## 设备与浏览器复现

浏览器：本机 Chrome + Playwright，320/390/1440 宽；脚本位于 Web 仓库 scripts/google-themes-browser.mjs。使用本机 preview 地址，通过 GOOGLE_PLAYWRIGHT_MODULE 指向已安装的 Playwright，通过 GOOGLE_QA_DIR 保存记录。GOOGLE_QA_STABILITY_MS=90000 增加反复切换和 90 秒观察。所有 Google 身份与行情响应均标记为合成数据，未连接真实资金接口。

原生：Client 仓库 integration_test/google_themes_host.py --platform android|ios --device <id> --output <path>。实际点击六套配色、保存、1.4 倍字体、小屏滚动、返回首页、原生 WebView 图表颜色、不可用 Google 入口、文本输入以及 12 次弹层开关。Host 使用 adb/simctl 截取完整系统画面。

Flutter 测试绑定中的 enterText 只证明输入处理，不证明软件键盘。另用正常应用绑定 integration_test/google_themes_preview.dart 构建，再由 integration_test/google_themes_keyboard.yaml 驱动真实输入并截图：

```sh
maestro --device <id> test \
  -e APP_ID=<Android package 或 iOS bundle> -e FIELD_POINT=<首个输入框坐标百分比> \
  -e CAPTURE_DIR=<输出目录> integration_test/google_themes_keyboard.yaml
```

Android Gradle 使用本机 JBR 17；后端仍使用 JDK 27。原生构建提示部分既有插件尚未迁移 Built-in Kotlin，本次构建成功，未扩展为无关依赖升级。

## 回归修正

最终联调发现 Google config GET 会携带旧交易 bearer；三项过期会话用例先失败，修正后通过，Google config/challenge/login 都不发送旧身份，也不触发会话续期。另补验缺少 issuer 和 native azp 的 token：修正前两个断言捕获 NullPointerException，修正后按非法身份拒绝。

## 截图与观察

- [Web 桌面配色选择](web/picker-1440.png)、[320 窄屏选择](web/picker-320.png)、[暖沙色](web/sand-320.png)、[二次验证](web/mfa-390.png)。
- [Android 小屏选择](android/native-theme-picker.png)、[放大字体](android/native-large-text-picker.png)、[暖沙首页](android/native-home-sand.png)、[真实键盘](android/native-keyboard.png)。
- [iOS 配色选择](ios/native-theme-picker.png)、[放大字体](ios/native-large-text-picker.png)、[首页暖沙色](ios/native-home-sand.png)、[图表海蓝色](ios/native-chart-ocean.png)、[真实系统键盘](ios/native-keyboard.png)。

浏览器 90 秒观察中 JS heap 从约 7.30 MB 降为约 5.25 MB，DOM 与事件监听计数下降；单页仅加载一次 SDK、登录请求 0、challenge 未重复获取。此记录仅用于发现重复挂载/持续增长，不代表生产长期稳定性或性能提升。

## 尚待完成

- Web 全仓仍有 93 个基线 lint 错误，均不在本轮修改文件；功能、类型、构建和设备验证通过。
- 配置真实 OAuth Client 后执行 Google 授权验收；未执行正式签名 Android 包和真机 iOS 授权。
- 本轮不涉及资金、订单、撮合逻辑；未执行完整交易回归或性能压测。
