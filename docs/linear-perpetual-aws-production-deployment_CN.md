# LINEAR_PERPETUAL AWS 生产部署基线

本文只覆盖一条 `LINEAR_PERPETUAL`（USDT 本位永续）产品线，不使用 Kubernetes。应用运行在
AWS EC2，PostgreSQL、Kafka、Redis 兼容服务直接购买 AWS 托管产品。

## 1. 首发结论

- 区域固定为新加坡 `ap-southeast-1`，使用 3 个可用区。
- 除撮合外，每个 Provider 固定运行 2 个节点，分布在不同可用区。
- 撮合首发只运行 1 个节点，由 EC2 Auto Scaling Group 自动拉起替代实例。
- EC2 全部使用 On-Demand `m8i`，不使用 Spot、突发型 `t` 实例或 Flex 实例。
- 数据库使用 Amazon RDS for PostgreSQL Multi-AZ DB Cluster。
- Kafka 使用 Amazon MSK Provisioned Standard，3 Broker、3 可用区。
- Redis 使用 Amazon ElastiCache for Valkey 9.0，同步持久化、1 分片、2 副本。
- PostgreSQL 是业务事实源；Kafka 是可靠事件日志；Valkey 是持仓、活动订单、触发单和风险索引的
  实时读模型。三者都不能配置自动淘汰或静默丢数据。

规格对应当前项目的
[单产品线启动脚本](../scripts/start-product-line-providers.sh)、
[Topic 创建脚本](../scripts/create-topics.sh)、
[Topic 命名](../surprising-product-api/src/main/java/com/surprising/product/api/ProductTopicNames.java) 和
[撮合引擎](../surprising-trading/surprising-matching-provider/src/main/java/com/surprising/trading/matching/service/ExchangeCoreEngine.java)。

## 2. 应用节点规格

每个进程独占一台 EC2。EBS 均为 KMS 加密的 `gp3`；除 K 线外，磁盘只保存程序、日志、JFR 和
故障转储，业务事实不能只保存在 EC2 本地盘。

| Provider | 节点数 | 每节点 EC2 | vCPU / 内存 | JVM 堆 | 数据盘 | 每节点 DB 池 | 选择原因 |
|---|---:|---|---|---|---|---:|---|
| `instrument-provider` | 2 | `m8i.large` | 2 / 8 GiB | 2 GiB | 100 GiB，3000 IOPS，125 MiB/s | 10 | 低频配置和生命周期任务，双节点用于可用性 |
| `candlestick-provider` | 2 | `m8i.xlarge` | 4 / 16 GiB | 4 GiB | 500 GiB，6000 IOPS，250 MiB/s | 20 | Kafka Streams 与 RocksDB 需要本地 I/O 和页缓存 |
| `price-provider` | 2 | `m8i.xlarge` | 4 / 16 GiB | 4 GiB | 100 GiB，3000 IOPS，125 MiB/s | 10 | 合并 index/mark price；DB lease 保证同 symbol 单发布者 |
| `trading-entry-provider` | 2 | `m8i.2xlarge` | 8 / 32 GiB | 8 GiB | 200 GiB，3000 IOPS，125 MiB/s | 32 | 下单、撤单、触发单和 outbox 是入口热点 |
| `matching-provider` | **1** | `m8i.4xlarge` | 16 / 64 GiB | 16 GiB | 200 GiB，3000 IOPS，125 MiB/s | 20 | exchange-core、订单簿和恢复需要独占 CPU 与充足内存 |
| `account-provider` | 2 | `m8i.2xlarge` | 8 / 32 GiB | 8 GiB | 200 GiB，3000 IOPS，125 MiB/s | 24 | 资金写入按用户串行，数据库和 Kafka 都是热点 |
| `margin-ops-provider` | 2 | `m8i.2xlarge` | 8 / 32 GiB | 8 GiB | 200 GiB，3000 IOPS，125 MiB/s | 20 | 合并 risk、liquidation、funding、insurance、ADL |
| `gateway-provider` | 2 | `m8i.xlarge` | 4 / 16 GiB | 4 GiB | 100 GiB，3000 IOPS，125 MiB/s | 10 | 无状态 REST 入口，双节点跨可用区 |
| `websocket-provider` | 2 | `m8i.2xlarge` | 8 / 32 GiB | 8 GiB | 200 GiB，3000 IOPS，125 MiB/s | 0 | 长连接、订阅和发送队列主要消耗内存与网络 |
| `market-maker-provider` | 2 | `m8i.xlarge` | 4 / 16 GiB | 4 GiB | 100 GiB，3000 IOPS，125 MiB/s | 10 | 两节点运行，DB lease 保证一个策略/symbol 只有一个 owner |

