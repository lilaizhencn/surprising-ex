# Aeron 客户端与实时传输

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

验证：HotSpot JDK 25，`mvn -pl surprising-aeron-core/surprising-aeron-client -am test`。
测试覆盖协议截断/越界、全部产品线和消息类型、完整回调发布、撤销、总内存额度、
跨线程可见性，以及真实 Media Driver 上的大消息分片重组和目标 stream 隔离。
当前测试不构成完整交易主链路性能验收。
