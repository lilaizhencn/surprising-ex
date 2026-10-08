# 单 Gateway 接入多产品线

一个 `surprising-gateway` JVM 同时接入启动时选择的产品，例如
`GATEWAY_PRODUCT_LINES=LINEAR_PERPETUAL,SPOT`。六条产品线都支持：`SPOT`、
`LINEAR_PERPETUAL`、`INVERSE_PERPETUAL`、`LINEAR_DELIVERY`、`INVERSE_DELIVERY`、`OPTION`。
HTTP 和 WebSocket 统一使用 `9094` / `/ws/v1`，无需额外的 `9194` SPOT Gateway。
每条产品仍使用独立 Aeron Core；这里只合并接入进程，不合并撮合、账户或结算状态。

## 请求和状态归属

1. `GatewayProductSelection` 解析产品线头、查询参数、正文及兼容 API 路径。
   多产品部署必须明确指定产品；各处选择不一致返回 400，未启用产品返回 404。
   单产品部署允许省略选择，使用唯一启用产品。共享合约列表和精度接口不要求产品选择。
2. `GatewayProxyService` 完成原有用户身份、权限、审批和审计校验，注入可信身份和产品线。
3. `GatewayProductServices` 将请求交给该产品的 `LocalBusinessApi`，订单和账户通过 Java 方法调用。
   每条产品拥有 Spring 子容器，隔离 Aeron 连接、合约和价格缓存、Kafka 监听以及定时任务。
   认证、JDBC 连接池、Redis、币种配置、合约管理和 WebSocket 会话由公共容器共享。
   子容器的配置绑定必须指向本产品环境，不能沿用父容器的 Boot 配置适配器或 WebSocket Kafka 配置。
4. 账户和交易方法把命令交给对应的独立 Core。回执查询 URL 带 `productLine`，轮询不会跨产品。
5. 原有独立 maker、行情及生命周期服务的内部 HTTP 契约由 `ProductInternalHandlerMapping`
   路由到相应业务容器；内部 JSON 请求正文缓存上限 4 MiB，并继续由 MVC 校验和绑定。
   公网 Nginx 禁止 `/api/v1/accounts/`、`/api/v1/trading/` 和 `/internal/`，内部服务走本机或私网。

`SpotAccountClient` 直接调用 SPOT `AccountCommandGateway`，充值和提现不需要运行现货交易币对。
`ProductAccountAccess` 直接调用划转源、目标产品的账户方法。两端均启用才允许提交源扣款。
Core 的 pending transfer 仍是资金权威来源；重复请求保留原业务标识，结果未知时保留待处理状态，
由原有对账任务继续处理，不能当作失败直接退款。FUNDING 的划转目标仍按原业务映射到 SPOT。

`OpenInterestPublisher` 按启用产品分别查询，同一个连接可以订阅不同产品的同一个 instrumentId。
Kafka 模式为每个产品创建独立 fanout 监听；Aeron realtime 模式沿用公共接收器和带产品标识的路由。
每个产品的定时任务使用自己的线程池，单条 Core 的超时不会阻塞其他产品的定时任务。

## 启动配置

这些是部署拓扑参数；合约、费率、风险和做市业务配置仍由后台管理并保存到数据库。

```bash
export GATEWAY_PRODUCT_LINES=LINEAR_PERPETUAL,SPOT
export AERON_CLUSTER_HOSTNAMES=127.0.0.1
export AERON_EGRESS_HOSTNAME=127.0.0.1
export GATEWAY_PRODUCT_TRANSFER_ENABLED=true
# 按部署环境配置 SPRING_DATASOURCE_*、Kafka、Redis、认证密钥及托管钱包连接。
scripts/start-gateway.sh
```

