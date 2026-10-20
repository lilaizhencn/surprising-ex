# Surprising EX

## Project overview

Surprising EX is a Java and Maven based exchange backend under active development. It provides order matching, accounts, risk management, market data, and APIs for six separate product lines:

| Product line | Main functions |
| --- | --- |
| Spot | Orders, matching, asset holds, and settlement |
| USDT-margined perpetuals | Positions, margin, funding, and liquidation |
| Coin-margined perpetuals | Inverse contract accounting, funding, and risk |
| USDT-margined delivery futures | Positions and expiry settlement |
| Coin-margined delivery futures | Inverse contracts and expiry settlement |
| Options | Premiums, positions, exercise, and expiry |

Product line identity controls routing and keeps instruments, accounts, trading state, events, and risk rules separate. The single-node deployment below starts **USDT-margined perpetuals only** (`LINEAR_PERPETUAL`).

## Architecture

Backend snapshot field evolution and historical command recovery: [恢复兼容设计](docs/core-snapshot-evolution.md).

The single-node stack has six Java processes:

| Process | Responsibility |
| --- | --- |
| Aeron Core | Ordered commands, matching, authoritative balances and positions, snapshots, and recovery |
| Gateway | Authentication, instrument, account and order APIs, WebSocket access, and the shared application Aeron MediaDriver |
| Price | Index and mark prices |
| Realtime | Market data, candles, trade export, and realtime routing |
| Derivatives lifecycle | Funding, risk scans, and liquidation workflows |
| Maker | Reference-market-based quotes and trading |

Gateway sends trading commands to the single-member Aeron Cluster. Core owns trading state and persists its log and snapshots through Aeron Archive. Realtime exports committed trades to Kafka; consumers and query services use PostgreSQL for configuration and durable business records and Valkey for rebuildable views. Gateway delivers public market data and private account updates over WebSocket. The maker submits orders through the same trading interfaces as other clients.

The Maven modules follow these boundaries: [`surprising-aeron-core`](surprising-aeron-core/) contains the cluster and client; [`surprising-gateway`](surprising-gateway/) contains the consolidated identity, instrument, trading, and account application; [`surprising-price`](surprising-price/), [`surprising-realtime`](surprising-realtime/), [`surprising-derivatives-lifecycle`](surprising-derivatives-lifecycle/), and [`surprising-maker`](surprising-maker/) contain the other four processes. Shared API modules define product-line and business contracts.

现货市价单已接通 realtime 盘口保护价（2026-10-19）：外部请求仍为 `MARKET`、`priceTicks=0`、`IOC/FOK`。`AeronOrderCommandService` 从 `MatchingMarketDataService` 读取 Valkey 中的完整可用盘口，买单取卖一、卖单取买一并应用既有滑点配置；无可用盘口或对手方流动性时拒绝提交。保护边界随原 MARKET 命令写入 Core 日志，`CoreOrderDecisionResolver` 负责现货资金准入，衍生品市价单仍要求零价格及新鲜标记价。原生订单簿已补齐限价 FOK：同一撮合线程先检查限价内数量，足量才全部成交，不足不消耗挂单。撮合依赖需从 `exchange-core-lilaizhencn` 的 `5363426` 干净提交构建安装，再构建并配套更新 Core/gateway；行情查询和推送继续由 realtime 提供。

本轮 261 项订单簿回归、85 项后端定向回归及单节点 FUSED 现货实链路通过。真实做市下完成市价 IOC/FOK、限价 FOK、挂单撤单及数量不足拒单；WS 与 REST 的 6 笔成交标识一致，撤去做市挂单后冻结清零，用户、做市与财库逐资产守恒差额为 0。本轮未验收原生移动端/浏览器 UI、余额比例、未取得命令号即断网恢复及其余产品线，详见 [验证与清理记录](docs/validation/spot-realtime-market-fok-20261019.json)。

进一步修复查询装配：产品线容器现在显式注册 `ValkeyUserQueries`，普通订单、余额持仓及用户触发单当前查询不再退回 Core。普通订单离开活动视图后从 realtime 提交投影查询终态；后台普通订单全局查询和撤单预览也使用提交投影。Web/client 回执恢复已统一到 `/api/v1/gateway/trading/commands/{commandId}`。192 项查询回归、Web 151 项及 Flutter 131 项测试通过；独立 FUSED 现货实测确认回执地址、终态查询、余额一致和资金守恒。全栈启动默认开启 realtime 与提交导出。专用查询及其他产品线仍按范围逐项验收，详见 [查询与清理记录](docs/validation/public-realtime-query-20261019.json)。

条件单终态单笔查询及后台列表、详情、时间线现改由 realtime 的已提交 SQL 投影提供，Core 继续承担交易控制。投影缺失或重建未完成返回明确 503；首次启动从 realtime 自身检查点重建保留状态。升级需先应用 `deployment/migrations/20261020-trigger-read-projection.sql`，详见 [条件单查询与回执边界](docs/trigger-realtime-queries.md)。

后台全局成交列表已接入 realtime 的已提交成交投影，支持同侧用户/订单筛选、双向游标分页，tradeId 与公开行情保持一致。已有主检查点会通过独立有限重放补齐历史，不重发 Kafka 或交易命令。需应用 `deployment/migrations/20261020-execution-read-projection.sql`，详见 [成交查询与重建](docs/execution-realtime-queries.md)。

后台交易统计改由 realtime 发布完整前缀采样：已提交账户/下单命令、成交、当前委托、持仓及条件单。未订阅的做市账户也计入当前状态；数量按合约列出，不跨单位累加。需应用 `deployment/migrations/20261020-trading-statistics.sql`，统计口径、冷态读取边界与重建见 [交易统计](docs/trading-statistics-realtime.md)。

