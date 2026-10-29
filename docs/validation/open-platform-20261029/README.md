# 开放平台与统一网关限流验收（2026-10-29）

## 交付范围

- Web `/developers` 公开文档与桌面/移动菜单；六产品选择、整数单位、两种 HMAC、权限/IP/验证码、命令回执与未知结果、WS 快照/增量/重连、默认限流策略；可下载的 Node 22+ 源码与页面展示共用一个文件。
- Key 创建/列表/修改名称、权限、IP/撤销；写 Key 必须有 IP/CIDR，禁止 `/0`；原生签名绑定方法、路径、查询和实际正文。冻结、写限制、撤单 TRADE、禁止管理员写/跨用户划转均有边界验证。
- 私有 WS 一次性挑战认证、定期 Key/IP/账户复查、固定会话期限；JWT 客户端保留原协议。
- Gateway 统一 IP → 用户/Key/产品/操作额度，Valkey Lua 原子扣减，多节点共用用户 hash tag，TTL 有界；批量按条目计费。独立撤单/回执额度与本机并发保留，异步请求到真正完成后才释放。
- WS 跨节点 IP/用户连接租约、节点容量、入站消息/认证频率。默认突发容纳正常六产品 36 条私有订阅及现有最多 200 条订阅；受限时等待完整补充期，客户端据 Retry-After 重新认证和恢复订阅，不把限流当登录失效。
- Nginx 入口请求/连接限制与可信来源头清理；429/Retry-After/CORS、503 容量/存储故障区分，Web、App、Admin 均保留未知写结果且不自动重试金融写操作。

## 最终验证

| 范围 | 命令/证据 | 结果 |
| --- | --- | --- |
| 后端干净构建和相关测试 | `mvn -pl surprising-gateway -am clean test -Dtest=<29 个相关类> -Dsurefire.failIfNoSpecifiedTests=false`，精确选择器见 logs/final-backend-selector.json | 首轮 268 个用例，256 通过；12 个维护测试方法因环境未设跳过 |
| PostgreSQL 维护/恢复补充 | 隔离库执行根 init.sql，再设置 MAINTENANCE_TEST_JDBC_URL，运行 `mvn -pl surprising-gateway -am test -Dtest=MaintenanceIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false` | 12 个方法展开为 38 个参数化用例全部通过；按类最后一次结果去重，**29 类、294 项、0 失败、0 跳过** |
| Web | `npm run lint`、`npm run typecheck`、`npm test -- src/api/client.test.ts src/api/errors.test.ts src/hooks/useBrowserNavigation.test.ts src/realtimeConnections.test.ts`、`npm run build` | 全通过，59 项相关测试 |
| App | `flutter analyze --no-pub`；`flutter test --no-pub test/api_key_edit_dialog_test.dart test/http_lifecycle_test.dart test/realtime_transport_lifecycle_test.dart test/shared_realtime_connection_test.dart` | 无分析问题，31 项通过 |
| App 设备 | `flutter test integration_test/api_key_live_test.dart -d <本轮设备> --dart-define-from-file=<私有夹具凭证文件>` | iOS 与 Android 各 1 项通过；每项包含深浅主题、真实 HTTP 编辑、错误验证码修正、数据库结果核对 |
| Admin | `npm run lint`、`bun test tests/client.test.ts`、`npm run build` | 全通过，41 项相关测试 |
| Nginx | 从官方 nginx-1.28.0 源码在临时目录构建，复制配置至本机端口并执行 `nginx -t` | 语法通过；将测试额度临时设为 1/s、burst 1，3 次请求依次 200/200/429，验证 JSON/Retry-After/CORS |

后端覆盖六产品真实 Core 状态机、订单/触发单撤销释放、资金守恒、划转幂等、丢响应、快照重启与维护恢复。上述 Core 业务集成将 Aeron 网络边界换成进程内协议调用，不代表生产 Aeron 网络部署已验收。

首次补充维护回归因本轮隔离数据库尚未执行 init.sql 失败；初始化后 38 项通过，没有为此修改业务代码。最终 JUnit XML、汇总和成功日志已归档，不能把初次禁用参数化方法数量 12 当作实际数据库用例数。

## 浏览器与设备交互

浏览器版本、Flutter 版本及设备分辨率见 environment.json。

- 用户 Web：1440×900 桌面、390×900 窄屏，真实点击顶部/移动菜单进入公开文档，点击目录、展开下载源码、切换中英文/深浅主题。后端不可用时文档仍可阅读，无页面脚本异常与整页横向溢出；已修复窄屏代码/表格撑宽问题。
- Key 管理：真实浏览器创建 Key，先验证写权限无 IP 被阻止，再填写白名单创建；关闭一次性 Secret，修改名称、权限、IP；错误验证码后修正成功，撤销后显示状态。Secret 不出现在截图或归档中。
- Node 网络示例：真实原生签名被接受；无 Core 的夹具余额读取保持 503/READ_VIEW_UNAVAILABLE，未伪造余额。真实 WS 挑战、Key 认证、私有 accountState 订阅成功；HTTP 撤销 Key 后现有连接以 1008 关闭。见 logs/native-network.json。
- App：本轮独占 iPhone 17 Pro / iOS 26.5 模拟器与 Android API 36 Pixel 7 AVD，实际运行生产编辑弹窗，点击、滚动、输入、校正验证码、保存并重读真实 Key 记录；深浅主题均通过。输入走 Flutter 测试输入通道；不把此流程声明为真实输入法/系统键盘适配验收，也不覆盖全部安全中心或 KYC 页面。
- Admin：真实点击登录按钮，经本轮 Nginx 的小额测试策略拒绝，页面显示“请求过于频繁，请在 1 秒后重试”。没有向真实账号或生产服务发送请求。

网络夹具 OpenPlatformBrowserQaTest 只监听 127.0.0.1，使用独占 PostgreSQL schema、独立 Redis、真实 JWT/HMAC/密钥加解密/Key Repository；验证码在夹具内模拟，未发邮件或短信，不启动 wallet/Core。设备创建和撤销经实际 API，编辑经生产弹窗；此夹具与独立资金状态机回归各有范围，不能合称完整交易网络端到端验证。

## 配置与部署边界

后端为 Corretto HotSpot JDK 27 / Maven 3.9.16；Android Gradle 的 jlink 在本机 JDK 27 失败，因此仅该设备构建进程使用已安装的 HotSpot JDK 21，未改项目 JDK/Gradle 配置。生产 JVM 要求不变。

生产 Gateway 默认绑定 loopback，通过受控 Nginx 进入。若 Nginx 前面还有 CDN/负载均衡，需先由 Nginx 按明确的可信上游范围恢复真实地址，再转发；未经校验的供应商来源头会被清除。本轮未更改线上 DNS/CDN/部署。REST/WS CORS 来源与相关超时、额度参数见模块 README。

未执行压测、JMH/JFR、全量测试、真实提现/链上钱包、生产部署或长时间容量/资源趋势评估。默认额度是可配置策略，不是实测容量或性能承诺。

源码摘要见 source-sha256.json；最终去重结果见 backend-summary.json 与 junit/；交互证据在 screenshots/。临时凭证、数据库转储、浏览器依赖、构建目录及工具索引不入库；本轮资源清理记录见 cleanup.json。
