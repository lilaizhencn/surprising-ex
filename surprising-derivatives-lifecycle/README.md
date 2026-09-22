# 衍生品后台

`surprising-derivatives-lifecycle-provider` 统一运行资金费、风险、强平、保险基金和 ADL。
默认端口 `9087`，启动类 `SurprisingDerivativesLifecycleApplication`，Core 保持独立。

## 资金费业务顺序

1. `FundingRateInputRepository` 读取当前产品共享的合约快照和标记价，按合约版本校验输入。
2. `FundingService.publishRates` 获取原有数据库租约、计算费率并发布产品专属资金费事件。
3. 到结算时间后，`FundingService.settleDueRates` 校验维护状态、使用原有结算 ID 分页发起 Core 命令，保存进度。
4. `FundingAeronGateway` 使用 `DerivativesAeronClient`，保留资金费拒绝码和幂等结果处理。网络结果未知继续向上传递，不盲目重试或当成成功。

合并不改变费率公式、结算 ID、数据库租约、产品 topic 和 Core 资金记账边界。
`com.surprising.funding.provider` 保留为业务包；共享 funding API 模块保留。

## 共享资源及隔离

- 一套数据库连接池，仍使用各业务原有表；原 funding 表须部署到此实例的数据源，不能遗漏原有迁移。
- 一份 `derivativesInstrumentSnapshotCache`，资金费不再自行初始化或创建一份缓存。
- risk、强平、ADL、funding 使用 `DerivativesAeronClient`，连接参数取 `surprising.risk.aeron` / `AERON_*`。保险业务的 `InsuranceAeronGateway` 仍有实际使用的独立客户端池，保留 `surprising.insurance.aeron`。
- 一份标记价消费缓存，保留衍生品后台 5 秒的新鲜度要求。
- `taskScheduler` 负责强平、保险、ADL，默认 6 线程；`fundingScheduler` 独立 2 线程负责费率发布和结算，避免资金费等待占用生命周期调度线程。共享客户端、数据库和 JVM 仍可能产生资源竞争。
- `FundingConfiguration` 只对 U/币本位永续加载资金费组件；交割、期权不创建资金费任务、消费者或接口。资金费产品配置与生命周期产品不一致时启动失败。

## 构建和部署

使用 HotSpot JDK 27：

```bash
mvn -pl surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-provider -am package -DskipTests
```

设置 `PRODUCT_LINE=LINEAR_PERPETUAL`（或其他目标衍生品）、数据源、Kafka、Aeron 和 `BUSINESS_INTERNAL_TOKEN`。
`/api/v1/funding` 保留，网关及 FundingRpcApi 默认目标改为 `9087`；自定义 `GATEWAY_ROUTE_FUNDING_BASE_URL` /
`surprising.clients.funding.base-url` 需同步指向此实例。停用原 funding 进程，避免两套部署并存。

启动脚本 `scripts/start-product-line-providers.sh` 不再包含独立 funding 进程。
每个产品实例与对应 Core、数据库 schema、topic 隔离；本轮合并不将不同产品账户混合。

## 2026-09-22 合并验证

基线 `9ebdc0c1`（Core admission 回收修复已先提交），HotSpot JDK 27、Maven 3.9.16。

| 范围 | 通过 | 跳过 |
| --- | ---: | ---: |
| 合并后的 lifecycle provider | 34 | 0 |
| Aeron client | 50 | 0 |
| 业务网关 | 513 | 34 |
| W4 联调工具 | 7 | 0 |

共享 funding/lifecycle API 编译通过，当前无测试类。六产品启动 dry-run 和 production-chain-preflight 合约检查通过。
五条衍生品使用生产 YAML 和完整组件扫描启动 Spring Web 测试上下文，验证资金费接口在永续返回 200，交割/期权返回 404。
外部 Aeron、JDBC、合约快照启动使用测试替身，Kafka 消费关闭，定时业务执行关闭；独立调度测试实际占满资金费线程，确认生命周期线程仍能推进。
资金费测试保留分页进度、维护拒绝、结算 ID 幂等和失败处理；共享客户端适配另外验证业务身份、拒绝及未知结果不重试。

网关 34 项跳过包括需要 PostgreSQL、原生业务服务等环境的集成测试；本轮未重跑完整 HTTP → 数据库 → Aeron 的资金费端到端、三节点故障、长稳或饱和吞吐。
Core 六产品资金/快照回归在前一修复提交已通过。不能把组件装配/MockMvc 验证表述为所有产品实际交易验收。
逐类结果和跳过原因见 [验证摘要](../docs/validation/funding-lifecycle-merge-20260922.json)。

主要命令：

```bash
mvn -pl surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-provider -am -DskipTests install
mvn -pl surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-provider,surprising-gateway test
mvn -pl surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-provider -Dtest=LifecycleApplicationContextTest test
mvn -pl surprising-aeron-core/surprising-aeron-client,surprising-funding/surprising-funding-api,surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-api test
mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -Dtest=W4LifecycleQaMainTest test
mvn -pl surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-provider,surprising-gateway,surprising-aeron-core/surprising-aeron-benchmarks -DskipTests package
PRODUCT_LINE=LINEAR_PERPETUAL RUN_ID=funding-merge-review ACTION=dry-run AERON_CLUSTER_HOSTNAMES=127.0.0.1 bash scripts/start-product-line-providers.sh
TASK1_TEST_ROOT="$PWD/.local-logs/funding-merge-preflight-20260922" bash scripts/test-production-chain-preflight.sh
```

最终可执行包检查通过：包含资金费入口和条件装配，只有 lifecycle 启动类，不包含旧 funding 启动类、重复合约初始化或 Core service 运行依赖。测试/JMH 进程已退出，本轮 preflight 数据、原始 JFR、日志及已汇总报告已清理，保留构建产物与验证摘要。


## 合并后的初始化与配置清理（2026-09-22）

只保留 `SurprisingDerivativesLifecycleApplication` 启动入口，删除原 risk/liquidation/insurance/ADL 四个 Application
及专门排除它们的扫描配置；funding 的产品条件扫描仍保留。

`DerivativesInstrumentSnapshotInitializer` 仍从 gateway 加载当前产品快照，不能删除。
ADL 与保险原先消费同一 Instrument topic 并更新同一个 `derivativesInstrumentSnapshotCache`；
现在保留保险包的消费者/工厂作为共享更新入口，沿用原 consumer group，删除 ADL 的重复消费者/工厂。
risk、funding、保险和 ADL 继续读同一份缓存，产品线校验、事件 key 校验和快照版本规则不变。

已删除的无效配置：`surprising.adl.aeron`、ADL 旧风险索引 Kafka 参数/`redis-index`、
`surprising.liquidation.aeron` 的 hostnames/egress-hostname/response-timeout、
固定按 ProductLine 生成却仍暴露 setter 的 funding-rate-topic / insurance group-id / liquidation-fee-events-topic，
以及本进程没有 Feign 调用者的 `surprising.clients.derivatives-lifecycle.base-url`。
强平的 `aeron.client-connections` 仍作为本地异步提交宽度使用，保留；真实共享连接参数取 risk.aeron。
保险消费并发、资金费调度/协调租约、数据库迁移及标记价新鲜度配置均保留。

本轮 lifecycle 34 项测试全部通过，含五产品完整组件配置与共享快照实际更新；gateway 537 通过、34 外部环境测试跳过。
完整外部数据库/Kafka/Core 重启和吞吐未测。详见 [清理验证摘要](../docs/validation/merged-config-cleanup-20260922.json)。
