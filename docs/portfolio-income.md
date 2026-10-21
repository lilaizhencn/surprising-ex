# 移动端资产估值与今日收益

## 业务入口与口径

`GET /api/v1/portfolio/income` 只接受当前有效 Bearer 会话，由 `PortfolioIncomeController` 从身份中取得用户 ID；请求中的其他用户 ID 不参与查询。响应 `Cache-Control: no-store`。`PortfolioIncomeService` 在同一个 PostgreSQL REPEATABLE READ 只读事务中校验权益切点、现金流、账务精度和历史报价，再调用 `DailyIncomeCalculator`。数据库故障返回通用 503，不输出 SQL。

业务日固定 UTC+08:00，从 00:00 到下一天 00:00；估值币种为 USDT。`groups` 包含 `overview`、`funding`、`trading` 以及六个既有 `accountType`。资金账户和现货是同一现金账本，总览只计一次。交易账户包含另外五条产品线。

收益金额 = 期末完整权益 − 日初完整权益 − 外部本金净流入。充值、提现、调账以及跨用户 C2C 收付款属于外部本金；同一用户在查询范围内部划转不产生收益。查询单个产品或资金/交易账户时，跨出该范围的划转按该范围的本金流出/流入处理。手续费、资金费、成交、交割、行权和浮动估值造成的权益变化保留在收益中。

