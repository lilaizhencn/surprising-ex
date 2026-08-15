# 生产级交易性能测试方案

## 1. 目标与边界

本方案验证现货、永续、交割、期权四条产品线在真实用户 API 流量下的吞吐、延迟、稳定性、资源使用、故障恢复和资金正确性。

每次测试只运行一条产品线。做市进程保持运行，测试用户通过 Gateway API 进入交易链路，测试资金只通过账户管理接口注入。PostgreSQL 不参与订单、撮合和账户热路径的业务判断，只用于异步投影、审计、恢复和最终对账。

性能结论必须同时满足：

- 吞吐达到本次测试目标值，并且达到部署规格对应的生产目标。
- p50、p95、p99、p99.9 和最大延迟均有原始证据。
- Kafka lag、Owner mailbox、Outbox 和本地 WAL 不持续增长，最终积压为零。
- 订单、成交、余额、冻结、持仓、手续费、资金费、强平、交割和行权全部对账通过。
- JVM、GC、CPU、内存、磁盘和网络资源没有超出对应环境的限制。
- 节点重启、重复消息、Kafka 重平衡和投影恢复后，状态与无故障基线一致。

“脚本执行完成”不等于性能通过。只有自动门禁返回 0，才能把该轮标记为 PASS。

## 2. 环境自适应原则

测试脚本启动前读取操作系统、CPU、物理内存、JDK、容器限制、Kafka 分区、PostgreSQL 可用性和 Actuator 暴露情况，生成不可修改的 `environment-manifest`。

### 2.1 环境档位

| 档位 | 自动条件 | 目的 | 默认行为 |
|---|---|---|---|
| `local-low` | CPU ≤4 或内存 ≤8GiB | 老旧笔记本、开发机 | 单产品线、单 symbol 功能 smoke，最小 provider 集合，低并发，小堆 |
| `local-standard` | CPU 5-8 或内存 8-32GiB | 本地完整链路 | 单产品线、最多 20 symbol，中等并发，单机 Owner shard |
| `cloud-capacity` | CPU ≥16 且内存 ≥32GiB | 云上容量验证 | 20 symbol、1000-5000 用户、可调 Kafka/Owner/Risk 并发 |
| `cloud-production` | 显式指定 | 生产规格验收 | 使用部署规格、专用节点、完整资源和故障矩阵 |

自动档位只负责选择安全上限，不能把低配置机器伪装成容量环境。低配置机器只能通过功能 smoke 和小规模链路门禁，不能据此得出生产容量结论。

### 2.2 资源预算

脚本必须保留操作系统余量，不允许把全部内存分配给 JVM：

- `local-low`：JVM 总堆预算不超过物理内存的 45%，外围 provider 默认 256MiB，matching/account/risk/liquidation 默认 384MiB。
- `local-standard`：JVM 总堆预算不超过物理内存的 55%，核心 provider 默认 768-2048MiB。
- `cloud-capacity`：JVM 堆按服务规格独立设置，默认 `Xms=Xmx`，不超过节点内存的 60%。
- `cloud-production`：使用部署文档中明确的节点级 JVM 堆，不使用本机自动值覆盖云上配置。

脚本支持显式覆盖：

```text
TEST_PROFILE=local-low|local-standard|cloud-capacity|cloud-production
TEST_MAX_JVM_HEAP_MB=<integer>
TEST_JVM_GC=auto|g1|zgc|parallel
TEST_SERVICES="instrument index-price mark-price matching account order trigger gateway websocket"
TEST_MAX_PROVIDER_PROCESSES=<integer>
```

显式配置优先于自动探测，但必须写入 manifest，并在报告中标记为 override。

### 2.3 只启动必要实例

`auto` 服务集合按产品线和场景生成：

| 场景 | 必需实例 |
|---|---|
| 现货功能 smoke | instrument、index-price、mark-price、matching、account、order、trigger、gateway、websocket、market-maker |
| 永续/交割/期权功能 smoke | instrument、index-price、mark-price、matching、account、risk、liquidation、funding、insurance、adl、order、trigger、gateway、websocket、market-maker |
| 撮合核心基准 | matching 及其依赖的 instrument 快照，不启动 Gateway、WebSocket、wallet |
| 账户 Owner/WAL 测试 | account，不启动 wallet 和无关产品线 |
| WebSocket 测试 | websocket、gateway 路由所需服务、一个做市实例 |
| 风控/强平测试 | index-price、mark-price、matching、account、risk、liquidation、funding、insurance、adl、gateway、market-maker |

低配置环境禁止同时启动四条产品线，也不启动 candlestick、index-price 等非当前场景必需实例。云上测试可以通过 `TEST_SERVICES` 恢复完整服务集合。

## 3. 统一 SLO

当前生产目标以 `docs/linear-perpetual-aws-production-deployment.md` 为基准，部署到不同规格时必须按规格生成对应配置，不能临时修改阈值。

