# 身份、订单、账户、合约合并

## 运行边界

`surprising-gateway` 是统一业务应用，保留现有启动类
`com.surprising.gateway.provider.SurprisingGatewayApplication` 和默认端口 `9094`。
原 trading/account/instrument provider 的源码、测试迁入该模块，三个独立启动类和 provider POM 已删除。
对应的三个 `*-api` 模块保留，供独立后台服务和 Core 共享协议；它们不启动 JVM。

| 业务包 | 职责 |
| --- | --- |
| `com.surprising.gateway.provider` | 身份、用户安全、API 接入、管理审批、审计、托管接入 |
| `com.surprising.trading` | 订单、撤单、改单、条件单、费率、杠杆和维护入口 |
| `com.surprising.account.provider` | 余额、持仓查询，余额/保证金命令，划转及账本查询 |
| `com.surprising.instrument.provider` | 合约配置、状态、审计、outbox 和生命周期配置 |

Aeron Core 仍是独立 JVM，业务应用只依赖 `surprising-aeron-client` 和协议。
`surprising-aeron-service` 在业务应用 POM 中仅为测试依赖，不打入运行包。
余额、冻结、订单与持仓的权威状态、撮合、资金结算、Snapshot/Log Replay 均留在 Core。
价格、资金费、生命周期、行情、realtime、maker 的进程边界未合并。

业务应用共用一个数据源和调度器。默认 Hikari 最大连接数为 30、最小空闲为 4，
可用 `BUSINESS_DB_MAX_POOL_SIZE` / `BUSINESS_DB_MIN_IDLE` 调整。
不同业务的 Kafka 消费组、事务 producer、合约缓存及 outbox 仍按原职责保留；
本次没有把不同产品或事务边界混到同一个队列。
价格消费只保留一个实例，过期上限采用原账户侧较严格的 5 秒。

## 调用与安全

1. 公共请求进入现有 Gateway / Binance 兼容入口，执行身份、用户状态、权限、审批检查。
2. `GatewayProxyService` 校验当前产品线，将已验证的身份头交给 `LocalBusinessApi`。
3. 三个 `*LocalRoutes` 显式调用对应 Controller 的本地 Java 方法；请求绑定保留必填参数、
   Bean Validation、管理维护的 `@ModelAttribute` 校验、异常状态和异步命令结果。
   这些类只承担网关协议转换，没有另建服务发现、RPC 框架或反射执行器。
4. Controller 调用原业务 Service；命令通过 Aeron 进入独立 Core，查询使用原有权威查询/投影边界。
5. Gateway 保留审计和响应脱敏。命令收据的 `commandResultUrl` 指向
   `/api/v1/gateway/trading/commands/{commandId}`，不能引导客户端访问已受保护的原始 URL。

订单、账户启动时通过 `InstrumentService.snapshot` 本地加载合约，不再使用 Instrument Feign。
本产品的余额调整及划转也直接调用 `AccountCommandGateway`；明确拒绝与结果未知仍分别处理，
超时不能误判成未扣款/未入账，不能据此直接退款。

公共调用不会向 `9080`、`9084`、`9086` 发起内部 HTTP 请求。
路由配置中的 `base-url: "local:"` 是现有路由契约的本地标识，不是监听地址。
`LocalBusinessApiTest` 检查业务公开 Mapping 均有本地路由，新增接口时必须同步该协议入口。

`BusinessEndpointConfiguration` 拦截原始业务 Controller URL，未持内部凭证返回 404；
伪造 `X-User-Id`、`X-Admin-User-Id` 不能绕过 Gateway。
独立后台服务通过 `X-Business-Internal-Token` 使用原有 RPC 契约，其他既有身份、签名校验仍执行。
部署时给业务应用及对应后台实例配置同一个非空 `BUSINESS_INTERNAL_TOKEN`；
不要把它放进前端，也不要将普通外部请求的同名头转发到内部服务。
外部应用的 Feign 默认地址已改为 `9094`，默认请求头从该环境变量读取。

