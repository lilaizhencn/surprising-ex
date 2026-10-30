# exchange-core 依赖对齐验证

日期：2026-10-30。环境：macOS x86_64、HotSpot Corretto 27、Maven 3.9.16；开始前磁盘可用约 526 GiB。

## 变更

fork `exchange-core-lilaizhencn` 从 `0.5.18-emporia` 升为 `0.5.19-emporia`，本项目父 POM 同步引用新版本。
fork 导入与本项目相同的 Spring Boot 4.1.2 BOM，只管理依赖版本，不引入 Spring 运行时。

| 依赖 | 升级前 | 升级后 / 依据 |
| --- | --- | --- |
| Java release | 25 | 27，与本项目构建基线一致 |
| Agrona | 1.15.1 | 2.7.0，[Aeron 1.54.0 官方版本清单](https://github.com/aeron-io/aeron/blob/1.54.0/gradle/libs.versions.toml) |
| HdrHistogram | 2.1.12 | 2.2.2，同 Aeron 与本项目 |
| LZ4 | org.lz4:lz4-java:1.8.0 | at.yawk.lz4:lz4-java:1.11.4，同本项目 |
| Jackson 3 | 3.1.4 | 3.1.7，Spring Boot BOM |
| SLF4J | 2.0.18 | 2.0.20，Spring Boot BOM |
| Logback | 1.5.34 | 1.5.38，Spring Boot BOM |
| PostgreSQL JDBC | 42.7.11 | 42.7.14，Spring Boot BOM |
| Hamcrest | hamcrest-library:1.3 | hamcrest:3.0，Spring Boot BOM |

JUnit 6.0.3、Mockito 5.23.0、Testcontainers 2.0.5、Commons Lang 3.20.0 改由 Boot BOM 统一管理；
Chronicle BOM 2026.18、Lombok 1.18.48 保持与本项目一致。无共同版本约束的 Disruptor、Eclipse Collections、
JNA 等保留原版本。没有更改撮合、资金、持仓、事务或快照格式的业务代码。

## 已执行验证

fork 保留“干净 Git 提交 + git archive + JAR 内源码来源信息”的构建检查。先在临时的干净 master 候选副本
执行验证，再提交正式 fork，并从正式提交重新安装制品；没有关闭该检查。

| 仓库 | 命令 | 结果 |
| --- | --- | --- |
| fork 候选副本 | `mvn -o -B -ntp clean verify` | 338 项测试，0 失败、0 错误、0 跳过；包括源码、Javadoc 制品和来源检查 |
| 正式 fork | `mvn -o -B -ntp clean install` | 338 项测试，0 失败、0 错误、0 跳过；已安装到本地 Maven 仓库 |
| 正式 fork | `mvn -o -B -ntp test -Dtest=ITExchangeCoreIntegrationBasic,ITExchangeCoreIntegrationRejectionBasic,ITFeesExchangeBasic,ITFeesMarginBasic` | 补充 44 项测试，0 失败、0 错误、0 跳过 |
| fork / 本项目 | `help:effective-pom` 与 `dependency:tree`，再解析 XML 比对 | 17 项共享受管直接依赖版本一致；fork 不含 Spring 运行时依赖或旧 org.lz4 制品 |
| 本项目 | `mvn -o -B -ntp -pl surprising-aeron-core/surprising-aeron-service -am dependency:tree`（过滤目标依赖） | 消费端实际解析 exchange-core 0.5.19-emporia、Aeron 1.54.0、Agrona 2.7.0、Jackson 3.1.7、LZ4 1.11.4、SLF4J 2.0.20 |
| 本项目 | `mvn -o -B -ntp clean verify` | Core 服务 1567 项（1 项 macOS 条件跳过）、benchmarks 功能测试 312 项及此前模块通过；在行情模块因现有测试模拟过期失败，见下文 |

fork 的功能测试覆盖 Direct/Naive 的 Equity、Exchange、Margin 规格，FOK 原子成交、资金核对、
现货及保证金手续费、业务拒绝、HTTP JSON、快照和 GTX 日志回放。

正式 fork 提交：`5c62e53e48d6aa5e0978a33003250c3c6b85dd4c`。
安装 JAR 的 `META-INF/surprising-exchange-core.properties` 与该 SHA 一致，`fork.git.dirty=false`；
`ExchangeCore.class` major version 为 71（Java 27）。

JAR SHA-256：`fc91e384f1d787c2b15908744022247b59f8c743b48ae09c2a90b890e876f7a3`。

原 Maven 镜像出现 TLS 握手失败。新增的 Agrona 2.7.0 POM/JAR 从 Maven Central 下载并校验官方 SHA-1，
后续构建离线使用已解析的缓存；没有修改用户的全局 Maven 设置。

## 验证时发现的现有测试问题

首次全项目验证在 `ExportBatchTest` 失败 17 项、`CommittedTradeExportIntegrationTest` 失败 1 项。
分别用以下命令覆盖 `exchange-core.version=0.5.18-emporia` 复现了相同失败：

```shell
mvn -o -B -ntp -pl surprising-realtime/surprising-realtime-provider -am \
  -Dexchange-core.version=0.5.18-emporia -Dtest=ExportBatchTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
mvn -o -B -ntp -pl surprising-realtime/surprising-realtime-provider -am \
  -Dexchange-core.version=0.5.18-emporia -Dtest=CommittedTradeExportIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

现有 `ExportBatch.flush` 已调用带 `feeObservations` 的八参数 `persist`，上述测试仍模拟、验证七参数重载，
导致异常注入和调用断言未命中。修正两处测试文件的模拟及验证参数，批处理测试额外断言手续费观察列表为空，
保留 SQL/Kafka 失败重试、业务顺序和水位断言；未改生产导出逻辑。

两处导出测试修复后，按新依赖执行定向命令，21 项全部通过：

```shell
mvn -o -B -ntp -pl surprising-realtime/surprising-realtime-provider -am \
  -Dtest=ExportBatchTest,CommittedTradeExportIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

随后 `mvn -o -B -ntp -DskipTests install` 在 23 个模块全部成功，完成编译、测试编译、打包及本地安装，
该命令跳过测试执行。再执行
`mvn -o -B -ntp -pl surprising-realtime/surprising-realtime-provider,surprising-gateway verify`，
行情模块 202 项（56 项数据库条件跳过）无失败，但网关另有 7 项现有测试失败。

网关通过以下命令使用旧依赖复现相同的 1 项失败和 6 项上下文错误：

```shell
mvn -o -B -ntp -pl surprising-gateway -Dexchange-core.version=0.5.18-emporia \
  -Dtest=ConstructorInjectionContextTest,AdminCursorPageTest test
```

`ConstructorInjectionContextTest` 没有注册现有构造器需要的 `GatewayApiKeyService` 模拟对象；
网关的 `AdminCursorPageTest` 仍拒绝公共 trading API 已支持的冒号游标格式，与该公共模块自己的测试矛盾。
补齐测试对象，改为验证既有游标兼容行为，并保留非法时间戳拒绝断言；未改网关生产行为。
两处网关测试修复后的定向验证为 9 项全部通过：
`mvn -o -B -ntp -pl surprising-gateway -Dtest=ConstructorInjectionContextTest,AdminCursorPageTest test`。

最后执行 `mvn -o -B -ntp -pl surprising-gateway verify`，网关 1835 项（468 项环境条件跳过），
0 失败、0 错误，构建成功。

## 最终测试汇总

以下按各模块最终 Surefire 报告汇总，初轮中已通过的模块保留其结果；行情及网关采用修复后完整复测的报告。
每个模块只计一次，合计 **4820 项：4270 通过、550 条件跳过、0 失败、0 错误**。
fork 单独为 **338 + 44 = 382 项通过**，不把候选副本或重复执行计入通过数。

| 模块 | 测试总数 | 跳过 |
| --- | ---: | ---: |
| instrument-api | 30 | 0 |
| aeron-protocol | 258 | 0 |
| aeron-client | 69 | 0 |
| aeron-service | 1567 | 1 |
| aeron-tools | 20 | 0 |
| trading-api | 15 | 0 |
| account-api | 4 | 0 |
| price-consumer | 9 | 0 |
| maker | 178 | 0 |
| realtime-api | 40 | 0 |
| price-provider | 154 | 3 |
| aeron-benchmarks 功能测试 | 312 | 0 |
| derivatives-lifecycle-provider | 127 | 22 |
| realtime-provider | 202 | 56 |
| gateway | 1835 | 468 |

550 项跳过包括：Linux CPU 绑定 1 项；缺少数据库测试连接、Redis 测试端口、在线验收 URL/开关/目录
或 `gateway.shared-driver.it` 系统属性的测试 549 项。环境条件未满足的场景不计为通过。

所有正式构建制品与项目依赖保留；临时候选副本、临时 Maven 设置已清理，原始命令日志与测试统计压缩归档于本机缓存。

## 未测范围

未运行 fork 的 `postgres-it` 容器数据库配置，未另行启动正式压测、JMH/JFR 或生产部署。
默认测试包含 benchmarks 模块的功能测试，不据此给出容量或性能结论。
macOS 跳过 `TradingCpuAffinityTest.linuxBindsEachRoleToTheRequestedCpuAndRestoresItsOriginalMask`，
原因为 `Disabled on operating system: Mac OS X`，Linux CPU 绑定行为仍需在 Linux 验证。
快照测试覆盖本轮生成后的恢复，未拿升级前的生产快照文件做跨版本回放；不据此宣称生产数据迁移已经验证。
