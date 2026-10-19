# 市场数据契约

此目录保留 `surprising-market-data-api`（gateway 与 realtime 共用的 `CandleUpdatedEvent`、`CandleStatus`），不再构建或启动独立 market-data provider。

- 盘口查询实现迁入 `surprising-gateway` 的 `com.surprising.trading.matching` 包。
  公共 URL `/api/v1/gateway/trading-market/orderbook` 不变，网关本地调用 Service，
  读取 realtime Router 写入 Valkey 的盘口视图；公共 REST 和 BBO 参考价不再向 Core 发起查询。
  视图按产品线和 instrumentId 隔离，Router 重启、缺失或超过 5 秒未刷新返回 503，不回查 Core。
  REST 深度仍接收 1–100 的请求，实际窗口最多每侧 50 档，响应 depth 如实返回可用窗口。
  旧内部 `/api/v1/trading/market/orderbook` 由 gateway 9094 承接，独立内部调用方无需凭证。
- K 线实现和测试迁入 `surprising-realtime-provider` 的 `com.surprising.candlestick.provider` 包，
  与实时路由同进程，默认 HTTP 9095。原 `/api/v1/candlestick/**` 和网关公共 URL 保持不变。
- 旧 `surprising-market-data-provider` 启动类、POM、配置及单独盘口 Aeron 连接池已移除。

之前“历史 K 线暂未接入”的说明已过时：当前成交导出器从已提交 Archive 导出 `PublicTradeEvent`，
K 线消费同产品 `match.trades` topic 并聚合、落库。产出实时 K 线仍依赖成交导出器实际运行。

完整数据路径及部署要求见 [realtime 行情应用](../surprising-realtime/README.md)。

K 线查询 DTO、周期枚举和成交聚合输入已迁入 realtime 的 `src/main/java/com/surprising/candlestick/api`，无调用的 `CandlestickRpcApi` 已删除。Java 包名及事件 JSON 保持不变。
