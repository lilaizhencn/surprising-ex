# 本地永续盘口恢复验证（2026-09-27）

## 原因及修复

BTC 的 Core 订单簿买卖两侧为空，做市账户 900101 可用余额仅约 5.44 USDT；
补单持续被 `INSUFFICIENT_AVAILABLE_BALANCE` 拒绝。`MarketMakerService.runStrategySymbol`
原先忽略 `quoteAccount` 的拒单结果，仍记录 `CYCLE_SUCCESS` 并显示 RUNNING。
现在复用 `ReconcileResult` 汇总拒单，失败周期显示 DEGRADED、记录核心原因；正常补单成功后恢复。
保留成功订单，继续由 Core 校验保证金、价格与 post-only；未改交易、费用或结算规则。
无新增类、接口或持久状态，拒单计数仅属于当前报价周期。

自管本地模拟环境通过余额调整 API 一次性扩大测试预算，详见 README。
先暂停 20 个策略，核对账户及国库，再注资、核对并恢复全部策略。
注资前累计 3,100,000 USDT，注资后累计 30,100,000 USDT；两次核对差额均为 0，
20 个币对净持仓均为 0。费用进入国库，未清空数据库、订单或 Archive。
本轮仅替换、重启 maker JAR，其他五个服务及前端继续运行。

## 验证范围

- HotSpot Corretto JDK 27 / Maven 3.9.16；maker 全模块 package：58 项测试，0 失败、0 错误。
- 新增拒单回归测试先在旧逻辑失败，再验证 DEGRADED → 成功补单 → RUNNING。
- 启动脚本 `bash -n`、`git diff --check` 通过。
- 20 个策略均 RUNNING；就绪复查 20/20 通过，包含三源指数、标记价及双边盘口。
- 60 秒 WebSocket：20 个币对均有成交及深度，27,005 条成交、2,295 条深度，序列断裂 0。
- Chrome 桌面盘口 15 次采样得到 15 个不同内容；移动宽度 390/390，无页面 JS 错误。
- 六个 Java 服务、前端、PostgreSQL、Redis、Kafka 均在运行。

## 限制及清理

首轮指数就绪检查 AAVE、OP 短暂返回 503，复查通过。采样期间 input.dropped 无增长，
transport.dropped 增加 4；最大深度更新间隔 17,173 ms，说明刷新延迟仍有缺口，不能据此宣称稳定高频。
预算有限，持续交易仍会消耗余额；没有自动补亏或放宽资金校验。
本轮未修改前端、Core、结算或其他产品线，不重复运行其全量测试；未验证多日稳定性。
本轮浏览器均已退出，无遗留测试浏览器；未新建集群、JFR 或 Archive，磁盘剩余约 368 GiB。
测试记录与截图保留在运行目录 `verification/book-restored-20260927/`，运行服务按演示要求保留。
