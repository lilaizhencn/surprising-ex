# 本人历史委托

`GET /api/v1/gateway/trading/history` 映射到 `OrderInternalController` / `TradingLocalRoutes` 的 `/api/v1/trading/orders/history`，沿用 `OrderRequestService`、`OrderService`、`AeronOrderProjectionRepository`。认证账户与产品由既有网关绑定；查询只读 realtime exporter 提交的 `core_order_projection`，不查询交易 Core。

可选 `status=FILLED|CANCELED|REJECTED` 在 SQL 的 LIMIT 之前筛选；不传则查询全部终态，OPEN 不属于历史。非法状态 400。现有 instrumentId、limit（1–1000）、orderId 下界、创建时间 startTime/endTime（毫秒且含两端）、cursor、minExportSequence 继续有效。SQL 与游标按 updated_at_epoch_ms / order_id 倒序，响应 sort 从不准确的 createdAt.desc 修正为 updatedAt.desc；创建时间筛选与更新时间排序是两个独立含义。

前端必须把游标与原账户、产品、交易对、状态和日期一同保存。Web 两处入口和 client 均保留继续加载；Web 分类记录的条件变化立即清空旧页并查询，client 输入为草稿，点击筛选后应用，加载更多沿用上次已应用条件。账户/产品/筛选变化后的迟到请求不能写入新结果。

历史显示本人原订单号、方向、委托类型、状态、限价/均价、委托/已成交/剩余量、累计手续费和时间。价格采用整数和十进制运算，均价允许小数 tick，不先转 double/Number。无真实元数据时不猜精度；client 的历史合约不在当前交易目录时，按原 instrumentId 和产品查询 latest 后再发布该页。手机沿用已有卡片样式与颜色 token，不提供对终态订单再次撤销的操作。

无新表、迁移、topic、资金模型或 Core 状态变化。需配套 gateway/Web/client 发布。验收记录为 docs/validation/spot-order-history-20261020.json；共享六产品 enum/SQL 隔离测试不替代其他产品的实栈验收。
