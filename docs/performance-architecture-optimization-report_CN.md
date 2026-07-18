# 永续合约后端性能与架构优化报告

## 结论

本轮八项改造已经完成。最终边界是：

- account-provider 是余额、保证金、持仓、亏空和资金流水的唯一写者；
- order、trigger、matching、risk、funding、liquidation、insurance、ADL 只处理各自业务事实，
  需要资金变更时发送按 `productLine + userId` 分区的账户命令；
- PostgreSQL 是资金和订单状态权威源，Redis 只承接可重建查询投影和候选索引；
- 跨模块一致性使用本地事务 + transactional outbox + Kafka 至少一次投递 + 消费端幂等，
  不使用 XA；
- 高频链路优先使用批量读取、JDBC batch、集合更新、keyset 分页和用户级串行，避免逐行网络往返与共享热点行。

## 八项完成情况

| 项目 | 完成后的实现 | 一致性保护 |
|---|---|---|
| 1. 持仓 Redis 投影可靠化 | 持仓变更在 account 本地事务中写完整、带 revision、按用户分区的持久化事件；独立消费者更新 Redis，不再依赖提交后的进程内回调作为唯一链路 | Redis 使用 revision CAS；乱序/重复事件不会覆盖新值；失败可由 outbox/Kafka 重放 |
| 2. 账户结算热点消除 | taker/maker 分别写不可变 settlement side，不再提前竞争共享成交行；未变化 deficit 不更新；持仓与 64 分片 OI 使用一条数据修改 CTE | 每侧资金事务独立幂等；双边完成由只读视图判断；所有资金更新继续检查影响行数与非负约束 |
| 3. 表所有权回归业务模块 | account 不再直接修改普通订单、reduce-only 订单和条件单；order-provider 与 trigger-provider 消费持仓事件后，在自己的事务中完成清理与 outbox | 精确使用 `productLine + userId + symbol + marginMode + positionSide`，并用事件时间边界保护新建订单 |
| 4. Outbox 发布去串行等待 | trigger 与 liquidation outbox 支持有界并发发送、按 key 保序、批量成功确认和租约恢复 | 同 key 只允许最早事件发布；失败不越过前序事件；发布标记仍由数据库条件更新保护 |
| 5. 风险扫描合并 | risk 消费 Kafka 批次后按用户/产品范围合并重复持仓事件，一次读取同账户持仓并生成统一快照，不再对批次中每条事件重复全账户扫描 | 快照租约、唯一键和版本检查保留；平仓事件仍生成零仓位快照，避免陈旧风险状态 |
| 6. 资金费分页与短事务 | 结算按稳定 keyset 分页读取持仓；payment id 批量分配；payment 与账户命令 outbox 批量落库；结果按批处理并由 reconciliation 收敛 | settlement/payment/command 均有幂等键；`PROCESSING -> WAITING_ACCOUNTS -> COMPLETED/FAILED` 状态机保留 |
| 7. 撮合持久化批量化 | 成交 ID 改为 `commandId * 1_000_000 + matchIndex`；maker 快照一次有界查询；成交 JDBC batch；maker 成交量一条集合更新；资金命令和 match result 一批 outbox 写入 | command result 是重放门；trade-id 冲突、少写成交、maker 条件更新数量不一致时整笔事务失败并触发 matcher 重启恢复 |
| 8. 订单投影与资金边界去 N+1 | 开放订单投影合并 order/match Kafka 批次，去重 taker/maker orderId 后一次批量查库；账户 reservation 保存订单总量与 `reduceOnly`，成交/释放命令携带相同快照，account 成交热路径不再查询 `trading_orders` | account 对产品线、账户类型、用户、订单总量、`reduceOnly` 全部校验；非 reduce-only 缺 reservation、快照不一致或用户错配时事务回滚 |

## 高频链路变化

### 撮合

单个 command 产生 `M` 笔 fill 时，旧实现包含逐笔 maker 查询、逐笔成交插入、逐笔 maker 更新和逐条
outbox 分配/插入，数据库往返随 `M` 线性增加。现在主链由以下有界操作组成：