### 为什么撮合首发为单节点

当前代码支持把不同 symbol 的 Kafka partition 分给多个撮合进程，但这不是同一订单簿的主备复制。
partition 接管后，进程会退出并由 systemd/ASG 重启，再从 PostgreSQL 恢复未完成订单。因此两个撮合
节点可以分摊不同 symbol，却不能提供无感主备。

首发采用一个撮合进程，避免把“分片扩容”误认为“高可用”。要求：

- ASG 设置 `min=1, desired=1, max=1`，健康检查失败后自动替换；
- systemd 设置 `Restart=always` 和 `RestartSec=3`；
- 实测 100 万活动订单恢复时间，验收目标不超过 5 分钟；
- 撮合不可用时可以让命令留在 Kafka，但网关应暂停新增下单，避免积压失控；
- 真正的撮合高可用需要增加 per-symbol active/standby、确定性日志和快照恢复，不能只多买一台 EC2。

其余 Provider 使用各自独立 ASG，稳定运行 `min=2, desired=2`，`max=3` 只用于无损滚动发布，
两个常驻实例放在不同可用区。

## 3. JVM 基线

全部使用 Amazon Corretto 21。除堆大小外，普通 Provider 使用同一组参数：

```text
-XX:+UseG1GC
-Xms<表中堆大小> -Xmx<表中堆大小>
-XX:MaxGCPauseMillis=100
-XX:+AlwaysPreTouch
-XX:+ParallelRefProcEnabled
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/data/dump
-XX:+ExitOnOutOfMemoryError
-Xlog:gc*,safepoint:file=/data/logs/gc.log:time,uptime,level,tags:filecount=10,filesize=100M
-XX:StartFlightRecording=settings=default,disk=true,maxage=24h,maxsize=1g,filename=/data/jfr/service.jfr
```

撮合把 `MaxGCPauseMillis` 改为 `50`，并保留项目
[deployment.md](deployment.md) 中 exchange-core/OpenHFT 所需的 `--add-opens` 和 `--add-exports`。

选择 G1 的原因：

- JDK 21 默认和成熟度高，适合当前大多数 Spring Boot 服务；
- `Xms=Xmx` 加 `AlwaysPreTouch` 避免交易高峰临时扩堆和缺页抖动；
- 堆只占主机内存的 25%～50%，给 Netty、线程栈、exchange-core、RocksDB 和 Linux 页缓存留空间；
- `MaxGCPauseMillis` 是调优目标，不是延迟保证，最终以压测的 GC 日志和端到端延迟为准。

只有撮合允许 A/B 测试 Generational ZGC：

```text
-XX:+UseZGC -XX:+ZGenerational
```

当 G1 的 GC pause P99 大于 20 ms 或单次超过 100 ms，且 CPU 仍低于 60% 时才测试 ZGC。只有 ZGC
让撮合 P99 至少改善 20%、吞吐不低于 G1 的 95%、CPU 仍低于 70%，才替换 G1。其他服务保持 G1。

## 4. AWS 托管中间件

