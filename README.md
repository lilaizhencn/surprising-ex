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
