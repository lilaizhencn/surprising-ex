# 网关安全完善验收（2026-10-29）

本轮落实 Gateway 的 Spring Security 边界、密码及会话、共享资源预算、上传扫描和入口防护，配套修改 Web、管理端与 App 实时心跳。实现与部署配置见 [网关安全](../../gateway-security.md)。仅提交安全任务内容；同一工作区的 Google 登录和主题开发保留为未提交改动。

## 结果与证据

| 范围 | 实际结果 | 证据 |
| --- | --- | --- |
| Java 定向回归 | 33 个类、262 项，最新逐类报告 0 失败/错误/跳过：Gateway 198、Core 快照与恢复 64 | [JUnit 汇总](junit-summary.json) |
| 独立提交内容构建 | 从 Git 暂存内容导出到空目录，编译全部 Gateway 依赖和测试源码，4 类关键测试共 16 项通过 | [构建日志](index-backend.log) |
| Web | 5 文件 72 项通过；本轮改动文件 Biome 通过；包含新增续期调度的暂存内容构建通过 | [测试](web-tests-final.log)、[检查](web-lint-related.log)、[构建](index-web.log) |
| 管理端 | 客户端 41 项通过、TypeScript 检查通过、暂存内容构建通过 | [测试](admin-tests-final.log)、[检查](admin-lint-final.log)、[构建](index-admin.log) |
| Flutter | 实时传输 21 项、HTTP 生命周期 18 项通过；另用真实 Gateway 验证原生客户端令牌过期后刷新并重试，旧刷新令牌不可复用；涉及的三个文件 analyze 无问题 | [实时测试](flutter-tests.log)、[HTTP 测试](flutter-http-final.log)、[真实 Gateway](flutter-native-live.log)、[检查](flutter-analyze-related.log) |
| 真实浏览器 | Chrome 156：Web/管理登录、短令牌续期、重载恢复、Cookie 隔离、Origin/CSRF 拒绝、桌面/390px、管理及移动菜单退出、退出后重载均通过；无 pageerror | [步骤](browser-flows.json)、[日志](browser-flows-final.log) |
| Nginx 1.30.6 | 配置语法通过；私有探针 403、真实 readiness 503 透传、正文 413、内部路由 404、编码路径 400、安全头、429 的带凭证 CORS/Retry-After、查询/认证头/Cookie 不落访问与错误日志均通过 | [结果](nginx-check.json)、[日志](nginx-final.log) |
| 依赖公告 | Gateway SBOM 171 个外部 Maven 组件、Web 418 个 npm 包名、管理端 49 个包名，三次查询完整，当前命中为 0 | [公告扫描](dependency-audit.json) |
| 源码凭证扫描 | Gitleaks 扫描四仓工作源码约 29MB，76 条命中逐类复核为摘要/请求标识/合成测试凭证，未确认真实凭证 | [脱敏复核](secret-scan-review.json) |

[源码摘要](source-sha256.json) 记录本轮选入暂存区的文件版本。后台曾额外执行一次现有前端 16 文件测试（145 项通过）；最终只重验受影响的客户端 41 项。没有运行后端/交易全套测试、压测、JMH 或 JFR。

首次定向干净构建发现删除转发方法后尚有 HTTP 控制器及 C2C 测试调用，已将 HTTP 入口直接接入 AuthService 并适配测试；独立空目录构建验证不存在残留 class 依赖。新增 Cookie 测试最初因 MockHttpServletResponse 不把 Servlet 扩展属性写入模拟响应头而失败，修正为检查 Cookie 的 SameSite 属性后通过，见 [Cookie 补验](cookie-final.log)。管理刷新原始 8 小时截止时间的新增用例亦通过，见 [会话补验](session-final.log)。262 项为这些定向运行的最新逐类结果，未把失败运行或跳过项算为通过。

## 验证环境与范围

HotSpot Corretto JDK 27、Maven 3.9.16、本机独占 Valkey/Redis 端口 62991、独占 PostgreSQL 库；Chrome 156 和临时 Nginx 1.30.6。浏览器夹具使用真实 AuthService/JWT/PBKDF2、数据库会话和 Redis、安全链；邮件、MFA、返佣及第三方身份提供商不联网，未启动 Core 或 wallet。缺失的资金/业务接口保留真实错误，截图不代表那些业务通过验收。

本机浏览器用 HTTP 开发 Cookie（secure=false）验证交互，生产 __Host-/Secure/HttpOnly/Path=/、无 Domain 由 Cookie 测试验证。生产域名、HTTPS 跨子域部署、真实第三方登录和真实上传病毒库尚未验收。ClamAV 协议测试使用受控 TCP 扫描端，覆盖 clean、病毒、错误回复、不可用及阻塞写总截止时间，并检查拒绝前不写 KYC、不把 C2C 置 READY；没有运行真实 ClamAV 服务。

