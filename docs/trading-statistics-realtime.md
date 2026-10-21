# realtime 交易统计

后台读取 `/api/v1/admin/gateway/trading-orders/metrics?productLine=OPTION&windowMinutes=1440&limit=20`。
沿用 `TradingLocalRoutes` → `AdminOrderRequestService` → `OrderService` 的鉴权、参数和产品校验，
`TradingMetricsRepository` 只读取 realtime 发布的完整统计 JSON。缺少快照返回
`TRADING_METRICS_INITIALIZING`，超过 180 秒返回 `TRADING_METRICS_STALE`，记录损坏或数据库故障也返回 503。
查询不调用在线 Core，不用空值补成零；切换产品/窗口、刷新失败都会清除旧卡片。

## 统计口径

- **账户命令**：已进入 Core 提交日志、账户 ID 大于零的命令；含订单、算法、条件单、余额和持仓设置。
  批量按一条命令、按整条命令结果统计，不等同于订单笔数或每项批处理结果。网关拒绝且未进入日志的不包含。
  同一 commandId 在不同日志位置的重试是不同尝试；相同日志位置的重放不重复计数。
  冲突重试计为拒绝，但不能覆盖原始命令回执；冲突输入即使格式损坏也不会阻断重放。
- **下单命令**：上述命令中 `PLACE_ORDER`、`PLACE_ORDER_BATCH` 的计数、拒绝率及不同账户数。
- **成交**：窗口内已提交撮合笔数、双方不同账户及末笔成交时间，复用 `core_execution_projection`。
  同一撮合只计一次。没有把不同合约的 `priceTicks × quantitySteps` 相加当作币值。
- **当前委托、持仓、条件单**：从 realtime 自身的独立重放状态取完整当前状态，不依赖活动订阅或已过滤的订单查询表。
  持仓数量按合约和多/空分别列出，跨账户汇总用 BigInteger。每个账户在全局持仓账户数中只计一次，零仓位不计。
  没有为缺失的持仓更新时间伪造时间戳；页面显示采样时间和已提交日志位置。
- **窗口**：支持 60、360、1440、10080 分钟，起点按整分钟向下取整，包含本分钟已提交部分，实际起点随响应返回。
  命令分钟桶保留八天，覆盖最长七天窗口。合约排名最多取前 100，接口 limit 仅裁剪排名行，不改变全局计数。

## 状态所有权和恢复

`CommittedTradingActivity.capture` 仅由独立 `CommittedTradeReplay` 在冷态读取边界调用，
借用现有 Account Lane 所有权后直接聚合本地 orders、positions、triggers，再归还。
不进入在线 matcher/settlement 热路径，不物化或缓存完整用户/订单视图，不新增 Core 协议。

`CommittedTradingStatisticsService` 归现有 `CommittedOrderProjectionService` 和导出线程所有。
分钟桶与订单、成交、回执和投影水位同事务更新，用独立命令日志游标保证幂等，不受做市回执过滤影响。
仓库通过方法提供该对象，确保 Spring 代理委派到实际初始化的目标，而非读取代理对象的未初始化字段。

默认每次导出检查点间隔（60 秒）发布四个窗口的采样。只在已追到当前 Archive 提交位置时采样；
若 SQL 命令游标因上轮崩溃领先本地恢复点，不把新计数拼到旧持仓上。四个窗口和原生当前状态作为一份前缀发布。
导出落后时保留旧采样，过期后明确不可用。正常异步延迟通过 generatedAt、dataAgeMs、asOfLogPosition 展示。
自定义检查点间隔应小于查询的 180 秒过期边界；本机联调使用 1 秒以缩短等待，并非生产压测配置。

部署前应用 `deployment/migrations/20261020-trading-statistics.sql`，新库已包含在 `init.sql`。
已有主检查点且尚未初始化统计时，导出线程使用独立 `.statistics-repair` 检查点从完整 Archive 起点补齐到主检查点，
只更新统计桶/游标，不发布 Kafka，不重发交易，也不写订单/成交/回执水位。完成后标记初始化并删除独立补齐检查点。
计划重建时停止对应产品 realtime，保留主检查点与完整 Archive，同时清除该产品的统计桶、统计游标、初始化标记和采样；
未完成的补齐检查点必须与其 SQL 进度一起处理。禁止只删除计数而保留游标。

本轮验证记录见 `docs/validation/trading-statistics-realtime-20261020.json`。