现货余额比例下单继续对齐（2026-10-19）：`GET /api/v1/gateway/trading/price-protection` 经既有
`TradingLocalRoutes → OrderRequestService → OrderService` 返回当前产品的 `marketMaxSlippagePpm`。
该只读接口直接读取现有业务配置，不查询 Core、不保存第二份状态；热更新后的配置可立即读取。
Web/client 使用 instrument 的数量、名义价值限制与 asset-scales 做整数计算，买入按
`OrderAdmissionMath.fragmentationSafeFeeDebit` 的逐步舍入方式预留手续费，卖出按基础资产向下取整；
BBO 使用选定方向与档位，市价预估使用当前保护参数。最后准入、冻结和结算仍由 Core 处理。
新增 DTO 只承担此 HTTP 契约，没有新增交易执行层。本轮 114 项 Java 定向测试、Web/client
数值与界面检查详见 [比例下单验证](docs/validation/spot-order-sizing-20261019.json)。
本轮未重启 FUSED 实栈，不将受控浏览器和 Flutter 测试认作真实成交或真机验收。

### 当前 Core 线程职责（2026-10-13）

用户余额、预留、仓位、订单、算法单、触发单、清算及风险记录由所属 `AccountLaneState` 的实际 worker 读写。`SettlementLaneWorker` 在启动前绑定线程；运行中的 Owner 不能直接读取账户表、进入 Lane 写作用域或执行账户闭包。已删除四张 `published*` 业务镜像，`ActiveOrderIndex` 仅保存订单 ID、用户 ID、币对；触发、算法及清算索引只保存路由与候选筛选键。Owner 定序、检查路由身份、派发任务并按日志顺序发布完成结果；命令内的不可变回执在提交后清理，不留第二份业务状态。

手续费、保险基金和 Treasury 仍只由原 `SystemLedgerLane` 写入。生产命令不再在 Owner 汇总资金流水或重复计算守恒：成交由 Ledger 消费 Lane 原语回执校验，控制命令由 Ledger 合并已完成的账户变更和 Treasury 变更，校验成功后才允许发布；失败保留原有回滚边界。强平检查、保险索赔读取、ADL 和触发子订单准备在实际 Lane 执行；自成交防护所需取消集合也在 Lane 准入时生成。

Core 内部账户、触发、算法及强平执行/保险/ADL 分页查询读取实际 Lane，通过已有异步查询槽响应；保险预算由 Ledger 计算。外部 Redis/Valkey 投影链路沿用现有机制。完整状态物化仅在显式冷态检查屏障借用所有权，生产运行不缓存完整业务视图。符号缓存、DirectBuffer 读取和事件缓冲复用继续保留。本轮仅做必要功能回归及直接受影响基准模块的编译，不执行压测、JMH/JFR、快照恢复或 wallet；功能通过不表示吞吐或零分配已重新验收。见 [Owner 状态边界验证](docs/validation/owner-shared-state-20261013.json)。下文的日期记录描述各阶段当时的状态。

## 订单与保证金链路简化

订单入口的标准化、校验和提交已合并为顺序业务步骤：`OrderService` 不再传递无用途的校验对象，
`AeronOrderCommandService` 每次只读取一次命令结果载荷。单订单 Valkey 查询由
`ValkeyUserQueries.order` 直接读取目标 ORDER 与元数据；保证金调整直接向既有 Core 余额写入口
提交数值。八项简化的源码入口、六产品线回归和局部分配数据见
[验证记录](docs/validation/order-simplification-20261018.md)。

Core 批量准入进一步按当前业务项使用局部数值，只保留跨阶段和回滚所需的订单、client key 及结果。
每项的准入快照由 `OrderBatchItem` 持有，Matcher 与实时发布借用同一份稳定值；
`MatcherSettlementEvent` 统一经 Direct 原生成交交接、实际 Lane/Ledger 完成后收集，删除旧结算入口及重复状态。
提交收尾、调账划转和旧测试游标一并收敛，源码入口与验证范围见
[Core 八项简化验证](docs/validation/aeron-core-simplification-20261018.md)。

## 风险查询口径

`RuntimeRiskQueryService.snapshots` 在查询边界使用当前仓位、钱包和标记价，统一计算名义价值、未实现盈亏、维持保证金、权益、保证金率和风险状态；返回的价格序号对应本次估值价格。全仓按用户及结算资产汇总，扣除逐仓占用；逐仓仅使用本仓保证金，期权权益使用期权市值。

后台有界风险扫描保存的 `RiskSnapshotRuntime` 用于风险扫描和清算流程，可能落后于当前价格，不能与新价格拼接为一份查询结果。查询不写入扫描快照，不生成强平任务，也不修改余额、持仓或扫描游标。新仓位在首次扫描前即可查询；缺少必要价格时报错，不返回旧估值伪装为最新值。

2026-10-05 测试环境核验：430 个 U 本位合约下，扫描预算 64 时 BTC 仓位快照连续采样落后标记价 20–37 个序号。通过带版本校验的风险运行配置接口将 `scanBatchSize` 调整为 512（保持 25ms 配置间隔），随后 8 次采样滞后为 0–1 个序号。该临时预算作为 Core 运行配置持久化。风险续扫现在根据待处理合约数自动确定本轮预算：`min(4096, max(配置基线, 待处理合约数 × 8))`；积压减少后自动回落，保留 TP/SL 预算和原有 Lane 公平轮转。查询一致性不依赖扫描及时完成。

验证记录：JDK 27 下已执行 Core 与生命周期模块回归，以及风险估值、风险预算和扫描公平性定向测试。`DerivativeRiskBoundaryBenchmark` 对 U 本位交割、币本位交割和期权执行 1×1s 预热、2×1s 测量、单 fork；分别为 57.614、55.991、67.309 轮/秒，三条路径接受数与终态数相等，未完成数为 0。该短测用于验证路径可完成及资金断言，不作为吞吐提升结论。线上完整历史成交与资金流水投影缺失，不能据此声称历史已实现盈亏已完整对账。

## Single-node deployment

Run these steps on the target Linux host. The deployment uses existing PostgreSQL, Kafka, and Redis/Valkey services. Install **HotSpot JDK 27**, Maven, `psql`, and the build's normal dependencies first. Configure PostgreSQL, Kafka, and Valkey at the addresses in the environment file, or change those values to match the host.