`GATEWAY_PRODUCT_LINES` 必填且不能重复；不再用 Gateway 的 `PRODUCT_LINE` 选择业务产品。
Core、maker、price、realtime 和生命周期等独立产品进程仍使用各自的 `PRODUCT_LINE`。
Core 响应超时限定为 1 毫秒到 1 分钟，连接数限定为 1 到 64。
默认每条 Core 使用公共 `AERON_CLUSTER_HOSTNAMES`（一个或三个主机）、`AERON_EGRESS_HOSTNAME`、
`AERON_RESPONSE_TIMEOUT`、`AERON_CLIENT_CONNECTIONS`。异机部署可分别覆盖：

```bash
GATEWAY_CORE_SPOT_HOSTNAMES=spot-core.example
GATEWAY_CORE_SPOT_EGRESS_HOSTNAME=gateway.example
GATEWAY_CORE_SPOT_RESPONSE_TIMEOUT=5s
GATEWAY_CORE_SPOT_CLIENT_CONNECTIONS=4
```

其余产品使用相同后缀，如 `GATEWAY_CORE_INVERSE_DELIVERY_HOSTNAMES`。
端口、cluster id 和 stream id 继续由 `ProductLineClusterLayout` 按产品区分。
`/api/v1/runtime` 返回实际启用产品；readiness 汇总各产品 Core 和标记价格状态。

独立行情、生命周期和做市仍按产品部署。多产品 Gateway 必须为使用的外部服务提供产品地址，例如：

```bash
GATEWAY_ROUTE_CANDLESTICK_LINEAR_PERPETUAL_BASE_URL=http://127.0.0.1:9095
GATEWAY_ROUTE_CANDLESTICK_SPOT_BASE_URL=http://127.0.0.1:9195
GATEWAY_ROUTE_PRICE_MARK_LINEAR_PERPETUAL_BASE_URL=http://127.0.0.1:9082
```

账户和订单不再配置远程产品地址；旧的 `GATEWAY_ROUTE_ACCOUNT_*` 和
`GATEWAY_SPOT_ACCOUNT_BASE_URL` 已移除。外部服务变量见 `application.yml` 的各 `product-routes`。多产品请求若缺少对应外部服务地址，返回 404，
不会沿用其他产品的默认地址。外汇是全系统共享服务，`price-fx` 不按产品拆分。
单产品部署可以直接使用该服务的 `base-url`。

只启动新增产品 Core：

```bash
CORE_ONLY=true PRODUCT_LINE=SPOT RUN_ID=spot-core AERON_CLUSTER_HOSTNAMES=127.0.0.1 \
  scripts/start-product-line-providers.sh
# 停止时使用同一 CORE_ONLY、PRODUCT_LINE、RUN_ID、RUNTIME_ROOT，设置 ACTION=down。
```

此模式不启动 Gateway、price、realtime、maker 或生命周期，不初始化数据库；各产品使用独立进程锁。
需要现货行情和交易时再运行该产品的行情服务；仅充值、提现和划转时 SPOT Core 即可。
Gateway 启用的产品集合属于启动拓扑；新增集合成员需重新启动 Gateway。
已启用产品中的合约新增、编辑、上市与开启交易继续使用现有后台热更新，不重启进程。

## 测试服务器迁移顺序

1. 保留现有各产品 Core 的数据目录、cluster id 和 RUN_ID，不执行 `fresh`，不删除 Archive 或快照。
2. 主 Gateway 启用 `LINEAR_PERPETUAL,SPOT`，迁入原 SPOT Gateway 的托管钱包、认证、安全和合规部署参数；
   数据库和密钥继续使用原值。移除 `GATEWAY_SPOT_ACCOUNT_BASE_URL`。
3. 补齐现货和永续的外部服务路由。保留 SPOT realtime `9195`，它负责行情/K 线，并非多余 Gateway。
4. 若使用 Aeron realtime，两产品 router 通过共享 Redis 发现同一个 Gateway 节点与其接收地址；
   保留各 Core/router 的产品专属输入通道，不要把两条 Core 的生产端口合并。
