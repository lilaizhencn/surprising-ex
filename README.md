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

   Set the PostgreSQL password and a unique `GATEWAY_JWT_SECRET`. Review the database and broker addresses, heap limits, and `MM_INSTRUMENT_ID` in the environment file. The launcher applies [`init.sql`](init.sql) **only when the `instruments` table is absent**. Populate the desired instrument catalog and any test users and funds through their normal application flows before relying on maker trading.

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