1. Check the toolchain and build all six application JARs from the release checkout on the server:

   ```bash
   java -version
   mvn -version
   mvn -pl \
     surprising-aeron-core/surprising-aeron-service,\
     surprising-aeron-core/surprising-aeron-tools,\
     surprising-price/surprising-price-provider,\
     surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-provider,\
     surprising-realtime/surprising-realtime-provider,\
     surprising-gateway,\
     surprising-maker \
     -am package -DskipTests
   ```

2. Make the checkout available at `/opt/surprising-ex`. Prepare the runtime directory and a private environment file:

   ```bash
   install -d -m 0750 /var/lib/surprising/linear-perpetual/runtime /etc/surprising
   cp deployment/test-single-node/linear-perpetual.env.example \
     /etc/surprising/linear-perpetual.env
   chmod 600 /etc/surprising/linear-perpetual.env
   ```

   Set the PostgreSQL password and a unique `GATEWAY_JWT_SECRET`. Review the database and broker addresses, heap limits in the environment file. The launcher applies [`init.sql`](init.sql) **only when the `instruments` table is absent**. Populate the desired instrument catalog and any test users and funds through their normal application flows before relying on maker trading.

3. Load the environment for a preflight check, then install and start the systemd unit:

   ```bash
   cd /opt/surprising-ex
   set -a; . /etc/surprising/linear-perpetual.env; set +a
   ./scripts/linear-perpetual-single-node.sh dry-run
   cp deployment/test-single-node/surprising-linear-perpetual.service \
     /etc/systemd/system/surprising-linear-perpetual.service
   systemctl daemon-reload
   systemctl enable --now surprising-linear-perpetual
   ```

   The launcher starts Core first, waits for the single-node leader, then starts Gateway, Price, Realtime, Derivatives lifecycle, and Maker. Check completion and health:

   ```bash
   systemctl status surprising-linear-perpetual --no-pager
   ./scripts/linear-perpetual-single-node.sh status
   curl -fsS http://127.0.0.1:9094/actuator/health/readiness
   ```

4. For later releases, build the new checkout on the server, stop the unit, point `/opt/surprising-ex` at the new checkout, and start the unit. **Keep** the runtime directory and Aeron Archive across restarts so Core recovers from its snapshot and log. Do not rerun `init.sql` against an existing database. Use `systemctl stop surprising-linear-perpetual` to stop the stack.

The unit and example configuration are in [`deployment/test-single-node`](deployment/test-single-node/). This deployment has one Aeron member and therefore no failover quorum.

小时快照任务由 `scripts/linear-perpetual-snapshot.sh` 执行，直接使用已部署的 Core executable JAR 中的 Aeron ClusterTool，无需另行构建诊断工具模块。只有确认健康非主节点或整个栈已停止才跳过；JAR 缺失、工具/超时错误、读取 recording-log 或请求快照失败均以非零状态报告，不能显示成成功跳过。完成要求 recording-log 新增两条有效快照记录。脚本边界回归：`python3 -m unittest scripts/tests/test_linear_perpetual_snapshot.py -v`。

## 后台合约维护与热上线

合约配置从 `InstrumentLocalRoutes` 进入 `InstrumentService.edit/editStatus`：校验管理员原因、期望变更版本及参数后，在数据库锁内保存并发布快照事件。并发旧版本返回 409，不能覆盖另一管理员的修改。创建后的价格单位、数量单位、结算资产和到期条款不能修改；停用后不能退回草稿或重新打开已关闭合约。

后台合约页面集中编辑交易规则、费用、资金费、风险档位、指数来源、做市策略及做市公共设置。草稿不可见；PRE_TRADING 上线展示；TRADING 开启交易；HALT 保留行情并暂停交易。页面展示 Core 应用状态及版本，未应用成功不能视为已完成上线。新增资产 scale 随 `InstrumentEvent` 自动传递，空目录也可以接受后续合约事件；指数加载不再被启动时的 required-ID 列表限制。

`market_maker_strategies` 保存完整策略，`market_maker_business_settings` 保存经校验的产品线公共参数。部署连接和节点身份由环境变量管理。后台保存使用版本校验，调度器每秒加载新增策略和设置；参考盘口变更关闭旧连接，停用策略持续撤销本策略挂单。订单报表每个提交批次读取后台策略账户，保持产品线隔离，停用策略不改变历史账户分类。

新数据库 `init.sql` 仅保留 BTC(604)、ETH(653)、SOL(866)。现有环境升级使用 `deployment/migrations/20261005-admin-contract-settings.sql`，必须先迁移实际运行的做市参数再发布；该结构迁移不删除合约或历史数据。首次未配置的做市处于关闭状态。不得在现有交易数据库执行完整初始化 SQL。

2026-10-05 本地验证：HotSpot JDK 27 下合约校验、版本冲突、快照、Core 同步、指数热加载、做市调度/撤单/参考盘口及 PostgreSQL 持久化测试共 109 项通过。独立 PostgreSQL 18 实例完整执行初始 SQL 和结构迁移，目录仅三条合约；做市设置重载、版本冲突、产品线隔离与订单投影共 7 项数据库测试真实执行。前序 Core 全量回归已通过；本轮测试不代表在线发布、在线合约删除或全量历史账务对账完成。新增记录类保存配置边界，基础设施属性类绑定部署参数，存储类负责数据库原子版本校验，未增加交易热路径抽象。

补充运行验证：使用本轮独立 PostgreSQL 数据库启动已提交版本的做市 JAR，通过真实 HTTP API 验证非法参数返回 400、旧版本保存返回 409、跨产品线请求返回 400，合法保存递增版本且读取一致。整个测试保持做市关闭，未向交易核心下单；本地未启动 Kafka，因此该检查不覆盖 Kafka 合约事件和完整交易端到端链路。

线上只读迁移核对：24 个后台账户的 U 本位普通订单与条件单查询无分页遗漏，挂单仅出现在 604/653/866，对应账户 2/3/4 各 100 单，条件单为空。三个运行策略的当前文件、进程参数及数据库覆盖值已离线合并并通过新设置模型校验，但尚未向线上新配置表写入。完整历史资金流水投影不足，不能将当前余额和风险查询校验等同于全量历史账务对账。

