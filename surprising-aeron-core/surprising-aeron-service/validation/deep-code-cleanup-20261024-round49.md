# 第四十九轮：调账审计完成链与后续冗余扫描

基于 `4ee8e87a9e747c05b338f7f864b29c12c37e3e05`，修复 A154 已确认的生产审计写链缺口。累计156项解决；本轮继续确认 A162–A164，另有 A11 候选和4项保留，全量审计未结束。

普通和管理员调账共享既有稳定命令身份。Gateway在提交Core前用REQUIRES_NEW事务保存首次来源及资金请求；相同资金请求重试不覆盖首次来源、管理员名称或原因，冲突则在offer前沿用AccountCommandRejectedException/IDEMPOTENCY_CONFLICT拒绝。普通来源也需保留请求身份，防止之后管理员重试冒认原操作；普通来源不保存没有读端的管理员原因、完成余额或时间。拒绝、未知结果和未offer的管理员请求均不伪造成功历史。

已提交回放从原执行前后可用加冻结总额提取精确现金差额和原事后总额，以原Cluster业务时间完成匹配的管理员请求。零调账用于建账，可产生一次审计确认；收益现金流水跳过零差额，拒绝和重复不生成新事实。后续查询得到的实时余额或当前墙钟不用于补造历史。直接Core操作没有请求行时不虚构发起者。

余额现金流水和审计完成通过现有PortfolioHistoryService进入原订单投影事务，与回执、强平、统计及水位原子提交；之后才发送Kafka、保存checkpoint。删除Exporter原来额外提交余额历史的步骤。PORTFOLIO_REPAIR复用同一服务，没有新增修复模式、完成标记或框架。

旧AdminBalanceAdjustmentRepository及无生产调用的record写入口删除。新account_balance_adjustments去掉重复reference_key/account_type列，按产品和稳定UUID识别命令，账户类型由产品与BASIC/PRODUCT种类提供；同时去掉阻止零金额建账的无用限制。表仍13列，各自消费如下：

| 字段 | 实际用途 |
| --- | --- |
| adjustment_id | 稳定分页次序及游标 |
| product_line、command_id | 产品隔离、请求与原已提交事实关联及重试身份 |
| adjustment_kind | BASIC/PRODUCT操作归属、查询过滤及账户类型推导 |
| admin_user_id、admin_username | 首次管理员身份及历史名称；普通来源为空 |
| user_id、asset、amount_units | 原资金请求、幂等冲突核对与审计展示 |
| reference_id、reason | 业务参考号过滤/展示、管理员原说明；普通来源无reason |
| balance_after_units、created_at | 仅管理员来源保存的原完成总额与原业务时间；未完成为空 |

原事后总额按NUMERIC(39,0)精确存储，避免可用加冻结超出long时截断。公开AdminBalanceAdjustmentRecord金额仍为long，读取使用longValueExact，越界明确失败。Cluster命令、在线Core状态、全部快照及版本均不变，仅冷回放事实新增一个有实际SQL读端的原事后总额。

首次编译发现一个测试mock漏迁移第7参数；修正后18模块通过。随后复核去掉普通来源无人消费的完成值写入，并保持原幂等拒绝类型及错误码，补充场景源码后重新执行：

```text
mvn -pl surprising-aeron-core/surprising-aeron-service,surprising-aeron-core/surprising-aeron-benchmarks,surprising-aeron-core/surprising-aeron-tools,surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-provider,surprising-gateway,surprising-realtime/surprising-realtime-provider -am -DskipTests clean test-compile
```

HotSpot JDK27、Maven3.9.16，18个reactor模块全部SUCCESS，退出码0，耗时65.507秒；日志SHA-256 `8e43412b5a8bafdaa0e5b76aecbee11343ab597dac7969a76ca7daa2385724c2`。22份变更Java及init.sql哈希与最终编译输入一致，4018份classfile均为major71，旧repository无定义或引用；新增事后总额有类型明确的生产调用。

新增/迁移场景源码涵盖首次来源、预写失败、调用方回滚、产品隔离、冲突、原余额/时间、零建账、重复、实际快照恢复、投影回滚与重试、宽总额及可变批次失败保留。这里只编译，没有执行这些场景。SQL建表与事务均未在数据库运行验证。

| 继续扫描确认项 | 证据及清理范围 |
| --- | --- |
| A162：内部逐项撤单resultCode | 仅一个测试读取；生产三个消费类只读orderId/accepted。可去掉字段、搬运与非空约束，保留整体失败码、成功前缀及公开批撤结果。 |
| A163：CommandSlot.reusableResolvedOrder | 每槽创建，仅清空；当前所有classfile无getter或外部字段读端。实际单笔事件和批量准入槽仍承担决策输入。 |
| A164：构造链laneCount | PendingMatchingRing只透传，CommandSlotRing只重复检查范围，不参与分配或路由。可去掉参数和虚假约束；真实LaneTopology、容量与完成掩码检查保留。 |

上述三项本轮只记录分析，尚未修改。另排除误判：Matcher序号/分片由OrderedCommitCoordinator通过MatchingResult接口消费；assetId直接参与Lane冻结；结算状态提供资金费/交割冲突判断；提交变更缓冲用于挂起/恢复时交换所有权。getter无直接调用不能独自证明字段无用。

仅执行相关编译及静态源码/classfile核对；未执行测试、服务、SQL、Aeron/Kafka、交易、恢复、UI或性能工具，不能声明全部功能正常或性能提升。完整证据和源文件锚点见[审计记录](command-data-audit-20261023.json)的forty_ninth_cleanup_round。