| 组件 | 确定配置 | 节点/可用区 | 容量 | 为什么这样选 |
|---|---|---|---|---|
| PostgreSQL | RDS PostgreSQL **17.10** Multi-AZ DB Cluster，`db.r6gd.4xlarge` | 1 writer + 2 readable standby，3 AZ | `io2` 4 TiB，20,000 IOPS | 16 vCPU/128 GiB；事务提交至少得到一个 standby 确认；io2 为 OLTP 提供稳定低延迟 |
| 连接池 | Amazon RDS Proxy | Multi-AZ 托管 | `MaxConnectionsPercent=80`，`MaxIdleConnectionsPercent=10`，Borrow timeout 3s | 限制 Provider 总连接数并缩短数据库故障切换影响 |
| Redis 兼容服务 | ElastiCache for Valkey **9.0** node-based，Cluster Mode Enabled，**durability=sync**，`cache.r7g.2xlarge` | 1 shard，1 primary + 2 replicas，3 AZ | 每节点 52.82 GiB；有效数据只按 primary 的一份计算 | 写入在至少两个 AZ 的事务日志持久化后才确认，单点故障不丢已确认写入 |
| Kafka | MSK Provisioned **Standard**，Kafka **3.9.x KRaft**，`kafka.m7g.2xlarge` | 3 Broker，3 AZ | 每 Broker 6 TiB，Provisioned throughput 312.5 MiB/s；存储自动扩到 16 TiB，目标 60% | 8 vCPU/32 GiB；支持当前 Kafka Streams；RF=3 时 3 Broker 已满足单 Broker 故障 |
| 外部入口 | Internet-facing ALB + AWS WAF + ACM | 至少 2 AZ | ALB idle timeout 300s | `/api/*` 到 Gateway，`/ws/*` 到 WebSocket，原生支持 WebSocket |
| 内部入口 | Internal NLB | 至少 2 AZ | 每个 Provider 独立 target group | 稳定内网地址，避免 Provider 暴露公网 |
| 监控 | CloudWatch Database Insights Advanced + Amazon Managed Service for Prometheus + Amazon Managed Grafana | AWS 托管 | 数据库指标保留 15 个月，JFR/heap dump 归档 S3 | 同时观察应用、JVM、RDS、MSK、Valkey 和 ALB |

### PostgreSQL 参数

RDS cluster parameter group 固定设置：

```text
rds.force_ssl = 1
synchronous_commit = on
wal_compression = lz4
autovacuum = on
track_io_timing = on
shared_preload_libraries = 保留 AWS 默认项并追加 pg_stat_statements
pg_stat_statements.track = all
log_min_duration_statement = 200
idle_in_transaction_session_timeout = 30000
```

同时执行 `CREATE EXTENSION IF NOT EXISTS pg_stat_statements`，并启用 KMS、删除保护、35 天自动
备份、PITR、CloudWatch Database Insights Advanced 和 Enhanced Monitoring。核心交易读写始终连接
RDS Proxy 的 writer endpoint；只把报表和离线查询发到 reader endpoint，因为 reader 可能有复制延迟。

Multi-AZ DB Cluster 是半同步复制：writer 会把 WAL 发给两个 standby，但提交只要求至少一个
standby 确认。不要强制等待两个 standby，否则一个可用区故障会让整个交易系统停止写入。

RDS Multi-AZ DB Cluster 当前不支持存储自动扩容。4 TiB 使用率达到 65% 时告警，达到 70% 时固定
扩到 6 TiB；扩容只能增加不能缩小。

### Valkey 参数

创建自定义 `valkey9` Cluster Mode 参数组：

```text
maxmemory-policy = noeviction
cluster-require-full-coverage = yes
slowlog-log-slower-than = 10000
slowlog-max-len = 1024
```

同时启用 Multi-AZ、自动故障切换、TLS、KMS、ACL user group 和每日快照。不要再用
`min-replicas-to-write` 模拟同步复制；这里真正的数据安全来自 `durability=sync` 的 Multi-AZ
事务日志。普通 Redis/Valkey 主从复制仍是异步的。

应用必须连接 configuration endpoint，而不是某个 primary IP，并使用 Cluster-aware Lettuce 客户端。
所有 Lua 脚本涉及的多个 key 必须具有相同 `{hash-tag}`。当前持仓、活动订单、触发单和 ADL key
设计已经使用 hash tag，上线测试仍要逐个脚本验证 `CLUSTER KEYSLOT`。

### Kafka 参数

MSK Broker 配置固定为：