交付边界：合约编辑维护、三合约初始种子、做市数据库配置和用户端热目录代码已提交；线上合约删除、配置迁移、JAR/前端发布、实际合约热上线验收仍未执行。资金费、强平、ADL、保险及价格全局配置仍需继续检查并完成业务配置收口，不能宣称所有业务配置已迁入后台。本轮独立测试数据库、浏览器开发服务、只读隧道及临时构建目录验证后清理；线上服务和资金数据未因上述本地检查而变更。

### 价格时效与用户端错误提示（2026-10-09）

指数/标记价推送采样、杠杆拒绝码及相关验证见 [排查记录](docs/validation/price-leverage-errors-20261009.md)。普通杠杆调整仍要求该保证金模式下没有持仓或挂单；已确认拒绝使用 409 和结构化业务代码，内部传输异常不作为产品提示。新增 Core 返回码需要相关客户端与 Core 配套升级。


### 多线程 Lane 结算版本采用（2026-10-11）

Owner 的改单路由、自成交前撤单候选筛选及 Matcher 前置撤单输入已改为查询现有 `ActiveOrderIndex`，避免为这些路由步骤探测并发写入的 Lane 订单表。索引包装对象的复用池由索引实例持有，不再使用 ThreadLocal。扩展的边界用例在任何 Lane 订单表读取时直接失败，覆盖改单、替换单和自成交候选；178 项回归通过。这只是 L1 的路由部分，余额、持仓准入及全部线程所有权边界仍需继续处理，验证范围见 [路由边界记录](docs/validation/lane-owner-routing-20261011.json)。

控制命令的账户回调现由 `ControlLaneDispatcher` 投递给常驻 `SettlementLaneWorker` 实际执行。Owner 派发后返回，等目标 Lane 的轻量完成标记全部就绪才读取结果或传播业务拒绝；不再由 Owner 内联执行这些回调。178 项基础回归及 31 项账户控制补测通过，覆盖任务重用、并行执行、拒绝后的继续推进及保证金/杠杆/触发单。Owner 业务预检和 Treasury 写入仍需迁移，见 [控制派发记录](docs/validation/control-lane-workers-20261011.json)。

解码读取器和指纹计算临时区由各串行入口/Owner 实例复用，成交、风险、杠杆和身份探针由各 Lane 独享；共享杠杆探针导致的并发清空竞态已修复。211 项扩大回归及 28 项控制/源码边界检查通过，正式无采样轮次观测 40.47 万业务操作/s、资金差与未完成数为 0，但本机仍限频。源码中还保留 3 处执行上下文 ThreadLocal；全链路分配与 Owner 状态归属仍需继续优化，见 [scratch 验证记录](docs/validation/lane-ingress-scratch-20261011.json)。

Treasury 资金事务现复用原语前值数组，资金差计算不再物化资产快照；单/多资产整笔溢出在第一笔写入前拒绝。290 项相关回归执行、20 模块构建通过；局部四组共 1200 万次预热后资金事务分配增量为 0，资金守恒通过。最新无采样单节点观测 42.51 万业务项/s，但限频使容量验收无效；独立整链路 JFR 仍约 1172B/业务项。当前 Treasury 仍由 Owner 写入，生产 SystemLedgerLane 尚未接通，见 [资金原语缓冲验证](docs/validation/treasury-primitives-20261011.json)。

专属 `SystemLedgerLane` 的原账本移交、SPSC 队列、条件唤醒及财务槽位复用边界已独立验证，248项资金/并发回归与50项边界执行通过。固定256槽的跨线程七子账本探针8388608次事务，两线程分配增量均为0。**该Ledger尚未接入生产Runtime**，Owner资金写入退出仍未完成，见 [账本队列基础验证](docs/validation/system-ledger-foundation-20261011.json)。

Matcher 财务回执已验证：账户 Lane 独立结算，账本读取完成位，在原 Treasury 上记手续费并校验资金守恒，Owner 接收原语缓冲后回收事件。队列满时保留财务槽；提前回收、重复收集及旧通知尚未返回时的槽位复用均有检查。304 项相关回归执行通过；复用无成交撤单事实的局部基准约 0.046–0.052B/次、GC 为 0。**生产账本接线仍未完成，不能据此声明主线吞吐提升**，见 [财务回执验证](docs/validation/matcher-ledger-receipt-20261011.json)。

主线采用 `experiments/surprising-ex-async-lanes` 的已修复实现，来源为 `2ca69ba6` 加该目录已有低分配改动及本次单笔、批量撤单读取边界修复。Owner 定序并使用已有活跃订单索引校验归属，Matcher 将确定性成交及撤单事件直接投递到各 Lane 的 SPSC 队列，所属 `SettlementLaneWorker` 应用账户结算，实际完成后才按序回包。未新增账户或订单镜像，符号缓存及 DirectBuffer 读取保留；全局控制操作沿用该实现的所有权交接。本次采用不代表所有控制路径已完成 Owner 资金写入退出，也未证明全链路零堆分配。

JDK 27 的 20 模块干净构建和 167 项资金、撮合、撤单、并发顺序、协议及实时投影契约回归通过；543 个服务编译类、632 个基准编译类与实验修复构建逐字节一致。真实 U 本位永续单节点使用 1 Matcher、4 Account Lane、window256、batch20、128 币对、1385 用户、60 秒预热及 60 秒测量。实验目录同构建两轮为 430144.054 和 416470.407 业务操作/秒，资金差为 0、未完成数为 0，持仓、预留和订单终态核验通过；两轮测量窗口 CPU_Speed_Limit 为 70/72/75，不能按限频比例推算不限频吞吐。主线迁入后复用相同构建独立实测两轮为 382629.744 和 381484.346 业务操作/秒，资金差及未完成数均为 0，持仓、预留和订单终态核验通过；测量窗口 CPU_Speed_Limit 分别为 68/70/72/75 和 66/68/70/72/75/77。采用前主线同参数复测为 179497.542 业务操作/秒，以上限频程度不同的轮次不作为单变量性能归因。采用代码已提交并推送主线 `5a46d779`，详细记录见 [采用验证](docs/validation/experimental-lanes-adoption-20261011.json)。本轮未测试 Aeron Cluster 快照或恢复场景，也未启动 wallet。


