# Release Gate Review — `262e3e27`

- recommendation: **REJECT**
- user-facing verdict: **FAIL**
- reviewed SHA: `262e3e278c0467d00e85cc44b52ab8647e8b42e0`
- review mode: read-only production gate; no code changed
- review time: 2026-08-06 Asia/Shanghai

## Original intent

对精确 SHA `262e3e27` 做只读发布门禁，复现指定 Maven 测试，确认未跟踪文件和用户脏改动没有混入目标提交，并判断 API Key IP 白名单与钱包动态 capital config 是否达到本次生产边界。

## Desired outcome

目标提交可独立构建并通过指定测试；提交内容不包含工作区额外改动；API Key 白名单在生产网络拓扑下基于可信的真实客户端 IP 执行；Binance capital config 从钱包服务动态链配置生成，而非继续依赖静态本地配置。

## Success criteria

- `C1`：`HEAD` 精确为 `262e3e27`，且指定 Maven 命令通过。
- `C2`：目标提交仅包含预期文件，当前未跟踪文件/用户脏改动未混入该 SHA 或指定 Maven 测试输入。
- `C3`：API Key IP 白名单达到生产边界：创建/更新可持久化并校验 IP/CIDR，认证请求按可信的真实客户端来源执行允许/拒绝，且有能证明该执行路径的测试。
- `C4`：`/sapi/v1/capital/config/getall` 使用钱包服务动态 chain config，反映启用链、资产和提现开关，并有测试证明。

## User outcome review

### `C1` — PASS