| 指标 |    默认生产门槛 |
|---|----------------:|
| 下单/撤单/改单总吞吐 |    300000 ops/s |
| 成交吞吐 | 100000 trades/s |
| 下单受理 p99 |           ≤80ms |
| 受理到 match result p99 |          ≤150ms |
| trade 到余额/持仓可见 p99 |          ≤300ms |
| 正常 Kafka lag |             <1s |
| 故障恢复后 Kafka lag 清零 |           ≤300s |
| 单倍负载 CPU |            <55% |
| 双倍突发 CPU |            <75% |
| GC pause p99 |           <20ms |
| 单次 GC pause |          <100ms |
| 最终 Outbox pending |               0 |
| 资金差异、重复扣款、重复结算 |               0 |
| 1 倍负载稳定时间 |          ≥60min |
| 2 倍负载突发时间 |          ≥15min |

本地低配档位只使用缩放后的目标，报告必须同时写明 `scale_factor`，不得把本地通过等同于生产通过。

## 4. 流量和容量矩阵

### 4.1 用户和 symbol 规模

| Case | 用户 | Symbol | 目的 |
|---|---:|---:|---|
| `smoke` | 100 | 1 | 功能和资金正确性 |
| `baseline` | 1000 | 20 | 单机基线 |
| `capacity` | 5000 | 20 | 容量和结算吞吐 |
| `hot1` | 5000 | 20 | 80% 流量集中 1 个 symbol |
| `hot3` | 5000 | 20 | 80% 流量集中 3 个 symbol |
| `burst` | 5000 | 20 | 2 倍目标 TPS，15 分钟 |
| `soak` | 1000+ | 20 | 1 倍目标 TPS，60 分钟以上 |
| `liquidation` | 5000 | 20 | 同时触发风险和强平 |

低配本机默认只运行 `smoke`；`baseline` 需要 `local-standard`；`capacity`、`burst`、`soak` 和 `liquidation` 只能在 `cloud-capacity` 或 `cloud-production` 执行。

### 4.2 交易行为比例

默认混合流量：

- 50% 新订单。
- 20% 撤单。
- 10% 改单。
- 10% 可成交 IOC/FOK。
- 10% 查询、WebSocket 和状态确认。

撮合专项另测 100% 可成交订单、部分成交、深订单簿、跨多个 symbol 和单热点 symbol。

## 5. 各链路指标定义

### 5.1 订单链路

- Gateway 请求 TPS、HTTP 成功率、超时率和客户端观测延迟。
- Order Owner mailbox 入队、执行、拒绝和最大深度。
- ACCEPTED、CANCELED、REJECTED 终态比例。
- `clientOrderId` 重复提交的幂等命中率。

### 5.2 撮合链路

- matching command TPS。
- match result TPS。
- trade event TPS。
- 每个 symbol 的 TPS、成交占比和撮合延迟。
- 订单簿深度、活跃订单数和恢复耗时。

### 5.3 账户与结算链路

- account command 接收和应用 TPS。
- 余额、冻结、解冻、持仓和手续费更新 TPS。
- 双边成交完成 TPS。
- 成交到余额/持仓可见 p50/p95/p99/p99.9/max。
- 用户 Owner mailbox、WAL append 和 checkpoint 耗时。

### 5.4 风控、强平和爆仓链路

必须分别报告：

```text
风险快照更新
 -> 风险扫描
 -> liquidation candidate 发现
 -> 强平单提交
 -> 强平单撮合
 -> 账户结算
 -> 持仓归零
 -> 强平费入保险基金
 -> ADL 状态完成
```

不能用“爆仓最终完成数量”代替各阶段 TPS 和阶段延迟。

## 6. JVM 垃圾收集器矩阵

JDK 25 下测试：

| 服务 | 候选 GC |
|---|---|
| matching | G1、Generational ZGC |
| order/account/risk | G1、Generational ZGC |
| 批量资金费、交割、行权 | G1、ParallelGC |
| Gateway/WebSocket | G1、Generational ZGC |

每个候选配置使用相同机器、堆大小、流量、数据和预热时间，至少重复 3 次。

替换默认 GC 的条件：

- 关键链路 p99 改善至少 20%。
- 吞吐不低于 G1 的 95%。
- GC pause p99 <20ms，最大 <100ms。
- 无 Full GC、OOM、长停顿和请求超时异常。
- CPU 增长不超过 10%。
- 最终资金、订单、成交和结算结果完全一致。

## 7. Web 容器、线程池和并发矩阵

如果服务实际使用 Tomcat，测试：

- `maxThreads`：100、200、400。
- `minSpareThreads`：20、50、100。
- `acceptCount`：1000、5000。
- `maxConnections`：目标并发的 2 倍、4 倍。

同时采集 active threads、queue、rejected、HTTP p99、CPU、连接数和 5xx。

`run-container-threadpool-matrix.sh` 默认强制 `THREADPOOL_REQUIRE_TOMCAT=true`；每个实际 case 都必须提供 `THREADPOOL_CONFIG_RESULT_FILE`，证明四项配置已绑定并与请求值一致。缺少绑定证据只能失败，不能以普通性能指标代替。