## 首期产品及托管资金

默认 `PRODUCT_LINE=LINEAR_PERPETUAL`。一个业务实例的订单、账户使用同一产品线；
不接受用户通过 header、query、嵌套 body 或 Binance 路径切换到其他产品。
其他五条产品的代码和协议保留，需要时另启独立产品实例，不能在同一账户状态里混用。

首期跨产品划转默认关闭：`GATEWAY_PRODUCT_TRANSFER_ENABLED=false`。
托管钱包仍默认关闭。本次没有把原本入现货/FUNDING 的充值改为直接入永续保证金。
启用托管时，现货业务实例可在本地完成调整；永续实例必须显式配置独立现货业务实例的
`GATEWAY_SPOT_ACCOUNT_BASE_URL`、原有内部签名配置及业务内部凭证。
跨产品划转的目标 URL 也必须显式配置；缺配置时拒绝执行，不能回退到当前产品。

## 构建及启动

使用 HotSpot JDK 27，执行前检查 `java -version` / `mvn -version`。

```bash
mvn -pl surprising-gateway -am package -DskipTests
# 先启动 PostgreSQL、Kafka、Redis 和独立 Aeron Core。
# 为所有相关进程配置 PRODUCT_LINE、BUSINESS_INTERNAL_TOKEN、数据库和 Kafka 地址。
java --enable-native-access=ALL-UNNAMED \
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens=java.base/java.util.zip=ALL-UNNAMED \
  -jar surprising-gateway/target/surprising-gateway-1.0.0-SNAPSHOT-exec.jar
```

完整本地启动脚本仍是 `scripts/start-product-line-providers.sh`。
启动顺序为 Core → 业务应用 liveness → price → market-data → 生命周期/资金费 → maker；
启用 realtime/export 时按原配置先启动对应进程。
业务应用先提供合约内部查询，价格启动后再满足标记价 readiness，避免双方启动互相等待。
原来三个 provider 的进程、端口和单独启动命令不再使用。

## 验证方法

- Maven 合并模块测试覆盖原四模块的业务测试、本地路由/权限/产品隔离、资金结果状态分类。
- PostgreSQL 集成测试使用隔离数据库：合约六产品配置审计、交易维护和托管退款对账。
- `MergedBusinessHttpIntegrationTest` 使用真实 HTTP 注册两个账户，做市账户持续提供成交和平仓流动性，
  验证挂单、成交、撤单、持仓、平仓、冻结归零以及余额加手续费的守恒。
  测试使用独立 Core、隔离 Kafka 标记价输入；测试初始化资金通过 Core 命令完成。
- 该测试仅在 `MERGED_BUSINESS_IT_BASE_URL=http://127.0.0.1:<port>` 时运行，另需
  `MERGED_BUSINESS_IT_KAFKA`；禁止对已有业务环境执行。生产镜像中不包含这些测试。
- 下游共享 API、价格、资金费、生命周期、行情、realtime、maker 执行对应模块测试。

这次没有修改 Core 生产源码，也不宣称提升 Core 吞吐。没有重新进行饱和压测、长稳、
三节点故障切换或完整钱包外部调用验证；WebSocket、风险/强平等主要通过原有模块回归覆盖。

## 2026-09-22 本机验证结果

基线为 `b675c6bd`，HotSpot Corretto 27.0.0.33.1、Maven 3.9.16。

| 验证范围 | 测试数 | 结果 |
| --- | ---: | --- |
| 合并业务应用，包括 PostgreSQL/真实 HTTP 集成 | 579 | 通过，0 跳过 |
| instrument / trading / account 共享 API | 47 | 通过 |
| price | 85 | 通过 |
| funding | 17 | 通过 |
| derivatives-lifecycle | 1 | 通过 |
| market-data | 21 | 通过 |
| realtime | 2 | 通过 |
| maker | 45 | 通过 |
| 合计 | 797 | 0 失败、0 错误、0 跳过 |

