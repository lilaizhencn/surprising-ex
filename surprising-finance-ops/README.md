# 财务运营暂存模块

`surprising-finance-ops` 是未来财务运营系统重建前的编译期暂存边界。

当前约束：

- 模块只参与 Maven 编译，不提供启动类、Controller、定时任务、消息消费者或数据库连接。
- 交易主库不为财务对账、订单时间线、运营报表和后台聚合查询提供运行时数据源。
- 当前模块不实现新功能，也不接入现有 provider 的运行依赖。
- 后续迁入的暂存代码不得被交易、账户、撮合、风控、WebSocket 或 gateway provider 反向依赖。
- 正式建设财务系统时，需要重新设计独立数据库、事件投影、消费水位、回放和对账模型；暂存代码不视为正式设计。

包边界：

- `reconciliation`：财务对账暂存边界。
- `ordertimeline`：订单时间线暂存边界。
- `reporting`：运营报表暂存边界。
- `adminquery`：后台聚合查询暂存边界。
- `feequalification`：成交量、资产估值、VIP 资格和做市质量评定暂存边界。
