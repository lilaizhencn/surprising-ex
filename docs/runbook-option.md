# OPTION 期权运行手册

期权统一遵循 [Aeron 六产品线运行手册](runbook-aeron-six-product-lines.md)，使用
`PRODUCT_LINE=OPTION` 和 `surprising.option.*` Topic。

验收必须覆盖权利金、买方资金、卖方确定性保证金、撮合、到期行权或失效、双边权益、持仓归零、
Leader 切换、Snapshot、冷恢复、Exporter 重放和 `fundsDiff=0`。期权预留必须由 Core 权威规则
计算，门禁不得降低保证金要求来绕过拒单。
