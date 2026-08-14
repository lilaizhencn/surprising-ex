# INVERSE_DELIVERY 币本位交割运行手册

币本位交割统一遵循 [Aeron 六产品线运行手册](runbook-aeron-six-product-lines.md)，使用
`PRODUCT_LINE=INVERSE_DELIVERY`、基础币结算和 `surprising.inverse-delivery.*` Topic。

验收必须覆盖反向交割盈亏、到期结算、订单释放、持仓归零、Leader 切换、Snapshot、冷恢复、
Exporter 重放和 `fundsDiff=0`。不得运行永续资金费流程，也不得用 U 本位资金核对替代币本位结果。