Kafka、Owner、Outbox 和风险线程池也必须矩阵化：

- Kafka listener concurrency：1、4、8、16、32。
- `max.poll.records`：100、500、1000。
- Owner shards：1、4、8、16。
- mailbox：16K、65K、262K。
- Outbox batch：100、500、1000。
- Outbox max in-flight：32、64、128。

一次只改变一个配置变量。

## 8. 四条产品线场景

### 现货

买入、卖出、双资产冻结、成交扣减、手续费、撤单解冻、资产守恒和高并发买卖混合。

### 永续

开仓、平仓、资金费、标记价、风险快照、强平、保险基金、ADL 和 5000 用户同时爆仓。

### 交割

到期前交易、到期撤单、批量交割、持仓归零、重复到期事件和交割期间节点重启。

### 期权

`CALL/PUT × ITM/ATM/OTM × 买方/卖方`，覆盖权利金、行权、失效、重复行权和重复结算。

## 9. 故障恢复矩阵

每条产品线至少验证：

1. Order Provider 在订单接受后重启。
2. Matching Provider 在撮合结果发布前重启。
3. Account Provider 在单边或双边结算后重启。
4. Risk、Liquidation、Funding Provider 在批处理中重启。
5. Edge/WebSocket 重启和客户端重连。
6. Kafka consumer rebalance。
7. 重复消息、Outbox 重试和 WAL replay。
8. 异步数据库投影短暂不可用。

通过条件：RPO=0、重复业务事实为 0、最终 lag 和 Outbox 为 0、恢复时间 ≤300 秒、资金绝对零差异。

恢复矩阵的真实执行必须提供 `RECOVERY_RESULT_FILE`，至少包含
`recovery_rto_ms<=300000`、`rpo_events=0`、`kafka_final_lag=0`、
`outbox_final_pending=0`、`funds_difference=0`、`duplicate_facts=0`、
`state_equivalent=PASS` 和 `wal_replay=PASS`；缺失文件不能标记恢复 PASS。

## 10. 脚本职责

现有脚本作为业务执行器：

- `product-line-api-flow-smoke.sh`：真实 API 交易流程。
- `product-line-funds-reconcile.sh`：逐用户、逐资产、逐产品线资金核对。
- `product-line-websocket-smoke.mjs`：公共/私有 WebSocket 隔离和事件验证。
- `matching-engine-benchmark.sh`：内存核心和 exchange-core 基准，不代表全链路性能；输出只能作为微基线，生产容量仍必须使用单产品线真实 Cluster、Kafka、投影和资金对账门禁。

计划调整或新增：

- `test-environment-profile.sh`：探测 CPU、内存、JDK、容器限制并生成 profile。
- `production-resource-monitor.sh`：采集进程、Actuator、JVM、GC、CPU、内存和磁盘指标。
- `production-performance-gate.sh`：按 SLO 对原始结果做自动 PASS/FAIL。
- `run-product-line-performance-matrix.sh`：四条产品线统一矩阵，替代只支持永续的矩阵入口。
- `run-jvm-gc-matrix.sh`：GC 对比。
- `run-container-threadpool-matrix.sh`：Web 容器和线程池对比。
- `run-product-line-soak.sh`：长稳和积压增长测试。
- `run-product-line-recovery-matrix.sh`：服务重启、重平衡和 replay 测试。
- `production-test-contract.sh`：脚本语法、参数、证据和门禁契约测试。
- `run-security-test-suite.sh`：独立安全测试编排；默认干跑，主动模式需要授权和目标 allowlist。
- `security-concurrency-race.sh`：相同幂等键、撤单竞态和账户操作并发安全测试；最终必须接资金对账。

所有脚本都必须支持 `DRY_RUN=true`，默认只打印计划，不启动服务。只有显式 `EXECUTE=true` 才执行真实测试。

## 11. 证据目录

每轮测试目录必须包括：

```text
manifest.env
environment-manifest.json
command-line.txt
provider-config/
api.log
gateway.log
kafka-lag.tsv
outbox.tsv
latency.tsv
resource.tsv
gc.log
jfr/
funds-reconcile.txt
websocket-evidence.json
summary.json
summary.md
```

报告必须写入 Git SHA、JDK、GC、堆、机器规格、环境档位、服务列表、Kafka 分区、目标 TPS 和所有 SLO。

## 12. 执行顺序

```text
静态边界检查
 -> 脚本契约测试
 -> Maven 模块测试
 -> 单产品线功能 smoke
 -> 资金核对
 -> 单 symbol 基线
 -> 多 symbol 矩阵
 -> GC/线程池配置矩阵
 -> 长稳测试
 -> 故障恢复测试
 -> 四产品线汇总和人工 Go/No-Go
```

当前阶段只完成文档和脚本设计，不执行任何压测或故障测试。后续脚本实现完成后，必须先在本机自动识别为 `local-low` 或 `local-standard`，再由云上显式切换到 `cloud-capacity` 或 `cloud-production`。
