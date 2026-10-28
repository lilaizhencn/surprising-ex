# Aeron 1.54.0 升级与验证

日期：2026-10-28。目标：从 1.53.3 升级到正式发布的 1.54.0，并核对更新内容、Java 调用及运行兼容性。

## 版本与来源

- 统一修改 `surprising-parent/pom.xml` 的 `aeron.version`；Client、Driver、Archive、Cluster 通过 `aeron-all` 一起升级。
- [正式发布](https://github.com/aeron-io/aeron/releases/tag/1.54.0) 时间为 `2026-10-27T20:53:38Z`，不是预发布。
- 发布标签提交为 `454da1e4e6c7edf3dd6d34878512d3243384992a`。官方 JAR 的 `AeronVersion.VERSION=1.54.0`、`GIT_SHA=454da1e4e6` 与其一致。
- 更新摘要依据用户指定的 [2026-10-27 更新记录](https://github.com/aeron-io/aeron/blob/master/CHANGELOG.adoc#1540-2026-10-27)，另存了标签、主分支及 [版本差异](https://github.com/aeron-io/aeron/compare/1.53.3...1.54.0) 证据。标签里的 CHANGELOG 标题仍为 unreleased，正式发布状态以 Release 和 Maven Central 制品为准。

## 更新内容

| 范围 | 主要内容 | 对本项目的影响 |
| --- | --- | --- |
| CPU 亲和性 | Java Driver、Archive、Consensus、Service 增加线程绑核；支持有效 cgroup cpuset 偏移、同 JVM 拓扑/冲突校验及失败即终止选项 | 可用于后续 Linux 配置验收；本次不自动开启，不替换 `surprising.aeron.cpu-affinity.*` 的业务线程配置 |
| 网络 | Java/C UDP Traffic Class；Java/C 收发路径优化；C 接收批量、逐 socket 轮询和批发送配置完善 | 使用新版传输实现，未改变现有线程模式或调优参数，未据此宣称吞吐提升 |
| Archive | 修复客户端超时后关闭会话的 SIGSEGV；改进末帧恢复；中断关闭时仍同步 catalog；修正录制上限、空录制回放和 response-channel 等待处理 | 关系到节点关闭、磁盘恢复和回放；本轮保留原有业务快照协议 |
| Cluster | 修复服务客户端超时可能导致的 15 秒死锁；调整零服务快照推进；包含活动日志重试、快照排序和响应发送改进 | 核对真实单成员通信、资金幂等及共享连接生命周期 |
| Java/C/C++ API | Java 删除不带 `archiveId` 的两个旧 `RecordingPos` 查找重载，C/C++ 对应接口也要求归档身份；C agent 的负返回值及非法客户端状态有新的处理语义；C++ 移除 `BufferBuilder` | 工程使用 Java SDK；Realtime 的 5 处测试调用改为使用当前 `AeronArchive.archiveId()`，没有引入 C/C++ 适配 |
| 其他修复 | C/C++ 覆盖内存屏障、URI 参数、越界、计数器生命周期、ARM/PPC64 和端点释放；诊断命名由 EventLogging 转为 Tracing | 本次没有引入兼容层或改写诊断配置 |
| 上游依赖 | Agrona 2.7.0、SBE 1.41.0，以及 Gradle、Mockito、ByteBuddy 等构建和测试工具更新 | 工程继续使用 Maven 和 `aeron-all`，没有额外引入 SBE 工具依赖 |

1.54.0 的章节包含已回补到 1.53.x 的条目，例如部分 MDC、快照排序和活动日志重试修复；不能把全部条目都当成相对于本项目原先 1.53.3 的新增修复。

删除的 Java 重载为 `RecordingPos.findCounterIdByRecording(CountersReader, long)` 与 `RecordingPos.findCounterIdBySession(CountersReader, int)`。完整构建发现后一个重载在 Realtime 集成测试有 5 处引用，现统一补充具体归档身份，不使用未知归档的通配值。没有改变生产交易 API、整数单位、资金/持仓状态、幂等或前后端契约。

## 制品获取

首轮构建在依赖解析阶段失败：本机配置的阿里云镜像尚未提供 `aeron-all:1.54.0` JAR。随后从 [Maven Central 官方目录](https://repo.maven.apache.org/maven2/io/aeron/aeron-all/1.54.0/) 下载 JAR/POM，核对发布的 SHA-1，并额外记录 SHA-256，使用 `maven-install-plugin:3.1.4:install-file` 放入本地 Maven 缓存。没有改全局 settings、仓库地址或依赖版本策略。

JAR SHA-256：`eed9fad510778af55c69cbe5b43ddc7ba754f79f8d592268c63fc3ad9d25707b`。

## 验证环境与隔离

使用 Corretto HotSpot JDK 27（27+33-FR）、Maven 3.9.16、macOS x86_64；开始时可用磁盘约 229 GiB。

验证开始时，工作区存在其他正在进行的 Core 改动。首轮源码使用 `git archive` 导出已提交的 master `663256b72f34ddc4f2f9e914f702379e0cbf8c1f`，仅覆盖本次 Aeron POM 变更和 5 处测试适配。

验证期间，并行任务将 Core 改动提交为 `e0f7e3f6edf5cd2f711f8d1fe9167955db099e03`。因此再次导出这个提交，覆盖同样的 6 个文件，进行最终干净构建和直接相关的集成验证；校验覆盖文件的 SHA-256 与交付源码一致。两轮测试的基线和范围分别记录，不将旧基线的全量测试结果算作新基线的全量通过。没有改写其他任务的文件或构建目录。结构工具 CodeGraph 本轮不可用，按已知源码和依赖声明核对。

## 验证结果

| 基线 / 范围 | 命令或记录 | 结果 |
| --- | --- | --- |
| `663256b72`，首轮全量 | `mvn -B -ntp clean verify` | 已执行模块的测试通过；在 Realtime 测试编译时发现 5 处已删除 API，随后完成适配 |
| `663256b72`，剩余 Realtime / Gateway 测试 | `mvn -B -ntp -Dtest=<288 个剩余测试类> -Dsurefire.failIfNoSpecifiedTests=false -Dgateway.shared-driver.it=true verify`；完整参数见归档 `phase2-command.json` | 两轮合并 4,667 项：4,221 通过、445 跳过、1 失败、0 error；未重复计数已通过的上游模块 |
| `663256b72`，全部模块打包 | `mvn -B -ntp -DskipTests verify` | 退出码 0，23 模块成功；此项不代表执行了测试 |
| `e0f7e3f6e`，最终干净构建与直接相关测试 | 下方完整命令 | 退出码 0，23 模块成功；11 项中 10 通过、1 条件跳过、0 失败 / error |
| `e0f7e3f6e`，解析依赖 | `mvn -B -ntp dependency:tree '-Dincludes=io.aeron:*,org.agrona:*'` | 退出码 0，所有解析到的 Aeron 均为 `aeron-all:1.54.0` |
| `e0f7e3f6e`，检查最终 JAR | 核对 3 个 shaded JAR 的 `AeronVersion.class`、4 个 Boot JAR 内嵌的完整 Aeron JAR | 与官方 1.54.0 一致；Boot 内嵌 JAR 的 SHA-256 与上文一致 |

最终干净构建命令：

```sh
mvn -B -ntp clean verify \
  -Dtest=ArchiveReplayIntegrationTest,CommittedTradeExportIntegrationTest,CommittedFundingRepairIntegrationTest,CommittedDeliveryRepairIntegrationTest,CommittedPortfolioRepairIntegrationTest,SharedCommandTransportIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dgateway.shared-driver.it=true
```

最终基线实际通过的集成测试：

- `ArchiveReplayIntegrationTest`、`CommittedTradeExportIntegrationTest`、`CommittedFundingRepairIntegrationTest`、`CommittedDeliveryRepairIntegrationTest`：4 项通过，覆盖归档回放和交易 / 资金费 / 交割修复。
- `SharedCommandTransportIntegrationTest`：六产品线的真实单成员 Cluster 通信 6 项全部通过。
- `CommittedPortfolioRepairIntegrationTest`：测试编译通过；因未配置 `INSTRUMENT_TEST_JDBC_URL` 跳过执行。没有将其列为回放通过。

依赖树中 `exchange-core` 仍传递引入独立的 `agrona:1.15.1`，属于既有依赖，本次未调整。`aeron-all` 自带上游 Agrona 2.7.0；额外核对了 Core shaded JAR 的 `DirectBuffer.class` 与新版官方制品一致，Realtime Boot 的 classpath 索引中 `aeron-all` 位于独立 Agrona 之前。

### 已确认的既有失败

全量回归未全部通过：唯一失败是 `AdminCursorPageTest.pageBuildsStableNextCursorFromLastReturnedRow` 第 37 行。该测试要求拒绝旧的 Base64 `epochMillis:id` 游标，但原有 `AdminCursorPage` 解码逻辑明确接受这种格式。

为排除升级影响，从升级前 `663256b72` 提取原始实现和测试，仅加载 JUnit / AssertJ，不加载任何 Aeron 类，独立运行仍得到 3 项中 2 通过、1 项同位置失败。日志明确记录 `Aeron present: false`。本次没有修改分页协议或放宽断言。

### 未测范围

- 未配置外部数据库等条件测试所需环境，首轮合并结果中的 445 项跳过保留在报告中；最终直接相关验证另有上述 1 项数据库测试跳过。
- 本机为 macOS，未验收 Linux 绑核 / cgroup、C/C++ Driver 和 QoS 配置。
- 未执行三成员滚动升级、旧版本生产磁盘目录原地迁移、长时间故障演练或正式 JMH / JFR 压测；没有项目吞吐、时延或性能提升结论。
- 没有改变 HTTP / WebSocket、前后端字段或资金状态契约，本轮未启动前端、原生客户端或 wallet。

## 证据归档与清理

本机证据目录：`/Users/atomex/.codex/artifacts/aeron-1.54.0-20261028`。保留两轮基线、原始 Maven 日志、完整测试参数、XML 报告 ZIP、无 Aeron 的既有失败复现源码、制品校验结果和 `summary.json`。归档后删除本轮独立源码 / 构建目录及复现编译目录；保留正常 Maven 缓存，不清理其他任务资源。