1. 一次 command/result 状态检查；
2. 每 1000 个唯一 maker 一次快照查询；
3. 一次成交 JDBC batch；
4. 一次 maker 集合条件更新；
5. 一次 outbox id 批量分配和一次 JDBC batch。

业务行数仍然随成交数增长，但数据库网络往返从逐 fill 的 `O(M)` 降为有界批次。

### 资金费

不再把全平台持仓、payment、账户命令和结算结果放入一个长事务。每页都有稳定游标和上限，
失败只重试当前页；账户命令按用户并行，最终状态由数据库 reconciliation 收敛。

### 风险

同一 Kafka poll 内，同一用户的多条持仓变化只触发一次账户级风险计算。热点用户快速成交时，
扫描次数由“事件数”降为“受影响用户数”。

### Redis 查询投影

持仓和开放订单均为可重建投影。普通用户查询优先走 Redis；缓存未 ready、数据不完整或 Redis
异常时安全回退 PostgreSQL。Redis 从不决定余额、保证金、成交、撤单或强平。

## 内部做市边界

内部做市白名单逻辑保持简单：

- 双方都是内部做市账户的自成交继续产生公共成交、盘口、K 线和 WebSocket 行情；
- 该类自成交不写经济成交表，不发送双边资金结算命令，不形成持仓、手续费、盈亏和资金流水；
- 做市账户与真实用户成交时，完整进入普通成交、资金和持仓结算；
- 自成交仍更新订单剩余量，并释放对应订单冻结，避免恢复陈旧订单或长期占用保证金。

## 验证结果

已完成：

- 根项目执行 `mvn clean test`：**1282 tests，0 failures，0 errors，0 skipped**；
- matching、order、account 三个核心模块执行干净全量测试通过；
- funding-provider 全量测试通过；
- 根项目执行 `mvn -DskipTests package` 打包通过；
- 永续合约启动、API flow、资金核对和在线 reconciliation 脚本通过 shell 语法检查；
- Git 工作树干净，全部提交已推送到 `origin/master`。

当前机器没有 Docker、PostgreSQL、Kafka、Redis 和 `psql` 客户端，因此没有伪造运行级结果：
依赖真实中间件的永续合约 API smoke、资金守恒核对和 TPS/p99 压测需要在部署测试环境执行。

## 上线前压测与验收

压测不能只看 TPS，至少同时记录：

- 下单、撤单、成交结算的 p50/p95/p99；
- matching command lag、account user-command lag、最老 outbox 年龄；
- PostgreSQL TPS、锁等待、deadlock、连接池等待、WAL 和慢 SQL；
- Redis 命中率、回退数据库比例、命令 p99、主从复制延迟；
- Kafka produce/fetch p99、consumer lag、ISR 变化；
- JVM GC pause、分配速率、CPU、直接内存；
- 每轮压测后的余额 + 持仓保证金 + 亏空 + 手续费 + 资金费 + 保险/ADL 逐项守恒。

建议按以下顺序确定容量：

1. 关闭做市流量，测真实用户下单/撤单/成交基线；
2. 增加内部做市自成交，确认行情吞吐上升但资金/数据库写入没有同比增加；
3. 加入资金费、强平、ADL 和 Redis 重建并发场景；
4. 注入 Kafka 延迟、Redis 故障、PostgreSQL 主备切换，验证分区阻塞、幂等重放和资金守恒；
5. 以 p99、最老 backlog 年龄和资金核对同时达标的最高稳定吞吐作为生产容量，不使用瞬时峰值。

相关脚本：

```bash
scripts/product-line-api-flow-smoke.sh
scripts/product-line-funds-reconcile.sh
scripts/live-runtime-trading-reconciliation.sh
scripts/check-account-single-writer.sh
```

## 提交记录

- `f276d74` `refactor(account): make position projection durable`
- `7903be2` `perf(account): remove settlement row hotspots`
- `38547bc` `refactor(trading): move position cleanup to table owners`
- `7456781` `perf(outbox): parallelize trigger and liquidation publishing`
- `c23e30e` `perf(risk): coalesce position event scans`
- `9004b14` `perf(funding): page settlement dispatches`
- `f145484` `perf(trading): batch matching persistence`
