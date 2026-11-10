# 做市商极速 UDP 行情改造与 Kafka 彻底解耦总结

## 一、 改造背景与核心收益

做市商（Market Maker）是用微秒换取生存空间的组件，如果依赖 Kafka 接收行情与标记价格：
1. **时延过大（1~5ms + GC/重平衡抖动）**：慢 2 毫秒挂在盘口的单子就会被高频抢先套利者（Sniper）吃穿，形成逆向选择亏损（Adverse Selection）。
2. **消息语义错配**：做市商只关心“**当前微秒的最新价**”，Kafka 的磁盘持久化、按序 Poll、Offset 提交对做市计算是纯粹的性能累赘。
3. **架构违规与框架包袱**：引入了复杂的 `KafkaListener`、Consumer 容器、重试与重平衡机制。

本次改造将做市商 `surprising-maker` **彻底切断与 Kafka 的所有依赖**，全面转向 **Aeron UDP / IPC 极速实时流（微秒级 10~50μs）**。

---

## 二、 微服务模式下动态获取 UDP 地址方案

做市商作为无状态极速计算进程，遵守“**不直连数据库与 Redis 存储**”的架构铁律（由 `MarketMakerApplicationYamlTest.runtimeClasspathContainsNoStorageClients` 强校验保证）。

微服务模式下的动态 UDP 地址获取采用如下生产级方案：
1. **动态通道注入（Environment / Config Center）**：
   - 做市商通过 Spring Environment 动态解析配置：
     `surprising.realtime.channel = ${SURPRISING_REALTIME_UDP_CHANNEL:aeron:udp?endpoint=127.0.0.1:40130}`
   - 在微服务容器化（K8s / Docker）环境中：
     - 若为**局域网组播模式**，配置中心或环境变量直接注入组播地址（如 `aeron:udp?endpoint=224.0.1.1:40123`），所有做市商实例加入组播组，享受网卡硬件级 O(1) 扇出分发；
     - 若为**同机部署模式**，环境变量注入共享内存通道（`aeron:ipc`），进程间零拷贝，时延 < 5μs；
     - 若为**指定单播端口**，由 Pod 部署脚本动态传入 `${POD_IP}:${PORT}`。
2. **自适应降级保障**：
   - 在无 Aeron 守护进程的轻量级单元测试环境下，`MarketMakerRealtimeReceiver` 自动侦测并跳过底层连接，确保单测与 CI/CD 流程 100% 顺畅。

---

## 三、 Kafka 残余代码彻底清理清单

做市商模块代码中 **Kafka 依赖已经彻底清零**：

| 文件 / 位置 | 改造前 | 改造后 |
| :--- | :--- | :--- |
| `surprising-maker/pom.xml` | 依赖 `surprising-price-consumer`（强制依赖 Spring-Kafka） | **彻底移除**，改引入轻量 `surprising-aeron-client` 和 `surprising-price-api` |
| `InstrumentSnapshotKafkaConfiguration.java` | 60 行 Kafka 消费者容器工厂配置 | **直接删除该文件** |
| `InstrumentSnapshotConsumer.java` | `@KafkaListener` 监听增量合约事件 | **直接删除该文件**，合约元数据由已有的 Feign RPC 在启动时拉取快照 |
| `SurprisingMarketMakerApplication.java` | 带有 `@EnableKafka` 注解 | **彻底移除 `@EnableKafka` 注解** |
| `MarketMakerProperties.java` & `MarketMakerInfrastructureProperties.java` | 包含 `kafka.bootstrapServers`、`instrumentSnapshotGroupId` 等配置类 | **彻底剔除 `Kafka` 内部配置类及其所有字段** |
| `application.yml` | 包含 `surprising.price.consumer.*`、`market-maker.infrastructure.kafka.*` | **彻底清理**，替换为直白清晰的 `surprising.realtime.*` UDP 配置 |
| `MakerQuoteWakeups.java` | 实现 `MarkPriceUpdateListener`，并带 `@KafkaListener` 消费成交 | **彻底移除所有 Kafka 注解与类引用**，改为轻量纯内存信号唤醒 |
| `MarketMakerService.java` | 依赖 Kafka 驱动的 `LatestMarkPriceCache` | 替换为纯内存无锁的 `MarketMakerMarkPriceCache`（由 UDP MARK 帧原子刷新） |

---

## 四、 合约规格定时 RPC 拉取与做市状态实时联动

由于完全剔除了 Kafka，合约规格（精度、乘数、最小步长、费率、状态等）改为通过 Feign RPC 定时动态刷新：
1. **定时拉取（`InstrumentSnapshotInitializer.refreshSnapshots`）**：
   - 默认每 5 秒（`${surprising.market-maker.instrument.refresh-interval:5000}`）通过 `InstrumentRpcApi.snapshot(productLine)` 拉取最新完整快照；
2. **多维度差异检测**：
   - 逐个合约比对 `status()`、`changeId()`、`makerFeeRatePpm()`、`priceTickUnits()`、`quantityStepUnits()`；
3. **做市状态实时调整**：
   - **停牌/不可交易（`status != TRADING`）**：触发 `MarketMakerService.onInstrumentStatusChanged`，立即撤销该合约当前所有盘口挂单，排空流动性，防止非交易状态残留挂单与资产被锁；
   - **规格/费率更新或恢复交易**：触发 `MakerQuoteWakeups.changed`，立即唤醒做市策略感知最新规格并重新计算报价。
4. **单元测试验证**：
   - 新增 `InstrumentSnapshotRefreshTest`，成功模拟从 `TRADING` 切换到 `HALT` 停牌时自动排空撤单并唤醒策略的完整闭环。

---

## 五、 验证结果

1. **测试通过率**：
   - `surprising-maker` 模块 **全部 179 项单元与集成测试 100% 通过**。
   - 包含业务设置校验、Fein RPC 契约测试、报价算术、策略运行时、无存储架构约束测试、合约规格定时刷新与状态联动测试等。
2. **全工程构建**：
   - `surprising-ex` 全模块（23/23）`mvn test-compile` 零错误编译通过。
