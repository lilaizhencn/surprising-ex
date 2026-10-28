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

## 请求与响应生命周期

`Session.drainResponses` 批量消费响应，按关联号完成在途请求。池化连接由 `EgressDispatcher` 绑定 I/O 线程，顺序执行 offer、轮询、心跳与关闭；独立客户端在监视器内串行访问 Cluster。

`AeronClientPool.Request` 用代数与原子状态控制发送、取消和完成。旧代数不能取消已复用的槽；完成者清理引用后归还空闲槽。`ADMIN_ACTION` 保留发送状态及原来源序号，资金命令结果未知时不能自动重发。

`AsyncConnection` 的建连期限与业务响应期限独立。建连期限通过 `surprising.aeron.client.connect-timeout-ms` 配置；不能用反复重建连接代替处理业务超时。

## 实时传输与关闭

`AeronRealtimeReceiver` 发生传输错误后退出本轮并关闭旧订阅，再重新连接。发送与接收组件使用 Aeron Driver 超时配置；正常关闭会唤醒或中断等待，并释放自己拥有的资源。

`RealtimeOutbox.commit` 只发布完整回调；消费者登记等待线程后再次检查队列，避免丢失唤醒。命令 Dispatcher 在没有工作时退避，有工作、心跳或建连期限时按相应预算推进。

## 验证

选择受影响连接、租约、预算或实时传输用例：

```bash
mvn -pl surprising-aeron-core/surprising-aeron-client -am test \
  -Dtest=<相关测试类> -Dsurefire.failIfNoSpecifiedTests=false
```

真实 Media Driver 传输测试与业务交易、资金结算和完整 Cluster 验收分别判断；历史采样数值不作为当前源码的性能结论。
