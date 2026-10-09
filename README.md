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