```text
auto.create.topics.enable = false
default.replication.factor = 3
min.insync.replicas = 2
unclean.leader.election.enable = false
compression.type = producer
num.partitions = 32
```

Producer 继续使用项目已有的：

```text
acks=all
enable.idempotence=true
compression.type=zstd
max.in.flight.requests.per.connection<=5
```

`acks=all + RF=3 + minISR=2` 表示正常提交至少由两个同步副本确认。不要把 `minISR` 设成 3，否则
滚动维护或坏一个 Broker 时无法写入。

只创建一条产品线的 topic：

```bash
PRODUCT_LINES=LINEAR_PERPETUAL \
INCLUDE_SHARED_TOPICS=true \
INCLUDE_LEGACY_PERP_TOPICS=false \
PARTITIONS=32 \
ACCOUNT_COMMAND_PARTITIONS=32 \
REPLICATION_FACTOR=3 \
BOOTSTRAP_SERVERS='<MSK bootstrap brokers>' \
./scripts/create-topics.sh
```

当前会创建约 25 个 topic、800 个逻辑 partition；RF=3 后约 2400 个 partition replica，平均每个
Broker 约 800 个，低于 `kafka.m7g.2xlarge` 每 Broker 2000 个 replica 的建议上限，所以首发不需要
5 个 Broker。

Topic 保留策略：

| Topic 类型 | retention | cleanup.policy |
|---|---:|---|
| index/mark/book ticker/orderbook/candle/public trade | 24 小时 | `delete` |
| order/match/account/position/risk/liquidation/funding | 7 天 | `delete` |
| `*.dlt.*` | 30 天 | `delete` |
| instrument 当前状态 | 不按时间删除 | `compact` |
| Kafka Streams changelog/repartition | 由 Kafka Streams 创建和管理 | 不手工覆盖 |

MSK 使用 TLS + SASL/SCRAM-SHA-512，Secrets Manager 保存账号密码，Kafka ACL 默认拒绝。应用需要
统一加入以下客户端参数，不能使用明文 9092：

```text
security.protocol=SASL_SSL
sasl.mechanism=SCRAM-SHA-512
ssl.endpoint.identification.algorithm=https
```

## 5. 容量是怎样定下来的

### Valkey

首发按以下上限估算：

| 数据 | 数量上限 | 保守单条占用 | 估算 |
|---|---:|---:|---:|
| 持仓 slot | 100 万 | 1.5 KiB | 1.5 GiB |
| 活动订单 | 100 万 | 2 KiB | 2 GiB |
| trigger/ADL/liquidation 索引 | 200 万 | 1 KiB | 2 GiB |
| revision、key、hash/zset 和碎片余量 | — | — | 6.5 GiB |
| 合计有效数据 | — | — | **12 GiB** |

节点内存公式：

```text
所需节点内存 = 峰值 used_memory_dataset × 1.5 / 0.55
             = 12 GiB × 1.5 / 0.55
             = 32.7 GiB
```

`cache.r7g.xlarge` 只有 26.32 GiB，不满足；下一个固定规格
`cache.r7g.2xlarge` 为 52.82 GiB，因此选择它。生产告警线：

- `DatabaseMemoryUsagePercentage` 或实际数据内存达到 55%；
- Engine CPU 持续超过 50%；
- evictions 不等于 0；
- 同步写 P99 超过 10 ms，读 P99 超过 2 ms。

容量计算成立的前提是终态订单从 Redis 删除、零仓位删除 state/margin field、revision tombstone
设置 TTL。若零仓位永久保留，Redis 会按历史交易过的 user×symbol 持续增长，不能上线。

### PostgreSQL

4 TiB 的基线按“高增长表 30 天热数据不超过 80 GiB/天”确定：

```text
(当前数据库 + 80 GiB/天 × 30 天) × 1.3 索引/膨胀余量 < 4 TiB
```

压测前后用 `pg_total_relation_size` 统计真实增长：

```text
日增长 = (压测后总大小 - 压测前总大小) / 压测小时数 × 24
```

若实测日增长超过 80 GiB，上线前必须给 order/event/match/trade/price/risk 快照等大表增加按时间
partition、保留期和 S3 归档；不能只继续扩大单表和磁盘。资金账本长期保留，但同样需要按时间分区。

