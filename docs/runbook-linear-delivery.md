# LINEAR_DELIVERY U 本位交割运行手册

U 本位交割统一遵循 [Aeron 六产品线运行手册](runbook-aeron-six-product-lines.md)，使用
`PRODUCT_LINE=LINEAR_DELIVERY`、USDT 结算和 `surprising.linear-delivery.*` Topic。

验收必须覆盖撮合、保证金、到期结算、双边盈亏、订单释放、持仓归零、Leader 切换、Snapshot、
冷恢复、Exporter 重放和 `fundsDiff=0`。不得运行永续资金费流程。
