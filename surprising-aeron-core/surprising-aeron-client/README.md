# Aeron 客户端与实时传输

应用侧 `SurprisingAeronClient` / `AeronClientPool` 内嵌 MediaDriver 默认使用 `SHARED`，
将 conductor、sender、receiver 合并到共享驱动线程，减少小型主机上的平台线程和调度竞争。
需要独立线程时可设置 JVM 属性 `surprising.aeron.client.threading-mode=DEDICATED` 或环境变量
`AERON_CLIENT_THREADING_MODE=DEDICATED`，JVM 属性优先；非法模式启动失败。
各产品线和各命令池仍独立，此默认值不修改 Core/Archive 的 Driver 模式，也不合并命令执行通道。

Gateway 的每个产品业务容器创建一个命令 MediaDriver，账户、订单、条件单和维护池通过
`AeronClientPool(..., MediaDriver)` 借用该驱动，各自保留 source identity、会话、邮箱及背压额度。
连接池只关闭自己的会话；驱动由产品容器在所有连接池关闭后销毁。独立应用未传入驱动时仍自行持有和关闭驱动。
实时推送使用原有应用驱动，与命令传输分开；单产品 Gateway 因此从五个驱动减为两个，无需新增部署参数。

`AeronRealtimeSender`、`AeronRealtimeReceiver` 是独立于 Cluster 命令客户端的实时传输组件。
发送线程独占 Publication；接收线程使用 FragmentAssembler 重组消息并在回调内复制数据。
业务线程只写 `RealtimeOutbox`，不调用 Aeron、Kafka、Valkey 或 socket。

`RealtimeOutbox` 仅支持一个生产者与一个消费者。容量同时受消息槽位和总字节数限制；
`begin/stage/commit` 将一个完整业务回调交给发送线程，提交前消费者不可见。
槽位或字节额度不足时丢弃整个回调，`abort` 清除暂存数据。网络出口不重试业务事件。
这是可丢失实时出口，不可作为可靠 Kafka 事件源或持久化日志。

内部协议为 `RealtimeFrameCodec` v1，单消息上限 1 MiB；每条消息携带产品线、用户、
Cluster log position、回调内 ordinal、事件时间、快照标识和实体标识。
`COMMIT_BEGIN/END` 和 `SNAPSHOT_BEGIN/END` 用于接收端检查完整批次；
接收端必须验证连续 ordinal，不能把部分批次当作完整状态。
价格等独立生产者的 sequence 属于自身来源，不能与 Core log position 混合比较。

验证：HotSpot JDK 27，`mvn -pl surprising-aeron-core/surprising-aeron-client -am test`。
测试覆盖协议截断/越界、全部产品线和消息类型、完整回调发布、撤销、总内存额度、
跨线程可见性，以及真实 Media Driver 上的大消息分片重组和目标 stream 隔离。
当前测试不构成完整交易主链路性能验收。

### 行情订阅异常恢复

`AeronRealtimeReceiver` 收到传输错误后标记不可用，当前轮询必须退出并关闭旧订阅，再重新连接。
即使 Driver 尚未关闭，也不能继续轮询一个已被错误处理器标记失效的订阅。错误原因会记录到日志。
`AeronRealtimeTransportTest` 使用真实 Driver 覆盖传输错误后的重连、Driver 重启恢复和分片消息交付。

发送端和接收端沿用 Aeron 的 `aeron.driver.timeout`（默认 10 秒），不再写死 1 秒。
本地 100 用户测试捕获 `keepalive age=1003ms > timeout=1000ms` 的误断连；真实 Driver
测试使用 1.5 秒定时间隔检查健康传输不被误判，同时保留 Driver 丢失后的恢复断言。

### 连接池建连期限与业务请求期限

`SurprisingAeronClient.AsyncConnection` 的握手期限默认 30 秒，可用 `surprising.aeron.client.connect-timeout-ms` 设置；业务命令/查询继续使用原来的 responseTimeout。连接建立涉及 Driver 分配日志缓冲区和 Cluster 握手，不能因为一次 5 秒业务请求超时就不断重建连接资源。本地抓到连接池连续 NOT_CONNECTED、核心 Driver 忙于预触碰新缓冲区；此项只延长握手期限，不重试未知结果的资金命令、不放宽业务请求成功标准。


## 空闲等待优化（2026-10-08）

`RealtimeOutbox.commit` 在发布完整回调后唤醒唯一发送线程。消费者先登记等待线程再检查队列，避免提交与入睡交错时丢失唤醒；stage/abort 不唤醒或暴露未提交消息。发送端空队列最多等待 100ms，仍定期检查 Driver 健康，关闭时中断等待。

`AeronClientPool.EgressDispatcher` 没有排队或在途请求时，将等待从 100µs 逐步延长到 1ms；新请求沿用入队唤醒，在途响应保持 100µs 轮询，心跳与重连期限限制最长等待。`AeronRealtimeReceiver` 没有跨进程入队通知，采用 100–250µs 退避，收到消息后重置。未修改命令身份、未知结果处理、产品线或连接隔离。

HotSpot JDK 27 本地对比基线 `292e4e04`：10 个双 lane 客户端池，加一对真实 IPC Sender/Receiver；预热后用 ThreadMXBean 统计这 12 个线程的 2 秒 CPU 时间，另录制有大小上限的 JFR。200 条 IPC 消息逐条发送，间隔 10ms，在 JFR 录制之外统计发送至接收回调的延迟。

| 指标 | 优化前 | 优化后 |
| --- | ---: | ---: |
| 目标线程 2 秒 CPU 时间 | 1273.855ms | 229.707ms |
| JFR 中 dispatcher park 次数（合计） | 151737 | 16655 |
| JFR 中 sender park 次数 | 15167 | 20 |
| JFR 中 receiver park 次数 | 15163 | 6150 |
| IPC P50 | 276.0µs | 348.8µs |
| IPC P99 | 405.3µs | 529.4µs |

目标空闲线程 CPU 时间约减少 82%，代价是本组 IPC P99 增加约 0.124ms；这是组件采样，不是整台服务器 CPU 或真实交易吞吐结果。接收退避上限曾试验 1ms，因 P99 达约 1.50ms，最终收紧为 250µs。

客户端模块及依赖测试通过；新增覆盖提交唤醒、未提交不可见、跨线程顺序，相关池、超时、真实 Driver 重连和分片测试通过。原始 JFR、临时编译程序及日志仅用于本轮验证，汇总后清理。