### Kafka

Kafka 按压缩后的真实写入速率计算，不按 JSON 原始大小猜测：

```text
每 Broker 所需空间
= Σ(各 topic 压缩后 bytes/s × retention 秒) × RF / Broker数 / 0.60
```

3 Broker、RF=3 时，副本因子和 Broker 数相抵。按加权保留后的持续写入 5 MiB/s 计算：

```text
5 MiB/s × 7 天 / 0.60 ≈ 4.9 TiB/每 Broker
```

因此初始购买 6 TiB/每 Broker，并在 60% 自动向上扩容，最大 16 TiB。磁盘不能缩小；若稳定写入
超过该假设，先缩短非审计行情 retention，再扩容。

## 6. 购买后必须修改的应用配置

| 项目 | 生产值 |
|---|---|
| 产品线 | 所有 Provider：`LINEAR_PERPETUAL` |
| product topic | 所有 Provider：`PRODUCT_TOPICS_ENABLED=true` |
| PostgreSQL | RDS Proxy writer URL，JDBC `sslmode=verify-full` |
| Valkey | configuration endpoint、DB 0、Cluster-aware、TLS、ACL username/password |
| Kafka | MSK TLS/SCRAM bootstrap brokers，统一注入 SASL_SSL 参数 |
| Account | 每节点 `ACCOUNT_USER_COMMAND_CONCURRENCY=16`、`ACCOUNT_DB_MAX_POOL_SIZE=24`、`ACCOUNT_DB_MIN_IDLE=4` |
| Matching | `MATCHING_KAFKA_CONCURRENCY=4`、`MATCHING_ENGINE_SHARDS=4`、`MATCHING_RISK_SHARDS=2` |
| Candlestick | 两节点使用相同 application-id；每节点使用独立 `/data/kafka-streams/<host>` |
| WebSocket | 两节点 group-id 必须不同，使每个节点都收到公共行情 |
| Price/Risk/Funding/MM | 两节点 node-id 必须不同，lease duration 保持项目默认值 |
| systemd | `Restart=always`、`RestartSec=3`、`TimeoutStopSec=90`、`LimitNOFILE=1048576` |

当前代码在生产启用前还必须完成并验证：

1. 所有自建 Kafka Producer/Consumer/Kafka Streams 配置统一支持 TLS + SASL/SCRAM；仅设置
   `spring.kafka.*` 不够，因为项目中多处直接构造 Kafka client config。
2. `scripts/create-topics.sh` 增加 Kafka CLI `--command-config`，让建 topic 和调整 retention 时也使用
   TLS + SASL/SCRAM。
3. Redis 配置从 standalone host/port 改为 Cluster-aware Lettuce，并打开 TLS 和 ACL username。
4. 零仓位删除 Redis state/margin field，revision 使用有 TTL 的 tombstone。
5. PostgreSQL 高增长表增加 partition、保留期和归档；当前无限增长的普通表不能直接长期运行。
6. 对 Valkey 9.0 做 Lua、`HSET/HDEL`、ZSET range、CAS revision 和故障切换兼容测试。
7. 增加撮合不可用/恢复中的下单入口保护；当前单撮合恢复期间不能继续无限接受新订单。

## 7. 压测如何决定是否需要升级

首发验收负载固定为：

- 100 个 symbol，其中 3 个热点 symbol；
- 3000 次下单/撤单/改单每秒；
- 1000 笔成交每秒；
- 100 万活动订单、100 万持仓 slot；
- 20 万 WebSocket 在线连接；
- 1 倍负载稳定 60 分钟，2 倍突发 15 分钟；
- 1 倍负载下分别执行 EC2、RDS writer、Valkey primary、MSK Broker 故障演练。

通过标准：