真实环境使用隔离 PostgreSQL `55392`、Kafka `59092`、Redis `56379`、业务 HTTP `59994`，
另起一个本机 Aeron 成员（`LINEAR_PERPETUAL`、cluster id 101）。
Core 和业务应用为不同 PID；业务应用 liveness 正常，合约查询成功，原始业务 URL 无内部凭证返回 404。
真实 HTTP 测试包含两次成交（开仓、平仓）及一笔额外挂单撤销。两用户最终持仓、冻结均为零，
初始余额总计 `2,000,000,000,000,000` 单位，最终余额加手续费 `1,400,000,000` 单位等于初始总额。
最后还在重启后的最终业务包上复验本地路由、编码路径的管理员限制、资金结果分类和公共命令查询 URL。

主要命令（环境变量中的数据库均为本轮新建的隔离测试库）：

```bash
mvn -pl surprising-gateway -am -DskipTests install
INSTRUMENT_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:55392/merge_instrument_test \
MAINTENANCE_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:55392/merge_maintenance_test \
SURPRISING_WITHDRAWAL_IT_DATABASE_URL=jdbc:postgresql://127.0.0.1:55392/merge_custody_test \
SURPRISING_WITHDRAWAL_IT_DATABASE_USER=maintenance \
MERGED_BUSINESS_IT_BASE_URL=http://127.0.0.1:59994 \
MERGED_BUSINESS_IT_KAFKA=127.0.0.1:59092 \
mvn -pl surprising-gateway test

mvn -pl surprising-instrument/surprising-instrument-api,surprising-account/surprising-account-api,surprising-trading/surprising-trading-api test
mvn -pl surprising-price/surprising-price-provider,surprising-funding/surprising-funding-provider,surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-provider,surprising-market-data/surprising-market-data-provider,surprising-realtime/surprising-realtime-provider,surprising-maker clean test

PRODUCT_LINE=LINEAR_PERPETUAL RUN_ID=business-merge-review ACTION=dry-run \
AERON_CLUSTER_HOSTNAMES=127.0.0.1 bash scripts/start-product-line-providers.sh
TASK1_TEST_ROOT="$PWD/.local-logs/business-merge-preflight-20260922" \
bash scripts/test-production-chain-preflight.sh
```

启动顺序 dry-run、production-chain-preflight 合约测试均通过。
迁移后重新编译还暴露出 funding/maker 测试使用旧版 CoreResponse/OrderCommandReceipt 构造器，
已将测试夹具对齐当前共享 API，未增加兼容构造器或改变 Core。

扩展执行 `mvn -pl surprising-gateway -am test` 时，未修改的 Core 模块原有
`CoreMaintenanceTest.statusOnlyChangesPreserveOpenStateMathAndRecoverWithoutReleasingMaintenance`
在六产品参数上报 `completed place admission must be collected`，因此该轮 reactor 在 Core 停止。
单独重跑确认异常仍存在；尝试改变测试等待方式也未消除，已撤回该尝试。
这 6 项不计入上述 797 项通过数，本次没有宣称 Core 全量测试通过，Core 生产代码与测试均未修改。
后续如修复维护准入逻辑，应作为独立 Core 变更按资金/恢复和性能标准验收。

最后补回原 trading 的 readiness 分组（`readinessState,markPriceReadiness`），核对组件名称，
重新打包和 YAML 加载测试通过。真实 HTTP 验证包与最终配置包的 SHA-256 分别保存在
[`validation/business-merge-20260922.json`](validation/business-merge-20260922.json)。

本轮业务应用、Core、PostgreSQL、Kafka 和 Redis 均已停止；已清理隔离运行目录、Archive、
preflight 数据、临时日志及已汇总的测试报告，保留构建产物和逐测试类验证摘要。