普通 AMEND/REPLACE 的账户准入判断已移到所属 Lane，Owner 在这条准备路径只读取现有路由/活动索引及不可变市场输入，完成通知复用分片就绪位图。347 项相关回归执行、20 模块干净构建通过；局部只读改单约152B/次。当前固定单节点 mixed 正式窗口观测30.89万业务项/s，资金差和未完成数为0，但 CPU 限频使容量验收无效；该 mixed 正式窗口不持续发普通改单，不能归因该改动的吞吐收益。整链路独立JFR仍约1171B/业务项，批量改单、触发和生产账本迁移未完成，见 [普通改单 Lane 准入验证](docs/validation/replacement-lane-admission-20261011.json)。


批量 AMEND 的账户准入已复用所属 Lane 的解析结果，现货余额/冻结检查仍在撮合前执行；Owner 保留路由与全局订单 ID 冲突判断，不再在这一准入路径读取账户表。批次 OI 改用已完成的不可变持仓变更，AMEND/CANCEL 取消重复批次准入索引。319次相关回归执行及真实节点资金核对通过。当前永续正式窗口观测42.15万business/s，CPU限频使容量验收无效；整链路仍约1172B/业务项，生产账本及其余 Owner 读写边界未完成。详情见 [批量改单 Lane 准入验证](docs/validation/batch-amend-lane-admission-20261011.json)。

最终账户提交的订单元数据盖章、关闭触发单取消及顺序/全拒单批次响应现由所属 Lane 实际执行；Owner 收到完成通知后发布，不再在这一提交路径读取或写回账户订单表。连续批量改单复用已有批次回执路由。286次相关回归执行、真实现货128场景和永续资金核对通过；永续观测42.50万业务项/s，限频使容量验收无效。局部提交仍有对象分配；生产系统账本及其余 Owner 边界继续迁移。见 [最终账户提交验证](docs/validation/owner-final-account-commit-20261012.json)。


系统账本结算进度已替换为每币对唯一不可变记录：准入读取原始记录，四张可变进度 Map 已移除，资金余额及进度写入仍限定账本线程。双域回滚、线程边界及 U/币本位异步资金费分页共33次必要测试执行通过。本轮按用户要求未运行压测、JMH/JFR、快照或恢复场景；生产 SystemLedgerLane 尚待接线，不能据此宣称 Owner 资金迁移完成或性能改善。见 [系统账本进度边界验证](docs/validation/system-ledger-progress-20261012.json)。

控制资金续步支持原系统账本：资金费、保险调整、强平收付、保险分配、ADL和交割/行权由 `SystemLedgerLane` 读写原Treasury，Owner消费完成回执；账本前值保留到提交/拒绝，账户阶段失败仍可回退保险预留。保险不足保留未执行用户游标，注资后原位重试。76次必要测试执行通过。**生产activation及Matcher/外部查询接线尚未完成，Owner资金退出仍未全部完成**；按要求未运行压测、JMH/JFR或快照恢复。见 [控制账本验证](docs/validation/system-ledger-control-20261012.json)。

Matcher财务接线现由日志提交队首异步推入原Ledger；普通成交返回完整原语流水，顺序批次只交接财库流水并沿用既有账户端点，避免重复记账。流水批次绑定原日志fence，用户回退前值来自Lane已有不可变回执。47次必要测试执行通过，包括带费单笔/流水/撤单批次的资金及Owner账户读取守卫。**外部查询与生产activation仍待接线，完整Owner迁移未完成**；未运行压测、JMH/JFR或快照恢复。见 [Matcher账本接线验证](docs/validation/owner-matcher-ledger-20261012.json)。

财库和保险分配查询已迁到原 `SystemLedgerLane`（2026-10-12）：Owner解析和选取不可变索赔事实，账本读取预算、计算和编码；复用盘口已有异步查询ID与结果槽。财库、保险及进度查询不触发Account Lane停让；查询失败不终止账本，关闭先等待工作线程再清理结果槽。HotSpot JDK27的18个必要用例通过，生产自动启用仍待下一模块；未做压测、JMH/JFR、快照/恢复或wallet。见 [查询验证](docs/validation/owner-ledger-query-20261012.json)。

生产账本自动启用（2026-10-12）：`CoreRuntimeLifecycle.activate` 绑定Owner并启动Account Lane后启动原Treasury的 `SystemLedgerLane`；无需实验开关，也不把资金状态接回Owner。实际命令入口的40个必要测试通过，覆盖批次/触发/手续费、线性和反向永续资金费、强平→保险→ADL、交割/期权结算及账本查询。Owner仍有强平执行判断与身份回收等账户读取边界，完整Shared-Nothing迁移尚未完成；未做压测、JMH/JFR、快照/恢复或wallet。见 [生产账本验证](docs/validation/production-ledger-activation-20261012.json)。

账本连续投递（2026-10-13）：`PendingMatchingRing` 复用原有命令链增加 Owner 独占的账本投递游标。`OrderedCommitCoordinator` 按日志顺序把已派发的独立命令及流水下单批次送入原 `SystemLedgerLane`，不再等第一条响应退休才投递下一条；Ledger 等对应账户完成位后结算，响应及槽位仍按原顺序完成。顺序批次、控制命令和未就绪事实保留屏障，队列满时保留游标并在 Owner 有界调度中重试。72 个必要用例通过；另有 3 个旧用例误含快照恢复，已记录偏差且不计入本轮验收。未压测，不宣称吞吐已恢复，见 [账本流水验证](docs/validation/ledger-pipeline-20261013.json)。


账户控制任务的用户、订单、预留与持仓变更现只写所属 Lane 的 `LaneCommitDelta`，Owner 在完成通知后收集既有回执；关闭持仓触发取消读取已有前值。持仓身份回收使用 Lane 末尾随风险删除返回的布尔证明与身份引用计数，Owner 不回读 Lane 持仓表，不新增账户镜像。58 次必要用例执行通过，覆盖分离删除、风险单独删除、同 Lane 重开及资金费/保险/ADL/交割期权/触发边界，见 [验证记录](docs/validation/owner-position-retirement-20261012.json)。未做压测或快照恢复；强平执行前校验等 Owner 读取仍待迁移。


