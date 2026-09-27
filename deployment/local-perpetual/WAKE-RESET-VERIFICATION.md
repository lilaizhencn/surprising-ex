# 黑屏后停更排查与本地重置（2026-09-27）

17:27:59 Core 在 `TradingCoreOwner.checkProgressDeadline` 抛出
`asynchronous command progress interrupted or timed out` 并退出，launchd 随后重启。
realtime 也因 Core MediaDriver 不可用而重启。原始异步超时的触发原因尚未确定；
用户一直开盖，系统 Sleep/Wakes 为 0，不能归因为屏幕自动熄灭。
最后完整快照在 10:59，日志位置 1,076,481,856，待恢复位置为 12,014,193,728。
恢复时日志位置持续推进。`TradingRealtimeBoundary` 只允许 Leader 对外捕获实时状态；
核心恢复资金和订单不等于向页面补播行情。页面重订阅 depth 接收当前快照，再合并增量。

前端 `RealtimeConnections` 增加 20 秒检查、45 秒无有效消息主动重连及页面可见性检查。
启动器验证 launchd 归属并刷新 PID；status 增加 Core 查询；定期快照核对 service/consensus
有效配对。业务流程、失败边界见本目录 README 及前端 REALTIME.md。

用户明确要求丢弃本地测试历史。停止六服务及依赖后，清空 `perpetual-pmm-20` 所属
PostgreSQL、Kafka、Redis、Core Archive、行情状态及注资标记，保留配置、构建产物与证据，
重新执行 `local-perpetual.sh up`。其他运行目录及用户未提交的 Core 源码没有改动。
旧登录、订单、持仓、K 线历史均已清空。新演示账户凭据仅存运行目录权限 600 的
`demo-credentials.json`，认证响应存 `demo-auth.json`，不提交凭据。

## 实测

- HotSpot Corretto JDK 27 / Maven 3.9.16；前端 71 项测试、lint、build 通过；快照调度
  5 项 Python 测试、shell 语法检查通过。本次没有 Java 交易实现改动。
- Chrome 半开连接丢弃消息及后台时间跳变测试均能重连、重新订阅，无 JS 错误。
- 六服务启动成功，就绪检查曾达到 20/20（三源指数、标记价、双边盘口）。
  首次自动快照完成：timestamp=1790504888980，position=2028832。
- 60 秒 WebSocket：20 币对均有成交和深度，共 9,842 条成交、1,384 条深度，序号断裂 0、
  协议错误 0。input.dropped 未增长，transport.dropped 增加 2；最长盘口间隔 17,867 ms。
- 桌面和手机各 15 次盘口采样均得到 5 个不同状态；无 JS 错误，手机 390/390 无横向溢出。
  BTC 图表 15m 历史一次、独立 24h 统计 1m 窗口一次、指数初始化一次，无行情轮询。
- 暂停做市后核对 22 账户及国库：总额 3,010,000,000,000,000（10^-8 USDT），与本次
  注资差额 0；20 币对净持仓均 0，演示用户冻结 0。核对后恢复全部 20 策略。

## 限制和清理

刷新延迟仍有缺口；后续抽查曾为 15/20、17/20，部分指数/标记价短暂 503。
price health 实测 HTTP 200 耗时 6.285 秒，五秒状态探测会超时，不能宣称持续健康。
未重新覆盖强平、ADL、资金费结算时点、止盈止损、其他产品线、多日稳定性及再次故障恢复耗时。
JFR 已停止，分析后删除本轮 JFR 与中间事件 JSON；浏览器及恢复等待探针均退出。
小型证据保留在运行目录 `verification/wake-recovery-20260927/`，重置清单见 `reset.json`。
新环境、前端和快照任务按查看页面的要求保留运行。
