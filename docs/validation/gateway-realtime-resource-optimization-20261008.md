# Gateway 与实时行情资源优化验证

代码提交：`8a8ce3cb89a613da12e362f3914ae95a0bec8b1b`。本轮完成本地实现、验证和推送，没有部署测试服务器。

## 改动与所有权

每条产品线的 `ProductBusinessConfiguration.productCommandMediaDriver` 持有一个命令驱动，账户、订单、条件单和维护池借用它。四个池保留独立来源、会话、邮箱和容量，池关闭只释放自己的会话，产品容器最后关闭驱动。不同产品线仍使用不同驱动；实时应用驱动继续独立。单产品 Gateway 因而从五个驱动变为两个，不增加部署配置。

`RealtimeFrameCodec` 直接写入最终消息的 UTF-8 区域，解码复用枚举常量并直接读取数组中的文本。共享文本写入方法增加独立长度上限参数，保留 Core 查询的 64 字节限制和行情的 128/256 字节限制。v1 字节格式和 payload 防御复制不变。

`ValkeyReadViewStore.deltaCommand` 直接生成 Redis 参数字节及 Base64 字节；`RealtimeVersion.bytes` 生成固定 19 位日志位置、冒号和 10 位 ordinal，去掉 Formatter、数值装箱和中间版本字符串。每用户每提交的 Lua 原子性、顺序及快照 fence 不变。`RealtimeRouter` 线程独占并复用一个最多 256 帧的批次列表，每轮在 finally 清除引用。

新增持久状态只有驱动所有权标记及私有枚举常量数组；复用列表只属于路由线程。没有新增业务状态副本、注册框架或公共接口层。

## 功能验证

HotSpot Corretto 27+33-FR、Maven 3.9.16，最终 **286 项通过，0 失败，0 跳过**。使用独立源码目录，排除工作区其他未提交的业务改动。期间 master 合入的空闲退避等提交在 `243158a8` 基础上重新验证；本次共享文件按独立源码内容提交，保留他人的工作区修改。

- 完整 protocol/client 测试：所有产品线、消息类型、UTF-8、空文本、畸形代理字符、长度边界、1 MiB 消息上限、payload 隔离、并发、背压、关闭和真实分片传输。
- 六产品真实单节点 Core：每条产品线四个独立命令来源共用驱动，并发充值、跨来源重复 commandId、关闭一个池后其他池继续查询。每产品期初 0、四笔各 100 units、幂等重试增量 0、期末 400、冻结 0、持仓为空。临时 Core 逐一停止。
- 六产品容器注入、驱动销毁顺序和目录释放；现有跨产品划转、结果丢失后的快照恢复、成交和衍生品风险状态机检查。
- 真实 Redis Lua、版本排序、快照边界、完整提交顺序；真实 Aeron UDP 订阅、私有用户隔离、缺口快照修复、Gateway 驱动重启重连。

主命令如下，逐类结果和本轮实际命令保存在 JSON：

