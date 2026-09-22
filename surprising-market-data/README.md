# 市场数据契约

此目录保留 `surprising-market-data-api`（K 线 RPC 和事件模型），不再构建或启动独立 market-data provider。

- 盘口查询实现迁入 `surprising-gateway` 的 `com.surprising.trading.matching` 包。
  公共 URL `/api/v1/gateway/trading-market/orderbook` 不变，网关本地调用原 Controller/Service，
  使用现有 `OrderAeronGateway` 查询 Core；没有复制订单簿或把行情状态写入账户。
  旧内部 `/api/v1/trading/market/orderbook` 由 gateway 9094 承接，独立调用方须带 `X-Business-Internal-Token`。
- K 线实现和测试迁入 `surprising-realtime-provider` 的 `com.surprising.candlestick.provider` 包，
  与实时路由同进程，默认 HTTP 9095。原 `/api/v1/candlestick/**` 和网关公共 URL 保持不变。
- 旧 `surprising-market-data-provider` 启动类、POM、配置及单独盘口 Aeron 连接池已移除。

之前“历史 K 线暂未接入”的说明已过时：当前成交导出器从已提交 Archive 导出 `PublicTradeEvent`，
K 线消费同产品 `match.trades` topic 并聚合、落库。产出实时 K 线仍依赖成交导出器实际运行。

完整数据路径及部署要求见 [realtime 行情应用](../surprising-realtime/README.md)。