百分比使用 Modified Dietz：收益 /（期初权益 + 每笔外部本金 × 在本期间停留时间的权重）× 100。权重按集群毫秒时间计算；资本基数不为正时百分比为空，不输出无意义的无穷值。该百分比是期间收益估计，不等同于逐笔现金流重估的精确时间加权收益。计算方法参考 [CFA Institute GIPS 计算方法说明](https://www.gipsstandards.org/wp-content/uploads/2021/03/calculation_methodology_gs_2011.pdf)。

净权益及本金以原始整数字符串/BigInteger 和 BigDecimal 计算，金额通过十进制字符串传给客户端，不能经过 double 或假设统一 1e8 精度。客户端只在格式化估值展示时转换为浮点数；提现 MAX、可用余额和划转核算继续使用原权威接口。

## 已提交事实的生成

`CommittedTradeReplay` 的独立 Archive 回放实例读取 `ADJUST_BALANCE`、`TRANSFER_OUT`、`TRANSFER_IN` 前后实际现金差额。重试、零变动、失败和冲突不产生新现金流。TRANSFER_OUT 实际立即扣款，COMPLETE_TRANSFER 只结束在途状态，不再次扣款。读取在既有 owner fence 内进行，仅访问该用户该币余额；超时停止本次导出且不推进检查点，不释放未获得的锁。在线 Core、撮合回调及账户状态没有新增历史容器或快照复制。

`CommittedTradeExporter` 在处理第一条时间大于等于切点的已提交命令前，冷查询 `CommittedPortfolioSnapshot`：现金可用+冻结；期货类另加 Core 原公式计算的浮盈亏；期权另加市值（权利金已在现金中计入，不使用入场价差重复计算）。缺失、未来、超过 120 秒的持仓标记价以及在途划转令相应用户不完整，尚未在本产品创建账户的在途接收用户也有不完整占位行。

完整的用户集合和切点元数据在同一事务写入。只有已有切点中不存在的用户才可证明当时为零；切点本身不存在不能证明零。日初保留，当日及前一业务日的日初与最近几分钟切点保留；不会保存全天每分钟的全用户快照。冷查询每分钟遍历一次回放副本的用户及持仓，不宣称大规模性能已通过压测。过慢导出会导致接口过期不可用，不会伪装成实时余额。

新投影从已有主检查点升级时，先用独立 `.portfolio-repair` 检查点完整回放至该主检查点，确认连续覆盖后再接续主导出。修复只写金融分析表，不写订单、不发 Kafka，检查点使用独立魔数，不能将主检查点重命名冒充已修复。SQL 失败不推进检查点；事实 UUID 幂等，相同身份出现不同金额则整个批次拒绝。日常现金调账有保留期；划转收据持续保留，以便核对历史前缀两端，不单独删除一端造成假缺口。

新增状态仅位于这些持久化/回放边界：`CommittedPortfolioSnapshot` 是不可变单次快照；`CommittedBalanceFlow` 是单命令实际变动；`CommittedPortfolioHistory` 管理分析表事务和连续性；批次缓存随既有 256 条/时间阈值刷新；查询中的价格缓存仅存活于一次请求。没有新增框架或通用执行层。

## 历史价格与完整性

各组选择其范围内所有产品都具有的最新同一分钟切点，最长 120 秒。最新共同切点不完整时不可退回旧的零余额来遮住刚发生的入金。日初或现金流连续性不足时，允许返回已知的期末估值，但今日收益为空。

非 USDT 币种按 SPOT 的最后一笔已提交 USDT 交易估值：报价时间不晚于权益切点前一毫秒或本金发生时刻，且不超过 120 秒；成交日志位置不超过 SPOT 对应切点。通过执行投影初始化凭据及状态发布序号确认报价投影已覆盖该切点，严格区分状态发布序号与 Archive 日志位置。instruments 按产品线关联，其他产品同一数字 ID 不能污染现货汇率；原始 ticks 按真实 price_tick_units 和 USDT scale_units 换算。

不使用当前价格重算历史本金，不使用未来成交、不猜 USD/USDC 为 1、不用未闭合 K 线近似本金发生时价格。缺乏直接现货报价时明确不可用，不暗造兑换路径。跨产品同用户划转还需检验各自前缀包含的内部流每币净额为零，避免两个集群的提交先后使总览出现暂时的假收益。

## 响应与客户端生命周期

响应包括 `currency`、`timeZone`、`periodStart`、`generatedAt` 和 `groups`。每组有 `available`、`status`、`amountUsdt`、`percent`、`currentEquityUsdt`、`openingEquityUsdt`、`asOf`。状态包含 AVAILABLE、SNAPSHOT_UNAVAILABLE、TRANSFER_PENDING、PRICE_UNAVAILABLE、PERIOD_START、HISTORY_UNAVAILABLE。未知金额为空，不能代填零。

Flutter `portfolio_income.dart` 严格校验业务日、账户集合、时间、状态和十进制字符串。AppState 每 30 秒刷新；用服务端时间加本机单调经过时间判断 120 秒过期及跨日，不依赖手机时区或可调整的本机时间。过期定时器更新界面；失败刷新清空旧估值；退出、换号、销毁取消定时器和未完成请求资格，返回结果还校验用户及会话代数。首页与资产页使用同一来源，资产过滤后只显示该范围收益；隐私开关隐藏金额和百分比。数据不齐显示 `—`。

## 发布与验收

先对 gateway/realtime 共用数据库执行 `deployment/migrations/20261020-portfolio-income.sql`，新库使用完整 `init.sql`；配套发布 realtime、gateway、client。已有 Archive 必须保留连续日志并成功完成分析重建。缺表或日志不完整属于不可用，禁止临时 SQL 补假基线。该接口仅为分析展示，不能作为资金、风控或提现事实源。

JDK 27 的实际 PostgreSQL、Aeron Archive 和 Kafka 验证包括快照六合约类型、精度、转账两端、SQL 故障恢复、主导出升级、幂等重跑、原执行导出，以及收益查询的历史 FX、过滤、资料缺失、无权限和 SQL 故障。具体命令、计数及未覆盖范围记录在 `docs/validation/portfolio-income-20261020.json`。完整 init.sql 已在本轮新建数据库真实执行，验证字段注释后删除该专用数据库。

不将单元或模拟器数据声称为真实生产账户的今日收益，也不将本次收益模块测试声称为全部产品的新增下单、强平或行情压测。真实 Telegram 授权和 iOS 实机防截图仍是整体功能的独立验收项。
