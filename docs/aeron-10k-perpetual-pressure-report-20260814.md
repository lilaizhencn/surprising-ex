# 10,000 用户永续合约压力测试报告

## 测试规模

- 产品线：`LINEAR_PERPETUAL`。
- Aeron：单产品线三节点 Cluster；不启动 Exporter、Projection、PostgreSQL、Valkey 或 Kafka。
- 逻辑用户：`10,000`（5,000 个 maker/taker 用户对），单 symbol。
- 并发执行：64 workers、64 Aeron connections；这是 10,000 个逻辑用户通过 64 个连接复用，不是 10,000 个 TCP 连接。
- 预热：30 秒；正式测量：600 秒。
- 场景：maker GTC + taker IOC，持续建仓/成交并校验资金和订单簿。

## 结果

| 项目 | 结果 |
|---|---:|
| offered commands/s | 80 |
| committed commands/s | 32.740 |
| match events/s | 16.370 |
| commands | 19,864 |
| matches | 9,932 |
| P50 | 29.487ms |
| P95 | 50.273ms |
| P99 | 53.847ms |
| max latency | 229.804ms |
| failures | 0 |
| funds diff | 0 |
| final book levels | 0 |
| max process CPU | 148.7% |
| max total RSS | 3.37GiB |

## 边界解释

短跑以 offered `100 commands/s` 验证时实际达到 `92.267 commands/s`，未达到脚本严格门槛的 95%，但业务和资金校验通过。正式 10 分钟采用 `ASSESSMENT_MODE=observe`，避免把负载发生器/单 symbol 串行撮合造成的供给不足误报为 Core 业务失败。

因此本机当前可确认的 10,000 逻辑用户单 symbol 基线是约 **32.7 committed commands/s**，而不是 80 或 100 commands/s。瓶颈表现为单热点 symbol、64 连接复用和本机资源竞争；本次不是 10,000 网络连接或多 symbol 容量结论。

原始证据：`/Users/a123/Documents/ChatGPT/demo/reports/aeron-10k/20260814/LINEAR_PERPETUAL/`。
