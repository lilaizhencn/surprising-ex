# 用户持仓 JVM 与 Redis 读模型

## 目标与边界

用户侧的 `GET /api/v1/accounts/position`、`/positions` 和 `/position-margin` 优先读取账户模块 JVM
快照，避免高频持仓查询访问数据库。Redis 只作为跨节点查询和恢复投影；账户用户分区 WAL/RocksDB
才是余额、持仓和保证金的交易事实源。订单校验、成交结算、资金费、风控和强平不能把 Redis 或
数据库投影当作正确性锁。

这不是“Redis 取代账户事实流”，而是把账户快照投影成可跨节点读取的用户读模型。管理员查询可以
读取数据库异步投影，但必须显示投影修订号和延迟，便于审计和排障。

## 键和产品线隔离

每个用户、每条产品线使用同一个 Redis Cluster hash tag：

```text
surprising:position:v1:{LINEAR_PERPETUAL:1001}:state
surprising:position:v1:{LINEAR_PERPETUAL:1001}:margin
surprising:position:v1:{LINEAR_PERPETUAL:1001}:revision
```

Hash field 固定为 `SYMBOL|MARGIN_MODE|POSITION_SIDE`。产品线必须同时存在于 key 和快照中，不能跨线
读取或写入。平仓后的零仓位保留 revision 和已实现盈亏，防止延迟旧事件复活已关闭仓位。

## 一致性设计

账户 reducer 按 `LINEAR_PERPETUAL:userId` 单写入，账户 revision 在本地状态中单调递增：

```text
账户用户分区命令
  -> 本地 WAL 按序追加
  -> RocksDB reducer 原子提交余额、持仓、保证金和 revision
  -> Kafka 发布完整账户快照
  -> 各模块 JVM 快照按 revision 幂等更新
  -> 独立消费者异步替换数据库和 Redis 投影
```

重复或乱序快照只接受更大的 revision；发现版本间隙、事件指纹冲突或快照损坏时，相关用户分区保持
未就绪。资金费和 ADL 不直接更新保证金或持仓，而是通过账户指令由 account-provider 统一写入。

WAL/RocksDB 是耐久事实，数据库和 Redis 都是可重建投影。投影失败只会停止投影水位推进和对外读模型，
不会改变资金裁决，也不能回退到数据库猜测零仓位。

## 启动、恢复和失败策略

1. 账户服务启动后先从本地 WAL/状态库恢复；没有本地快照时，必须通过账户内部快照 RPC 初始化，不能
   用数据库余额临时填充。
2. JVM 快照消费者使用产品线隔离的 Kafka topic 和消费组，按 revision 丢弃重复、乱序消息；重启时
   从最新快照位置恢复，不使用过期行情或旧持仓事件。
3. Redis 投影没有 readiness marker、数据不完整或写入失败时，查询返回未就绪；不把空集合解释成没有
   持仓，也不允许其参与下单、撮合、风控或强平裁决。

## 运维与测试

Redis 只能启用持久化和 `noeviction`，用于跨节点读模型和协调。测试应先启动 instrument，再只启动一个
产品线，验证并发下单、成交、资金费、强平、重复事件、进程重启和逐项资金守恒。所有模块的本地快照
revision、Kafka lag、投影水位和未就绪原因都应纳入监控。
