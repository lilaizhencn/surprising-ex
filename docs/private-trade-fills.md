# 用户成交记录与 realtime 查询

用户 Web/client 的成交记录入口统一为 `GET /api/v1/gateway/trading/trades`，由已认证的 trading 路由映射至 `/api/v1/trading/orders/trades`。此前两端使用的 `trading-trades/trades` 已没有后端路由，不能返回有效成交记录。

入口沿用 `TradingLocalRoutes` → `OrderRequestService.ownFills` → `OrderService.ownFills` → `TradeProjectionRepository.ownFills`。用户不能用 query `userId` 访问他人账户；产品由现有网关上下文绑定。查询只读 realtime exporter 已提交的 `core_execution_projection`，不向 Core 发查询、不查询管理员接口，不新增表、SQL 迁移、topic 或交易状态。

参数为可选 `instrumentId`、本人 `orderId`、毫秒 `startTime`/`endTime`（含两端）、`cursor` 及 1–1000 的 `limit`（默认 100）。分页按成交时间、提交序号、执行序号和成交角色倒序，游标绑定账户、产品和所有筛选；不能跨筛选或复用管理端游标。成功 no-store；非法参数 400、跨账户 403，未初始化、非法投影或数据库故障 503，不能当作成功空列表。

每条记录只包含成交号、产品与合约、本人订单号/方向/持仓方向、Maker/Taker 身份、真实整数价格与数量、本人当时的费率及成交时间。不会返回对手账户、对手订单或对手费率。自成交有两笔本方执行，分别保留 Maker 和 Taker，不按成交号误去重；管理端按一笔撮合计数的原口径不变。`feeRatePpm` 是原成交费率，可为负，界面明确标为成交费率；不据此猜算实际扣费金额。

Web 订单页与分类记录页共用 `TradeFillTable`，按真实币种 scale/tick/step 解释价格与数量、保留 64 位订单号，支持继续加载；手机使用可直接阅读的成交卡片，长订单号与成交号可换行。订单页请求带账户及筛选归属，迟到结果不能覆盖另一账户/产品/页签。client 复用 `FundingRecordsPage`，现货交易页提供“历史委托/成交记录”入口并带入当前交易对；成交记录不显示不适用的币种/状态筛选，价格和数量不再写死 8 位精度。

先保证此前成交投影迁移、realtime 导出及历史覆盖已就绪，再配套发布 gateway、Web 和 client；旧地址不保留别名。实测范围与临时环境清理见 `docs/validation/spot-private-fills-20261020.json`。