强平单笔与批次先由所属 Lane 校验持仓和风险，Owner 消费命令槽中的动作结果后才向 Matcher 撤单；订单页及取消 Lane 掩码使用现有 `ActiveOrderIndex` 的不可变引用。过期动作与失效风险不会撤掉有效挂单，已启动账本的同步入口也走原账本回执，订单提交元数据由 Lane 写入。31 次必要用例执行通过，见 [强平迁移记录](docs/validation/owner-liquidation-admission-20261012.json)。本轮没有压测或快照恢复，内部账户查询等 Owner 读取仍待迁移。


Core 内部的用户、订单、客户订单、用户/订单 hash 与挂单分页查询现由实际 Lane 读取和编码，通过既有异步查询槽回包；Owner 仅解码和路由。只有订单 ID 且没有活跃路由的查询并行定位各 Lane 的保留终态订单；只读作用域不触碰未收集的控制变更回执。删除已无生产调用的 Owner 用户/订单读取响应方法。34 次必要回归与末次线程限制的 8 次定向执行通过，见 [账户查询验证](docs/validation/owner-account-query-20261012.json)。外部 Redis 投影沿用现有链路，未运行压测或快照恢复，ADL 候选和其余边界仍待收口。


Owner 编排的订单读取已收口到本次已收集回执与既有活跃索引，显式删除回执优先，取消跨 Lane 订单/预留探测。已删除无调用的持仓查询、触发准备及合约暴露全表扫描，普通下单只消费 Lane 准入。25 次必要用例执行通过，见 [订单读取收口验证](docs/validation/owner-order-receipt-only-20261012.json)。风险状态与 ADL 候选查询仍需迁移；未做压测或快照恢复。

## 已提交命令回执查询（2026-10-19）

realtime 现从已提交 Archive 回放生成普通用户命令回执，与订单投影、水位同事务落库。用户命令查询不再读取 Core 的短期缓存；新增 submission 按原 clientOrderId 查回执，让 Web/client 在响应丢失且没有订单实体的拒单场景也能恢复。未知结果保持待确认，不自动重发。需先应用新增迁移、升级 realtime 并用独立 checkpoint 有限回补，再更新 gateway 和两端。详见 [业务、发布与验证说明](docs/realtime-command-results.md)。174 项后端定向测试无失败或跳过，Web 172 项与 client 148 项通过；本轮未执行完整 FUSED 实栈或原生设备验收。

## 现货撤单恢复（2026-10-19）

网关新增按认证用户、产品和原订单号查询已提交撤单的 `GET /api/v1/gateway/trading/cancellations/{orderId}`；结果来自 realtime SQL 投影，不查询 Core。Web/client 在现货撤单前持久保存原订单身份，响应丢失后保持待确认，只以 GET 恢复；未知结果不当作成功。145 项后端定向测试、Web 181 项和 client 155 项通过，详情与发布顺序见 [回执说明](docs/realtime-command-results.md)。本轮没有启动完整 FUSED 服务栈或原生设备。

## 现货改单（2026-10-19）

Web/client 补齐单笔现货订单行改单和请求身份，响应丢失时只从 realtime 查询原请求；网关新增 `GET /api/v1/gateway/trading/amendments?clientRequestId=...`，不访问 Core。162 项后端定向测试、Web 188 项、client 166 项通过，覆盖部分成交及改单前快照恢复的冻结/费用/资金守恒。先更新 gateway 再更新两端，沿用已提交回执迁移与历史回补；本轮未运行完整 FUSED 栈或真机。详见 [业务及发布说明](docs/realtime-command-results.md)。

### 现货批量撤单（2026-10-19）