5. 更新 Nginx 为本目录的单 Gateway 模板；`/api/v1/runtime` 必须代理真实接口，不硬编码响应。
6. 验证资金、地址和提现对账状态后停止原 `9194` JVM；不要停止 SPOT Core 或清理持久数据。

## 验证

使用 HotSpot JDK 27。运行共享依赖测试及网关测试：

```bash
mvn -pl surprising-gateway -am test
```

`MultiProductMoneyIntegrationTest` 使用六个真实 Core 状态机，核对余额隔离、幂等充值/提现、
跨产品划转守恒、超额拒绝、源/目标提交后回执丢失及快照恢复后的补偿重试。
`GatewayProductSelectionTest` 覆盖六产品别名、冲突、未知产品、批量正文和部署配置校验。
`ProductBusinessConfigurationTest` 覆盖父/子配置绑定及 WebSocket Kafka 产品隔离。

真实 HTTP 集成必须使用隔离本机数据库、Kafka、Redis，以及六条独立 Core：

```bash
MULTI_PRODUCT_GATEWAY_IT_BASE_URL=http://127.0.0.1:19494 \
MULTI_PRODUCT_GATEWAY_IT_KAFKA=127.0.0.1:19092 \
MULTI_PRODUCT_GATEWAY_IT_FX_PORT=19482 \
MULTI_PRODUCT_GATEWAY_IT_OPERATIONS_TOKEN="$QA_OPERATIONS_TOKEN" \
mvn -pl surprising-gateway -Dtest=MultiProductGatewayHttpIntegrationTest test
```

Gateway 的 `GATEWAY_WITHDRAWAL_VALUATION_BASE_URL` 必须指向同一个测试 FX 端口。
该测试提供 USDT 到 USDT 的恒等 FX 报价，标记价使用隔离 Kafka 的测试输入，
其他账户、交易、身份、数据库和 Aeron 调用均真实运行。
测试以模拟做市账户提供开平仓流动性，覆盖六产品挂单、成交、撤单、持仓、平仓、冻结归零、
用户+做市+Treasury 的资金守恒，以及一条 WebSocket 同时接收六产品公共/私有推送。
不启动 custody wallet，不对外发送真实充值或提现。


### 完整 Aeron 推送验证

启用 Gateway 的 `SURPRISING_REALTIME_ENABLED=true`，六个 Core 分别设置
`surprising.realtime.directory` 为各自 MediaDriver 目录，设置 `surprising.realtime.channel`
指向实时 router 输入通道，并分别设置独立 `surprising.realtime.control-channel`。
router 的 `control-channels`、`control-destinations` 必须包含六条产品线，输出节点从 Redis 订阅租约发现。
这些连接是部署参数，业务设置仍从数据库加载。

在上面真实 HTTP 测试的环境变量基础上设置：

```bash
MULTI_PRODUCT_GATEWAY_IT_REALTIME=true \
MULTI_PRODUCT_GATEWAY_IT_ROUTED=true \
mvn -pl surprising-gateway \
  -Dtest=MultiProductGatewayHttpIntegrationTest#sixRealCoresRouteTheirExecutionsThroughOneGatewaySocket test
```

此方法等待 Core 完成新合约注册，并等待两用户各六条产品的权威实时快照 READY 后开始交易。
成交与 executionReports 由真实 Core 产生，经生产 `RealtimeRouter`、Redis 路由和 Gateway 接收器进入 WebSocket，
不注入私有 Kafka 事件。每个用户一条连接，验证六产品公共成交、执行报告、用户隔离和资金守恒。
价格就绪输入和 USDT 恒等 FX 报价仍使用隔离测试输入；不依赖外部交易所或链上钱包。

单独验证接收器的同 instrumentId 跨产品隔离时，额外设置
`MULTI_PRODUCT_GATEWAY_IT_AERON_DIR` 为 Gateway 应用侧 MediaDriver 目录，执行
`aeronRealtimeUsesOneSocketForSixProductsAndKeepsPrivateUsersIsolated`。
该方法的输入为协议帧，与上述真实 Core 完整链路测试分别记录。

