# LINEAR_PERPETUAL U 本位永续运行手册

U 本位永续统一遵循 [Aeron 六产品线运行手册](runbook-aeron-six-product-lines.md)，使用
`PRODUCT_LINE=LINEAR_PERPETUAL`、USDT 结算和 `surprising.linear-perp.*` Topic。

验收必须覆盖撮合、保证金、资金费净额、mark sequence、Risk、Liquidation Work、Takeover
Liquidation、强平费、Insurance Treasury、ADL 恢复、Leader 切换、Snapshot、冷恢复、Exporter
重放和 `fundsDiff=0`。Risk 与强平不得回退 Redis、Kafka candidate 或 PostgreSQL 事务。