Web/client 从 realtime 获取完整固定目标，保存批次键和发送断点后按原生每批 50 笔撤单，展示成功/失败及失败订单。响应丢失时查询 realtime 提交投影；确认当前批次后只继续原计划未发送的批次，不重新读取目标或重发已发送批次。Core 保持交易职责，本次未改在线撮合/账户/账本交易逻辑。发布前需要 SQL 迁移、realtime 更新及必要的历史逐项结果回补；[完整契约和发布顺序](docs/realtime-command-results.md#现货批量撤单与固定目标恢复2026-10-19)，[验证与清理记录](docs/validation/spot-bulk-cancellation-20261019.json)。

后续已完成真实 SPOT FUSED 两端验收：Web 桌面/手机和 Flutter 在真实做市、HTTP/WS 下验证分批撤单、暂停 realtime 后 202 未决、断响应/刷新/重建状态恢复以及新增订单保留。最终用户和做市冻结清零，逐资产资金守恒差额为 0。修复完整建库时新增回执列缺少业务说明触发的 schema gate；[复现方式](docs/realtime-command-results.md#现货-fused-两端实链路验收2026-10-19-后续)，[验证记录](docs/validation/spot-fused-clients-20261019.json)。原生设备安装、其他产品和其余批量写入仍待继续。

## U 本位交割结算中减仓与两端恢复（2026-10-19）

`InstrumentRequestService.latest/list` 保留 `LINEAR_DELIVERY + SETTLING` 的公开合约元数据，供用户定位已有持仓、直接减仓与撤单；DRAFT/CLOSED 和其他产品的可见规则保持原有约束。普通交易仍由 `OrderValidator`/Core 校验：结算中仅接受 reduceOnly，HALT 不允许下单。此处不改变结算调度或资金模型。

Web/client 的单笔下单和撤单在 POST 前持久保存原身份；响应丢失后查询 realtime 提交/撤单回执，未知结果保持待确认，刷新或重启不重发原写入。行情、账户、订单及回执仍走 realtime；验收中的 Core 查询仅用于独立对账。先更新 price（SETTLING 指数源）与 gateway（合约可见性和 realtime 杠杆读取），再发布两端，沿用已完成的回执迁移与导出；[业务和复现说明](docs/realtime-command-results.md#u-本位交割结算中减仓与恢复2026-10-19)。

### U 本位交割到期与现金记录（2026-10-20）

到期自动进入 SETTLING，Core 提交排空普通委托及条件单并释放预占，当前版本 ORDER 确认后仍需双人审批确认结算价。Core 负责结算，realtime 的独立已提交回放负责交割现金账本投影；Gateway/Web/client 查询该投影，已结束合约可按明确 ID 保留查询页面。有限历史回补、幂等与异常处理见 [realtime 说明](surprising-realtime/README.md#交割现金流水与有限回补2026-10-20)。这次本地实际产品线限定 LINEAR_DELIVERY；其他产品线的共享测试不代表已完成其真实到期流程或原生设备验收。

### 币本位交割 Web/client 链路（2026-10-20）

INVERSE_DELIVERY 的 SETTLING 合约继续提供目录、指数源与 realtime 杠杆只读查询，只允许直接减仓；CLOSED 可按 ID 查询历史元数据，默认目录仍排除。Web/client 单笔下单及撤单先持久化意图，响应不明时查询原回执，不重复发送交易。到期清理继续覆盖普通单、TP/SL 与做市报价，交割现金流水由 realtime 的已提交回放写入既有产品账本。币本位的余额、保证金、手续费与盈亏使用 BTC 单位，固定报价面值不能套用线性乘价公式。

本轮只实际启动 INVERSE_DELIVERY 单节点 FUSED；真实用户和做市账户完成成交、减仓、响应丢失恢复及自动到期，核对资金守恒与 BTC 流水。Web 390/1440、Flutter 主机 320 深浅色检查见 docs/validation/inverse-delivery-trading-20261020.json。该记录不代表其他产品线、批量故障、强平/ADL 或原生真机已完成验收。


### 期权交易、到期行权与两端恢复（2026-10-20）

本轮仅启动 OPTION 单节点 FUSED。期权买方全额支付权利金，卖方保证金仍由既有 Core 风险模型核定。`ExpiringContractSettlementFanoutService` 把事件的标的结算价格整数按不可变期权快照的 `priceTickUnits` 精确换成 Core 档位；缺少精度或未对齐直接拒绝。`InstrumentService` 用相同的期权档位和乘数计算事件现金，不能混用标的合约的档位。Core 继续独立计算内在价值和结算资金，本轮没有修改在线 Core 交易状态模型。

realtime 的 `CommittedOrderProjectionRepository.persistDelivery` 对 OPTION 写入现有产品账本的 `OPTION_EXERCISE/期权行权`；交割仍为 `DELIVERY_SETTLEMENT`。金额、余额、自然身份、整页事务及提交检查点规则不变。旧数据库先停止相关 exporter，执行 [分类迁移](deployment/migrations/20261020-option-exercise-ledger.sql)，再发布 gateway/realtime/Web/client；迁移只改分类，不重放交易、不改金额，身份冲突整笔回滚。新建数据库无需修复旧分类。

Web/client 的期权单笔下单及撤单先保存原请求身份，丢失响应后只查询 realtime 原回执；期权只允许未到期 TRADING 下单。到期自动排空普通单、TP/SL 和做市报价，经现有双人审批确认价格后行权；CLOSED 元数据及现金流水仍可查。两端按真实币种精度展示全额权利金和手续费，移除杠杆入口及不可信 Delta。后端尚未提供 Greeks 定价接口，因此两端明确显示暂无可信报价；不能把这一轮当成完整期权定价功能已经完成。

实际覆盖 CALL/PUT 各价内、平价、价外六组合约：每组两张，权利金 10 USDT、买方手续费 0.005 USDT、卖方手续费 0.002 USDT；价内行权 4 USDT，其余为零。仓位、冻结和预占清零，用户/做市/财库资金差额为零。两端验证真实丢回包恢复与 CLOSED 流水，小屏修复摘要重叠。环境、命令、未测范围和清理状态见 [本轮记录](docs/validation/option-trading-20261020.json)。


### 期权行情与参考估值、公开手续费版本（2026-10-20 后续）

两端已接入 realtime 的期权参考接口 `/api/v1/gateway/candlestick/options/quote`，展示真实权利金、标的指数、同到期远期价及零贴现 Black-76 参考 IV/远期 Delta/Gamma/Vega/每日 Theta。模型与每1单位标的的口径明确显示，缺失/过期/越界不造数；它不替代账户风险或结算。HALT 中有新鲜源数据的未到期合约仍可查询，下单限制保持独立。

期权链现在可直接切换 CALL/PUT，按标的身份、精确到期时刻及币种分组，使用整数行权价并保留同价多合约。Web 补齐320px已登录页头、语言菜单与主题交互。另修复公开有效手续费的版本名：返回 changeId，与公开合约精度版本一致，client 不再依赖被网关删除的 instrumentChangeId；Web 现货费用版本校验同步使用公开 changeId，缺失或旧字段仍拒绝。未放开脱敏、未添加猜测费率。

发布需配套 realtime、gateway 与两端；本轮没有 SQL 或 Core 资金/撮合/协议变更。参考模型仅为明确的零贴现欧式现金模型，不提供完整利率曲线或组合 Greeks；原生设备安装及其他产品本轮实栈不在验收范围。[验证与清理记录](docs/validation/option-market-reference-20261020.json)。


### 期权原子 TP/SL 与原请求恢复（2026-10-20 后续）

client 期权“高级委托 → 条件单”补齐 TP/SL 双腿 OCO，支持选择标记价/最新成交价/指数价，以及各腿触发后市价或限价执行。价格按真实资产 scale/tick 换算，单条条件单也使用永久 instrumentId。OCO 先保存原身份，再一次提交两腿；丢回包、刷新或重建只查询原 realtime 已提交回执，不自动重发。停牌、关闭、方向不符及超出持仓数量均拦截。client 活动条件单展示真实触发源、OCO 标记和执行方式。

Web 同步保存 OPTION OCO 原身份并复用结果确认入口。TP/SL 票据不显示普通开仓权利金，费用标记为“触发成交时计算”。后端新增只读 oco-submission 查询，复用现有回执表及 realtime exporter，不访问在线 Core。本轮无新 SQL 或 Core 交易/资金模型变更，发布需配套 gateway 与两端，并确保此前回执迁移和导出已就绪。

实际仅运行 OPTION 单节点 FUSED：Web 双腿一次提交、丢响应刷新恢复后撤销；client 双腿一次提交、暂停 realtime 后未知、恢复并重建读取原结果，止盈腿触发限价平仓且另一腿撤销。当前及前一次隔离账户持仓/冻结归零、资金差额0。320px 深浅色及停牌费用文案验证通过；其他产品、独立条件单恢复、原生设备和生产发布仍需继续。[验证与清理记录](docs/validation/option-protection-20261020.json)。

期权独立条件单与单笔撤销的原请求查询沿用 realtime 已提交回执，不查询在线 Core；两端持久恢复与配套发布见 [条件单原请求恢复](docs/trigger-command-recovery.md)。本轮不新增 SQL。

U 本位交割的独立条件单/OCO 恢复与 SETTLING 直接减仓边界，参见 [条件单原请求恢复](docs/trigger-command-recovery.md)；实栈用例在同一仓位上交接交易与结算两个阶段。


币本位交割的条件单/OCO 持久恢复复用既有 realtime 回执入口；BTC 资金、报价币触发价和 SETTLING 直接减仓分别验证。
realtime 同步修正币本位交割基础币成交量、结算/关闭后真实成交接收，以及冷启动时当前大周期查询；Core 撮合与资金模型不变。见 [条件单原请求恢复](docs/trigger-command-recovery.md)、[行情口径与投影重建](docs/inverse-delivery-market-data.md) 及 [验证记录](docs/validation/inverse-delivery-protection-20261020.json)。


现货用户成交记录现通过 authenticated trading 路由读取 realtime 已提交成交投影，返回本人 Maker/Taker 执行，不暴露对手身份；两端成交记录、精度和分页随之对齐。查询契约及发布顺序见 [用户成交记录](docs/private-trade-fills.md)。


### 现货历史委托分页、筛选与展示（2026-10-20）

历史委托继续从 realtime 已提交的订单投影查询。状态 FILLED/CANCELED/REJECTED 在 SQL 分页之前过滤；按更新时间及订单号倒序翻页，响应 sort 修正为 updatedAt.desc，起止日期仍按创建时间且含两端。Web 两处入口保留游标与原账户/产品/筛选，桌面和手机共用只读历史展示；client 固定已应用查询，编辑草稿不会把新条件与旧游标混页。两端保留大订单号、实际 tick/step/资产精度和小数 tick 均价，展示成交量、剩余量及原累计手续费；client 对不在交易目录的历史合约按永久身份取资料。

需配套发布 gateway、Web、client；无新表、迁移、topic 或 Core 交易状态变化。实栈一次只运行 SPOT、FUSED，不启动 wallet。隔离脚本分别为 Web 的 scripts/spot-order-history-fused-live.mjs、client 的 test/spot_order_history_fused_live_test.dart，显式传 SPOT_HISTORY_LIVE_FIXTURE（/tmp/surprising-spot-fused-history- 下、Maven SIX_LINE_LIVE_USER_HISTORY=true 生成）；Web 可用 SPOT_HISTORY_PLAYWRIGHT_MODULE / SPOT_HISTORY_CHROMIUM 指定已有浏览器。脚本只把真实 GET 的 limit 降为 1 以验证分页，不生成金融响应或提交交易。实测与清理见 docs/validation/spot-order-history-20261020.json。


### 现货当前委托的精确价格与价值（2026-10-20）

Web `TradingAccountTables` 与 client `OrderRow` 的现货价格/均价显示复用整数十进制换算，保留大于 JS 安全整数的原 tick 与小数 tick 均价。现货委托价值按 priceTicks × quantitySteps × notionalMultiplierUnits，成交价值按原 executedValueTicks × notionalMultiplierUnits，以真实报价币 scale 展示。原累计手续费直接显示，不使用平均价或最新费率反算；市价单不猜限价价值，缺少乘数/执行累计值时保持缺失。现货明确显示“计价币种”和 Post-only，隐藏不适用的全仓/逐仓、持仓方向与 reduce-only 文案。Web 小屏现货操作列随表格滚动，避免固定按钮遮住长价格和金额。

本轮为展示层修复，无 API、realtime、Core 交易或资金模型变化，无迁移/topic/新服务；未启动后端、wallet 或其他产品实栈。Web 只读展示验证脚本 scripts/spot-order-details-qa.mjs 使用显式 SPOT_DETAILS_QA_ROOT（/tmp/surprising-spot-details- 前缀、owner.json 标识本轮 QA）及可选 SPOT_DETAILS_QA_BASE、SPOT_DETAILS_PLAYWRIGHT_MODULE、SPOT_DETAILS_CHROMIUM。它挂载真正生产组件及显式整数测试数据，不拦截金融 API 响应；不代表本轮真实 FUSED 成交/资金对账。证据与清理见 docs/validation/spot-current-order-details-20261020.json。


### 现货交易面板默认视图与账户隔离（2026-10-20）

client 现货默认打开当前委托，隐藏持仓入口和保证金风险摘要，保留止盈止损/策略、历史委托与成交记录；同一面板切换账户或产品后重新选择适用的默认页签。两端现货登录提示改为账户与委托。

Web 最近订单更新曾在直接切换已登录账户时保留前一账户缓存。现按账户、登录状态、产品及永久 instrumentId 重新挂载面板，清除页签、筛选、订单更新与风险局部状态；仅收录根事件 userId 属于当前账户、channel=orders 且产品匹配的更新，跳过无订单号的空事件。现货不再展示持仓页签，衍生品入口保留。

本轮只调试现货界面与状态隔离，无 API、realtime 或 Core 逻辑改动，无迁移/topic/服务新增；查询继续使用既有 realtime 路径。未启动后端、wallet 或其他产品实栈，不能据此宣称新的 FUSED 成交/资金对账通过。scripts/spot-order-details-qa.mjs 新增账户、退出登录、交易对往返、错误产品事件与页签切换回归，沿用既有 SPOT_DETAILS_QA_ROOT / SPOT_DETAILS_QA_BASE / SPOT_DETAILS_PLAYWRIGHT_MODULE / SPOT_DETAILS_CHROMIUM 参数。受控真实组件验证、原生 widget 覆盖与资源清理见 [验证记录](docs/validation/spot-trading-panel-scope-20261020.json)。