| 指标 | 通过线 |
|---|---:|
| 下单受理 P99 | ≤ 80 ms |
| 可成交订单从受理到 match result P99 | ≤ 150 ms |
| trade 到资金/持仓 Redis 可见 P99 | ≤ 300 ms |
| PostgreSQL commit P99 | ≤ 20 ms |
| Valkey 同步写 P99 / 读 P99 | ≤ 10 ms / ≤ 2 ms |
| Kafka consumer lag | 正常 < 1 秒，故障后 5 分钟内归零 |
| 普通 EC2 CPU | 1 倍 < 55%，2 倍 < 75% |
| MSK Broker CPU | < 60% |
| JVM GC | pause P99 < 20 ms，无超过 100 ms 的异常停顿 |
| 资金核对 | 绝对零差异 |

升级规则固定，不临时拍脑袋：

- 普通 Provider 在依赖组件正常时仍超过 CPU/延迟线：`m8i.large → m8i.xlarge →
  m8i.2xlarge → m8i.4xlarge`，只升一级后重跑完整压测。
- 撮合先把 `matching/risk shards` 从 `4/2` 调到 `8/4` 并重测；如果是单热点 symbol，增加 shard
  没有效果，直接升级到 `m8i.8xlarge`。仍失败则必须改撮合架构。
- Account 只有在 DB CPU < 50%、Hikari 等待连接且 Kafka partition 足够时，才把每节点
  user concurrency 从 16 调到 24，同时把 DB pool 从 24 调到 32。
- RDS CPU 持续 > 50%、DBLoad 超过 16 或 commit P99 超线：升级为
  `db.r6gd.8xlarge`，同时把 io2 提升到 40,000 IOPS，再重测。
- Valkey 内存超过 55% 或 CPU 超过 50%：升级为 `cache.r7g.4xlarge`；如果同步写流量接近
  100 MiB/s 的单 shard 上限，改为 2 shard。
- MSK CPU 超过 60%、磁盘吞吐持续超过 70% 或 lag 超线：全部 Broker 升为
  `kafka.m7g.4xlarge`；不要先增加无意义的 partition。

每次改 JVM、线程数、实例类型或中间件参数，只改一个变量，重复同一份流量回放和资金对账。最终
生产参数必须来自压测报告，而不是来自一次短时 TPS 峰值。

## 8. 上线检查

- 所有 EC2 在 private subnet，无公网 IP；运维只用 SSM Session Manager。
- ALB、RDS、MSK、Valkey、EBS、Secrets Manager 全部使用 KMS 和 TLS。
- 主机关闭 swap，使用 Amazon Time Sync，`vm.swappiness=1`，
  `net.core.somaxconn=65535`。
- CloudWatch 告警至少覆盖：EC2 CPU/磁盘、JVM GC、Kafka lag/URP、RDS CPU/commit/ReplicaLag/
  storage、Valkey memory/CPU/TrafficManagementActive、outbox backlog。
- 做 RDS、Valkey、MSK、单个 Provider 和撮合进程的实际故障演练。
- 完成订单、成交、资金、持仓、手续费、资金费、强平、保险基金和 ADL 全链路零差异对账。

## AWS 官方依据

- [EC2 M8i 规格](https://docs.aws.amazon.com/ec2/latest/instancetypes/gp.html)
- [RDS Multi-AZ DB Cluster 架构和半同步复制](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/multi-az-db-clusters-concepts.html)
- [RDS io2 适用于低延迟 OLTP](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/CHAP_Storage.html)
- [RDS PostgreSQL 版本](https://docs.aws.amazon.com/AmazonRDS/latest/PostgreSQLReleaseNotes/postgresql-versions.html)
- [CloudWatch Database Insights](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/Database-Insights.html)
- [ElastiCache Valkey 同步持久化](https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/durability.html)
- [ElastiCache durable cluster 限制](https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/Durability.Limitations.html)
- [ElastiCache 节点内存规格](https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/CacheNodes.SupportedTypes.html)
- [MSK Broker partition 建议和 RF/minISR](https://docs.aws.amazon.com/msk/latest/developerguide/bestpractices.html)
- [MSK Provisioned storage throughput](https://docs.aws.amazon.com/msk/latest/developerguide/msk-provision-throughput-management.html)
- [MSK 存储自动扩容](https://docs.aws.amazon.com/msk/latest/developerguide/msk-autoexpand-details.html)
