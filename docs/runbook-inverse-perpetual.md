# INVERSE_PERPETUAL 币本位永续运行手册

币本位永续统一遵循 [Aeron 六产品线运行手册](runbook-aeron-six-product-lines.md)，使用
`PRODUCT_LINE=INVERSE_PERPETUAL`、基础币结算和 `surprising.inverse-perp.*` Topic。

验收必须覆盖反向合约盈亏、资金费净额、mark sequence、Risk、Liquidation Work、Takeover
Liquidation、强平费、Insurance Treasury、ADL 恢复、Leader 切换、Snapshot、冷恢复、Exporter
重放和 `fundsDiff=0`。不得用 U 本位数学或 USDT 资金核对替代币本位结果。
