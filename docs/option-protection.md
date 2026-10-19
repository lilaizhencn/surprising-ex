# 期权原子止盈止损与原请求恢复

本次按 Web 已有能力补齐 client 的期权保护委托。入口是期权交易页的“高级委托 → 条件单 → TP/SL 双腿 OCO”：两腿共享当前持仓的方向、保证金模式、持仓侧、数量及价格源，各自选择触发价和触发后市价/限价执行。限价须单独填写；一腿触发提交减仓委托，另一腿撤销，触发不等于保证成交。

client `TriggerOrderPanel` 按当前合约的 `quoteScaleUnits` 和 `priceTickUnits` 转换触发价、激活价及委托限价，不再假设八位价格精度。`ApiClient.placeTriggerOrder` 发送永久 `instrumentId`，不再发送接口未接收的 `symbol`，并携带实际价格源及执行类型。持仓方向、数量、精度资料及未到期 TRADING 状态均须有效；停牌/关闭和已有待确认请求禁止新的 OCO。追踪止损仍只允许市价执行。

`AppState.placeOptionOco` 校验两腿一致性和当前持仓，生成两腿身份并先写入既有设置存储，再向已有 `trading-trigger/batch` 发送一次 `atomic=true` 请求；没有把双腿拆成两次普通提交。完整响应需核对数量、索引、两腿身份、合约及 OCO 分组。保存失败不发送；网络中断或响应不完整保留原身份，重新构建 AppState 后仍只读取原回执，不重发两腿。换用户不把旧请求结果展示给新用户。

Web 的原子 OPTION 分支同样先保存两个原身份，复用 `pendingOrders` 和现有结果确认按钮。结果不明、刷新页面、查询 503 或缺失回执均不能视为失败后重新下单。期权 TP/SL 票据不沿用普通开仓的权利金和当前盘口费用预估；其费用显示为“触发成交时计算”。普通期权限价票据的全额权利金口径保持不变。

## 回执查询

```text
GET /api/v1/gateway/trading-trigger/oco-submission
  ?userId=<当前用户>
  &takeProfitClientId=<原止盈身份>
  &stopLossClientId=<原止损身份>
X-Product-Line: OPTION
Authorization: Bearer <当前用户会话>
```

内部路径为 `/api/v1/trading/trigger-orders/oco-submission`。`TriggerOrderService.ocoCommandId` 与原子提交共用原命令身份，维持已有产品/用户/有序两腿的编码；拒绝空、超长、重复及包含分隔符 `:` 的身份。`TriggerOrderRequestService` 使用既有 `OrderService.commandResult` 读取 realtime exporter 写入的已提交回执表，不向在线 Core 查询，也不通过订单是否仍然挂着推断接受结果。用户及产品隔离仍由原网关身份和投影查询约束。

`TERMINAL/NONE` 确认原请求已应用，其他明确终态代码表示已提交的拒绝；`RESULT_UNKNOWN`、查询异常或格式不匹配保留待确认。查询不能为从未到达服务端的请求提供负确认。它不要求两个保护单仍处于挂起状态，因此其中一腿已经触发、另一腿已经撤销后仍能恢复原接受结果。

本次无需新增 SQL、topic 或 Core 交易协议。沿用既有回执迁移、realtime 已提交日志导出和查询表；发布时先确保该链路可用，再同步更新 gateway、Web 和 client。

## 验证范围

后端测试覆盖原身份稳定性、产品/用户/两腿隔离、查询不访问 Core、无效查询和原有条件单维护。client 覆盖三位资产精度、限价/市价、价格源、持仓及生命周期拒绝、发送前存储、重建后只读恢复和 320px 深浅色操作。Web 覆盖发送前存储、错误回执、丢回包及原查询路径。

仅启动 OPTION 单节点 FUSED 和真实做市，低堆、单分区 Kafka、单线程行情、无 wallet/JFR。实际 Web 先开一张持仓，再原子提交双腿、丢弃真实成功响应、刷新查询原回执，最后撤销两腿；client 再提交一次 OCO，暂停本轮 realtime 并丢弃真实成功响应，未知结果保持待确认，恢复后重建并查询原回执。止盈腿触发限价平仓，止损腿撤销。Maven 验收独立核对两个用户及做市账户、两腿终态、持仓/冻结和资金守恒；测试中的 Core 只读断言仅用于对账，不是产品查询路径。

脚本入口：Web `scripts/option-protection-fused-live.mjs`；Flutter `test/option_protection_fused_live_test.dart`；后端 `SixProductLineContractLiveTest` 以 `SIX_LINE_LIVE_OPTION_PROTECTION=true` 执行末态核对。使用本轮隔离 fixture、原运行目录 realtime/price PID 文件及已有 Playwright/Chromium。两端均退出 0 才释放交接。Web 脚本读取 HTTP 中的 64 位整数时保留原始文本，不能用 JavaScript 浮点转换后撤单。

完整结果、首次验收脚本错误、前一轮隔离账户归零对账及清理记录见 `docs/validation/option-protection-20261020.json`。本次未做 iOS/Android 真机安装、生产发布、其他产品 OCO 实栈或新的到期/强平压力矩阵。独立条件单的持久恢复及管理端条件单查询边界等仍需继续审计，不把本次验收视为全平台完成。