- `git rev-parse HEAD` 返回 `262e3e278c0467d00e85cc44b52ab8647e8b42e0`。
- 已原样运行：

  `mvn -pl surprising-edge/surprising-gateway/surprising-gateway-provider -am -Dtest=GatewayApiKeyServiceTest,BinanceApiControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

- 结果：exit `0`，`BUILD SUCCESS`；`GatewayApiKeyServiceTest` 5 个、`BinanceApiControllerTest` 10 个，共 15 个测试，0 failures、0 errors、0 skipped；4 个 reactor module 均 `SUCCESS`。

### `C2` — PASS

- `git show --stat 262e3e27` 和 `git diff --name-status 262e3e27^ 262e3e27` 显示提交恰含 8 个文件：`init.sql`、迁移文件、4 个 gateway production Java 文件、2 个测试文件。
- 当前工作区另有 4 个已跟踪脏改动：`docs/README.md`、`docs/production-grade-trading-acceptance-plan.md`、`scripts/product-line-api-flow-smoke.sh`、`scripts/start-product-line-providers.sh`，以及大量 `.omo/evidence/`、文档和脚本未跟踪文件。
- 上述脏改动均不在目标提交的 8 个文件中，也不位于指定 gateway Maven module 的 Java/resource/test 输入路径；目标 `HEAD` 已是精确 SHA，因此没有混入该提交或本次测试编译输入。
- 注：工作区不是 clean；本结论是“未混入目标 SHA/指定构建输入”，不是“仓库无脏文件”。

### `C3` — FAIL

- 持久化和 API 表面已接入：`ip_allowlist` migration/init schema、create/list/update repository、create/PATCH controller、CIDR 规范化和认证时检查均存在。
- 阻断事实：`GatewayApiKeyService.authenticate()` 直接把 `request.getRemoteAddr()` 传给 `requireIpAllowlist()`。同仓库的生产边界 `AdminIpWhitelistFilter.clientIp()` 明确只在 TCP 对端属于 `trustedProxyIpAllowlist` 时解析 `X-Forwarded-For`。API Key 路径没有复用或等价实现该可信代理解析。
- 因而在生产反向代理后，白名单比较的是代理 IP，而非经过可信代理链解析的客户端 IP：配置真实客户 IP 会拒绝合法请求；配置代理 IP 又会让该代理后的所有来源共享放行，无法实现用户期望的 API Key 来源限制。
- `GatewayApiKeyServiceTest` 新增测试仅覆盖 `normalizeIpAllowlist()` 的去重/格式化和拒绝 hostname，没有调用 `authenticate()`，也没有覆盖 allow、deny、空 allowlist、IPv4/IPv6 CIDR、可信代理头或伪造转发头。绿测不能证明生产执行边界。

### `C4` — PASS

- `BinanceApiController.capitalConfig()` 已从静态 `assetScales`/`withdrawalAddressIds` 改为遍历 `custodyWalletClient.chains()`，过滤禁用/无 chain/无 assets 的条目，按 `assetSymbols` 聚合 network，并传播 `withdrawalEnabled`。
- `BinanceApiControllerTest.exposesConfiguredCapitalNetworksAndAccountStatus()` 让动态钱包响应包含 TRX/ETH 两条链，并断言返回 1 个资产、2 个 network、ETH 的 `withdrawEnable=false`。该 fixture 与旧静态 fallback 不同，能区分是否真正使用钱包动态配置。
- 本轮边界内未发现该测试为删除型、同义反复或实现镜像断言；它断言的是 HTTP 返回的用户可见结果。

## Blockers

1. `violatedCriterion: C3`
   - observation: API Key 白名单没有在可信代理边界解析真实客户端 IP，生产代理后无法可靠地按客户来源执行白名单。
   - evidencePointer: `surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/auth/GatewayApiKeyService.java` (`authenticate`, `requireIpAllowlist`)；对照 `.../config/AdminIpWhitelistFilter.java` (`clientIp`).
2. `violatedCriterion: C3`
   - observation: 指定绿测只验证 allowlist 字符串规范化，未验证认证时允许/拒绝和可信代理/伪造头场景，不能锁定核心安全行为。
   - evidencePointer: `surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/auth/GatewayApiKeyServiceTest.java`；Surefire 结果 `5 tests` 中新增两例均只调用 `normalizeIpAllowlist`。

## Direct remove-ai-slops / programming pass

- 审查范围：目标 SHA 的 8 文件 diff、两份指定测试和 production authentication/capital-config 路径。
- 过拟合检查：未发现 deletion-only test、仅验证请求删除的测试、自然语言/实现文本 pin、期望值从被测输出反推或 fallback 与 override 相同。capital config 测试可区分动态钱包结果；API Key 测试则覆盖过窄，造成安全行为的 false confidence，已作为 `C3` blocker。
- slop/维护负担：`GatewayApiKeyService` 新增了与 `AdminIpWhitelistFilter` 重复的 IP/CIDR 匹配逻辑，并绕过其可信代理来源解析。这是边界重复与行为分叉，不只是风格问题；因直接破坏 `C3` 而阻断。
- 范围漂移：同一提交同时包含 API Key allowlist 与 capital config 动态化。两者都属于用户指定审查边界，未据此单独阻断。
- `git diff --check 262e3e27^ 262e3e27`：通过，无 whitespace error。
- 未找到精确 SHA 对应的 code review report、manual QA matrix 或 notepad。按门禁规则，本次直接审查覆盖可用于判断；缺失本身不作为独立 blocker。

## Checked artifact paths

- `/Users/atomex/Desktop/surprising/surprising-ex/.git`（HEAD、status、commit/diff metadata）
- `/Users/atomex/Desktop/surprising/surprising-ex/init.sql`
- `/Users/atomex/Desktop/surprising/surprising-ex/migrations/20260807_gateway_api_key_ip_allowlist.sql`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/auth/GatewayApiKeyRepository.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/auth/GatewayApiKeyService.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/auth/UserApiKeyController.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/controller/BinanceApiController.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/config/AdminIpWhitelistFilter.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/main/java/com/surprising/gateway/provider/service/CustodyWalletClient.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/auth/GatewayApiKeyServiceTest.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/surprising-edge/surprising-gateway/surprising-gateway-provider/src/test/java/com/surprising/gateway/provider/controller/BinanceApiControllerTest.java`
- `/Users/atomex/Desktop/surprising/surprising-ex/docs/production-gateway-security-baseline.md`
- `/Users/atomex/Desktop/surprising/surprising-ex/.omo/evidence/`（精确 SHA 报告/QA/notepad 搜索）

## Exact evidence gaps

- 无 API Key `authenticate()` 的 allow/deny/empty-list 测试。
- 无可信代理 `X-Forwarded-For` 与不可信来源伪造该头的 API Key 测试。
- 无 API Key allowlist repository migration/integration test。
- 无精确 SHA 的独立 code review report、manual QA matrix 或 executor notepad；直接审查已完成，但无法交叉核对执行者报告中的 skill-perspective coverage。
- 未执行真实钱包服务集成测试；`C4` 的证据是 controller 单测中的动态 client response。该项未被本次明确门禁要求为集成测试，因此记为 NOTE，不阻断。

## Final recommendation

**REJECT / FAIL**。Maven 与提交隔离门禁通过，钱包动态 capital config 达到本次边界；API Key IP 白名单未达到生产可信代理边界，且核心认证行为缺少测试证据。
