# 算法委托查询与成交累计（2026-10-20）

本轮补齐现货算法委托查询的后端前提；移动端现货 TWAP/冰山入口及交互仍需后续完成，不能据此认定算法委托功能已全部验收。

## 查询链路

`GET /api/v1/gateway/trading/algo/open` 保持原有认证、用户归属、参数和响应契约。`AlgoOrderService.openOrders` 改由 `AlgoOrderProjectionRepository` 读取 realtime 写入的 PostgreSQL 投影，不向在线 Core 查询。产品线、用户、合约和非终态条件在 SQL limit 前过滤，按创建时间和算法委托号倒序。委托号和交易整数沿用既有字符串响应。

`CommittedTradeReplay` 仅在独立 Archive 回放副本中读取父单视图；它保存子单到父单的查询路由，收到父单意图更新或子单成交/撤销时导出最新状态。父单终态后删除子单路由。成交可以改变累计数量而不增加父单 revision，因此数据库同时使用提交导出序号判新旧，不能只比较 revision。

`CommittedTradeExporter.ExportBatch` 将算法单变化纳入已有有界批次。`CommittedOrderProjectionRepository.persist` 在同一事务中写入普通订单、命令回执、算法单和可见性水位；SQL 或 Kafka 失败不清空待提交批次、不推进检查点。`CommittedAlgoProjection` 承担算法单 SQL 持久化和初始化边界，没有另起导出线程或检查点。

第一次启动时，从已有恢复点的回放状态分批写入保留的父单，全部成功后才写初始化标记；失败可重新执行。标记未就绪返回 503 `ALGO_READ_VIEW_INITIALIZING`，存储不可用或状态损坏同样明确返回 503，不回查 Core。已有标记后继续使用正常已提交日志增量。

## Core 交易状态

普通子单成交或撤销后会从活动订单中回收，原先通过历史子单逐个求和会遇到 `algo child order missing`。`MatcherSettlementChanges` 现在在回收前，交由同一 Account Lane 的 `RuntimeAlgoOrderStateTransitions.retireChild` 累加最终成交量，并通过已有 `LaneCommitDelta` 发布变更。

父单只新增两个权威标量：已回收子单累计成交数量，以及“首个子单已终结但 gateway 尚未关联”的子单 ID；不保留整份终态子单历史。查询累计为已回收数量加仍活动子单的成交量，后续调度意图不能覆盖累计值。已取消请求不能退回运行，终态父单拒绝后续调度更新，避免迟到更新重新启动父单。

两字段进入业务哈希和快照。交易状态格式仍为 37，reader revision 升为 4；任一字段非零时最低 reader 为 4。旧的、包含算法单但缺少新必需字段的快照不能直接当作新状态使用；升级前应明确检查恢复点及可重放日志覆盖，不能删除 live 检查点后猜测重建。本轮没有在既有生产数据上执行升级。

重启实测还暴露了本地调度与撤销同时更新父单 revision 的冲突。撤销现与既有同步调度共用服务监视器，等待本机在途子单完成关联后，重新读取权威父单再提交取消。子单撤销异常后必须再查权威活动数量：仍有活动子单则保留 CANCEL_REQUESTED 并传播异常，不会提前写成 CANCELED；子单确已全部终结才收敛父单。用户的只读 SQL 查询不获取该监视器。

交易控制和调度的内部权威查询继续留在交易链路。此次迁移的是用户查询入口，不改变 Core 的交易裁决职责或引入 Core 到数据库的网络访问。

## 发布与验证边界

先执行 `deployment/migrations/20261020-algo-read-projection.sql`，再配套更新 Core、realtime 和 gateway。新环境的 `init.sql` 已含同样两张表。等待对应产品初始化完成后开放查询；不要清零或替换原 live 检查点。

本轮单产品实栈为 SPOT、单节点 FUSED，保持真实做市，不启动 wallet。覆盖冰山撤销、买卖 TWAP 子单成交、父单完成、终态过滤、越权拒绝，以及暂停在线 Core 时查询仍成功；同时核对用户和做市冻结归零及资金守恒。共享 Core 状态另做六产品快照、资金幂等及提交变更回归，未启动其他产品的实栈。[逐项结果与清理记录](validation/spot-algo-read-projection-20261020.json)。

扩大回归发现固定历史快照的资金命令重试返回 `IDEMPOTENCY_CONFLICT`，六产品的 `PublishedSnapshotRecoveryTest` 均失败。将本轮改动类替换为提交前版本、在独立临时输出中执行相同输入，结果完全一致；再以 `a7771604e` 之前的 SHA-256 指纹计算器作对照，六产品均返回 DUPLICATE 且状态哈希不变，定位到此前指纹算法替换与固定样本的冲突。此对照未改动工作区源码或 target。本轮保留固定样本和失败断言，没有重写历史指纹或降低幂等校验。该缺口尚未修复，不能宣称历史快照升级验收通过。

移动端现货算法单列表、数量精度、原请求恢复和取消中状态仍需补齐；本轮验证单 gateway 的调度/撤销序列，多 gateway 的在途子单关联竞态尚未验收。本轮不代表原生设备、生产升级或全部产品验收。
