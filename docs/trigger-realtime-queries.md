# 条件单查询归属 realtime

用户单笔查询 `/api/v1/gateway/trading-trigger/{id}?userId=...`，以及后台
`/api/v1/admin/gateway/trading-trigger` 的列表、详情、时间线，统一读取
`core_trigger_order_projection`。列表按创建时间和完整 64 位条件单 ID 排序，支持
`createdAt.desc` / `createdAt.asc`；游标绑定产品、用户、合约、状态、ID 和排序，换筛选必须从首页开始。
用户读取仍校验用户归属，后台读取仍经过原有权限与产品路由。

`TriggerOrderService` 负责入口校验和响应转换；`TriggerOrderProjectionRepository`
负责有界 SQL 读取、游标和原始状态一致性检查。活动条件单列表与私有推送继续使用
`ValkeyUserQueries` 的版本化活动视图。终态从活动视图移除后，单笔查询仍可返回已撤销、
已触发、触发失败或已过期状态。时间线由该记录的生命周期字段派生，并非另建审计事件库；
触发事件名使用实际 MARK / LAST / INDEX 来源。

`CommittedTradeReplay` 在 realtime 的独立已提交重放状态中捕获 TRIGGER 绝对状态。
`CommittedOrderProjectionRepository.persist` 将条件单、订单、命令回执与订单投影水位放在同一事务，
只接受更高 revision，相同 revision 的不同内容或不可变身份冲突会回滚。该投影是可重建查询副本，
不参与撮合、冻结、持仓或结算。Core 保留条件单提交、撤销、触发、到期、维护和交易准入所需的控制查询。

## 部署与重建

1. 在各产品使用的数据库应用 `deployment/migrations/20261020-trigger-read-projection.sql`；新库已包含在 `init.sql`。
2. 部署匹配的 Core、realtime 与 gateway。无需改变命令或实时帧的协议格式。
3. realtime 启动时若没有 `core_trigger_projection_initialization` 中的产品记录，
   从自身导出检查点中的保留条件单状态每页最多 512 条重建。分页使用现有 ID 索引的范围视图，
   不扫描在线 Core，也不重置实时导出检查点。全部页面完成后才写入初始化标记，然后继续消费已提交 Archive。
4. 任一页面失败，已写入部分仍保持隐藏；重试根据 revision 幂等续建。
   初始化标记缺失返回 503 `TRIGGER_READ_VIEW_INITIALIZING`，数据库不可用或记录不一致同样返回明确 503。
   禁止回退到 Core 查询或伪装成空列表。投影是已提交前缀，允许正常导出延迟；不声称同步读取最新交易状态。

检查点重建覆盖检查点中仍保留的条件单；其后的状态来自已提交日志。已有部署升级前先保留完整检查点及
所需 Archive，并按常规备份流程迁移。不要删除在线检查点、重放交易命令或手工填充初始化标记。
若需要重建 SQL 表，应先停止对应产品的 realtime，保留检查点，并一并清除该产品的初始化标记，
再启动 realtime；其他产品和订单投影水位不应改动。

## 做市账户的保护单回执

原先做市策略账户的所有命令回执都被过滤，导致人工创建的 OCO 查询不到回执。
现在保留 `PLACE_TRIGGER_ORDER`、`PLACE_TRIGGER_OCO_PAIR`、`CANCEL_TRIGGER_ORDER`，
常规高频报价回执继续按现有规则过滤。历史缺失回执可使用已有
`CommittedCommandResultRepair`：显式选产品、有限 Archive 终点、独立 `.command-repair` 检查点，
只回补回执，不写交易/条件单投影水位，不发布 Kafka，也不再次提交订单。
本次没有扩大为做市账户所有人工普通单回执的保留策略。
过滤器仍读取现有 SQL 账户目录；当前 maker 的公共设置与动态策略保存在进程内，
目录与运行时配置的同步不在本轮修复范围。SQL 目录过滤例外由 PostgreSQL 回归覆盖；
真实做市 OCO 读取验证不作为高频报价过滤已在运行时生效的证明。

本轮后台还发现交易统计、全局成交列表的旧地址返回 404，尚需补齐 realtime 读取。
后台页面现分别处理每项结果，明确显示失败原因，避免这两项失败阻断已可用的条件单列表。

本轮实测与清理记录见 `docs/validation/trigger-realtime-queries-20261020.json`。