```bash
mvn -pl surprising-aeron-core/surprising-aeron-client -am test
mvn -pl surprising-gateway,surprising-realtime/surprising-realtime-provider,surprising-aeron-core/surprising-aeron-benchmarks -am \
  -Dgateway.shared-driver.it=true \
  '-Dtest=*Realtime*,*Valkey*,*GatewayMediaDriver*,*ProductCommandMediaDriver*,*SharedCommandTransportIntegration*,*ProductBusinessConfiguration*,*MultiProductMoney*,*AccountAeronGateway*,*OrderAeronGateway*,*AeronOrderCommandService*,*CorePerpetualEndToEndBenchmark*,*DerivativeRiskBoundaryBenchmark*,*SettlementSolvencyBenchmark*,*ArchiveReplay*,*MarketApplicationContext*' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

初轮黄金向量检查发现共享文本方法不能直接用于空文本和较长行情字段，修正后重跑通过。测试断言 API 和临时 Core 的 `java.util.zip` JVM 开放参数问题也已修正。初轮另选的 10 项维护数据库测试因没有专用测试库而跳过，本次没有修改维护 SQL；最终选择范围没有跳过。没有启动 wallet，也没有操作真实用户资金。

## 本地资源测量

Intel Core i9 MacBookPro16,1，16 逻辑 CPU、16 GiB RAM，macOS 26.7.2。独立无采样吞吐、`-prof gc`、JFR 三类轮次；JMH 1.37，512 MiB ZGC，5 × 1 秒预热、5 × 1 秒测量。吞吐 2 forks，GC/JFR 各 1 fork。最后一轮没有 swap-in、swap-out 或 page-out 增量。同机是开发工作站，4096 字节 Redis 场景波动较大，不作容量验收。

当前同一个驱动工厂的四驱动/单驱动配置探针，分别稳定 2 秒后观察 10 秒：

| 配置 | 驱动工作线程 | 新线程总数（含 Cleaner） | 目录逻辑字节 | 空闲线程 CPU（单核百分比） |
|---|---:|---:|---:|---:|
| 四个 SHARED 驱动 | 4 | 5 | 197,148,672 | 9.146% |
| 一个 SHARED 驱动 | 1 | 2 | 49,287,168 | 3.386% |

目录逻辑大小减少约 141 MiB；这不是 RSS、物理占用或 NMT 的节省量。此探针没有交易负载，不能外推整个 Gateway 的 CPU 降幅。

最终微基准主吞吐及独立 GC profiler 的分配量：

| 路径 | payload 字节 | 无采样 ops/s（JMH 误差） | B/op |
|---|---:|---:|---:|
| encode | 128 | 15,050,991 ± 454,900 | 280.00 |
| encode | 4096 | 2,352,504 ± 188,075 | 4,248.00 |
| decode | 128 | 14,262,262 ± 781,664 | 472.00 |
| decode | 4096 | 1,255,247 ± 66,844 | 8,408.01 |
| Redis command | 128 | 4,040,485 ± 328,559 | 1,008.00 |
| Redis command | 4096 | 765,839 ± 150,611 | 10,264.01 |

Codec 的初轮与最终编译字节码 SHA-256 相同，保留已有有效测量，不重复采集。Redis 版本号优化后单独复测。诊断过程中曾观察到 Formatter 主导分配，相关初轮数据仅解释本次定位过程，没有签出旧分支/tag 或重新跑历史部署版本。

实际客户端 admission/dispatch 微基准为 1,362,084 ± 47,345 ops/s，使用即时确定性 Session，不含网络/Core：4 个线程各最多 64 个请求，**全局窗口 256**。两个 fork 的 offered/terminal 分别为 13,040,256/13,040,256 和 13,326,400/13,326,400，unfinished 均为 0；这些是包含预热的完成性计数，吞吐取正式测量样本。

JFR 最终轮 10 秒、profile、24 MiB 上限，DataLoss=0，3,045 个 ObjectAllocationSample、32 个 ThreadAllocationStatistics；TLAB/非 TLAB 精确分配事件未启用。Formatter 已不再出现在主要分配路径。采样权重不能当作精确对象总数；B/op 使用 GC profiler。最终录制观察到 306 次 GC phase pause，最大 0.0680ms；这不是业务请求尾延迟。

初次归因录制的 CLI `jvmArgsAppend` 覆盖了注解参数，实际为 G1/default heap，仅用于定位 Formatter，不能与最终 ZGC 比较 GC 指标。最终录制明确补齐 512 MiB ZGC 参数。两次录制的原始路径、大小、SHA-256、完整 summary、分配及 GC 聚合均保存到 JSON。

## 结论和清理

功能验证通过；资源验证为局部验证，确认减少驱动和临时分配。未做完整 HTTP/Kafka/做市压力、线上资源复测、NMT 或长稳，不能据此承诺整机 CPU 降幅、交易 p99、容量或无泄漏。服务器尚未部署本轮代码。

汇总见 [可复核 JSON](gateway-realtime-resource-optimization-20261008.json)。清理已完成：本轮独立源码、构建、Core/驱动数据、日志和两次 JFR 录制均已删除，汇总保留命令、原始校验及聚合结果；确认没有本轮测试进程存活。已有服务及他人工作区产物保持不变。