六产品快照测试核对资金/持仓/预留、幂等及恢复后继续下改撤与成交；不代替真实 Archive 回放、多节点故障切换或持续运行验收。Nginx 429 使用一个强制响应验证生产错误处理段的响应头，没有通过发压触发阈值。

Web 全目录 Biome 另有既有及并行任务文件的 hook 依赖等诊断，未扩展修改这些文件；本轮涉及文件检查通过。Flutter 全目录检查有另一任务的集成测试格式提示，本轮三个文件检查通过。公告扫描只覆盖所列 SBOM/锁文件和查询时可用公告；凭证扫描不含 Git 历史、忽略文件或运行环境。

## 可复验命令

```bash
java -version
mvn -version
df -h .
# 使用独占库，AuthInputBoundaryPostgresTest 会清空 gateway_users，禁止指向业务库。
GATEWAY_LIMIT_TEST_REDIS_PORT=62991 \
INSTRUMENT_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/<独占验收库> \
INSTRUMENT_TEST_DB_USER=<本机用户> INSTRUMENT_TEST_DB_PASSWORD='' \
TELEGRAM_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/<同一独占验收库> \
TELEGRAM_TEST_DB_USER=<本机用户> TELEGRAM_TEST_DB_PASSWORD='' \
mvn -nsu -pl surprising-gateway -am test \
  -Dtest=GatewaySecurityBoundaryTest,BrowserCookieSecurityTest,GatewayInputLimitsTest,GatewayProductionSecurityConfigurationTest,PasswordHasherTest,AuthServiceTest,AuthInputBoundaryPostgresTest,PrimaryAuthenticationDatabaseTest,AdminPermissionFilterTest,AuthClientMetadataBoundaryTest,GatewayApiKeyAuthenticationIpTest,GatewayApiKeyServiceTest,ApiKeyHttpBoundaryTest,ApiKeyOrderPermissionTest,GatewayLimitHttpTest,GatewayRequestLimitsRedisTest,WebSocketLimitsRedisTest,ClientWebSocketHandlerTest,WebSocketApiKeyAuthenticationTest,WebSocketSessionAuthorizationTest,WebSocketSessionAuthorizationTaskTest,UploadContentScannerTest,KycDocumentServiceTest,UserKycUploadBoundaryTest,KycEvidenceReferenceTest,CompressionCompatibilityTest \
  -Dsurefire.failIfNoSpecifiedTests=false
# 根依赖安全升级涉及压缩，另执行以下定向快照/恢复用例：
mvn -nsu -pl surprising-gateway -am test \
  -Dtest=CoreNativeSnapshotProductLineTest,SharedProductLineSnapshotContractTest,CoreTransferSnapshotCodecTest,TradingStateSnapshotCodecTest,SectionedCoreSnapshotCodecTest,ProductRecoveryLifecycleTest,RuntimeCommitRecoveryTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Web 执行 `npm test -- src/state/session.test.ts src/api/client.test.ts src/api/types.test.ts src/api/telegram.test.ts src/realtimeConnections.test.ts`，管理端执行 `bun test tests/client.test.ts`。两端执行 `npm run build`；管理端部署构建增加 `-- --base=/admin/`。Flutter 执行 `flutter test test/realtime_transport_lifecycle_test.dart`（21 项）、`flutter test test/http_lifecycle_test.dart`（18 项）及 `flutter analyze lib/src/api.dart test/http_lifecycle_test.dart test/realtime_transport_lifecycle_test.dart`。真实后端的 [原生协议夹具](security_gateway_session_qa_test.dart.txt) 通过同一独占 Gateway 运行，不调用外部身份提供商。

浏览器使用临时夹具 [SecurityBrowserQaTest.java.txt](SecurityBrowserQaTest.java.txt)，[实际操作脚本](browser-flows.mjs) 读取 `SECURITY_QA_DIR` 中权限 0600 的随机测试凭证；凭证文件未归档。夹具仅用于独占本机测试，完成后写入该目录 `qa-done` 停止。Nginx 的 [单次功能检查脚本](nginx-check.py) 需要对应临时证书、代理与端口配置；429 检查位置只存在于临时配置，生产配置不包含该位置。

## 部署与清理

代码没有部署到生产。上线还需实际配置 CDN/WAF/DDoS/Bot、源站防绕过、管理域访问限制、私网 ClamAV/freshclam 与告警，按 [部署说明](../../../deployment/test-single-node/README.md) 安装新的 Nginx snippet；没有声称源码能承受分布式流量或带宽攻击。

本轮临时资源在归档后清理：只停止本轮夹具、Nginx、独占 Redis，删除独占数据库、临时凭证、证书和构建工具，保留并行开发的工作区改动。