## 2026-10-08 本地验证结果

环境为 HotSpot Corretto 27、Maven 3.9.16；一个 Gateway、六条独立单节点 Core，
隔离 Kafka 19092、Redis 16379，HTTP 19494。另起实际 realtime provider 19495 验证完整推送。
待提交文件单独导出后构建和验证，未把工作区已有的 KYC、托管钱包或公告修改混入本次提交。

| 范围 | 用例数 | 结果 |
| --- | ---: | --- |
| Gateway，包括认证/合约/提现数据库、六产品维护结算、真实 Core→router→WebSocket | 811 | 0 失败、0 错误，6 项环境门控跳过 |
| Core | 1035 | 0 失败、0 错误，2 项门控跳过 |
| product/protocol/client 和共享 API 等依赖模块 | 218 | 全部通过 |
| price | 96 | 0 失败、0 错误，1 项数据库门控跳过 |
| derivatives-lifecycle（含 funding、强平） | 45 | 0 失败、0 错误，1 项数据库门控跳过 |
| realtime / K 线 | 59 | 0 失败、0 错误，6 项数据库门控跳过 |
| maker | 109 | 0 失败、0 错误，2 项数据库门控跳过 |

Gateway 的 Kafka 模式真实 HTTP 方法已另行执行通过。旧的单产品/固定端口/20 个策略负载脚本
未在多产品运行环境启用，由新六产品 HTTP 流程覆盖本次交易接入改动；容量压测未包含在本轮功能验证中。
各模块的数据库门控用例另行在隔离 PostgreSQL 15439 的 `sixline_qa` 数据库执行，
该库使用本次待提交的 init.sql 初始化。28 项全部通过，无跳过，包含下单设置 6 项、
行情设置 6 项、生命周期设置 5 项、成交投影 5 项、K 线查询 1 项、做市设置 2 项及路由配置 3 项。

已核对：六产品挂单、成交、撤单、持仓、平仓，最终持仓、冻结和保证金释放，
用户+做市+Treasury 资金差额均为 0。重复充值、提现和划转不重复记账，
超额拒绝、两端提交后回执丢失、快照恢复和补偿重试由真实状态机测试覆盖。
暂停期权 Core 时其他五条账户查询和 liveness 正常，readiness 返回不可服务；恢复后余额不变。
Gateway 和六条 Core 正常关闭再启动后，六条余额、已有登录令牌和 readiness 验证通过。
新启动入口 scripts/start-gateway.sh 已用于实际启动验证。
最终打包产物删除旧远程账户路由配置后，再次执行真实六 Core→router→WebSocket 交易验证通过。

`CORE_ONLY=true` 已实际完成 SPOT 单节点 up→status→down：仅一个 Core 进程，
只提供 Core/tools 两个 JAR；PostgreSQL、Kafka、Redis 地址故意设为不可用端口，
Core 查询探针仍通过，未启动 HTTP 服务或初始化数据库。六产品 dry-run、两个启动脚本的 Bash 语法检查
及 production-chain preflight 契约检查也通过。preflight 契约检查使用 1 GiB 的隔离数据增长预算，
不代表生产容量验收。

本轮不部署服务器；三节点选主、容量压测、真实链上充值/提现和外部行情源不属于本次本地验证范围。

清理状态：本轮 Gateway、六 Core、realtime、Kafka、Redis 和独立 PostgreSQL 已停止；
Core-only 探针进程已退出。已删除本轮临时 Archive、Aeron 目录、Kafka 数据、数据库、
独立构建树和 preflight 测试目录；共享 PostgreSQL 5432 保持运行，既有数据及其他工作区修改保留。
临时日志中的结果已汇总到本节，清理后不再作为可打开的交付文件。
