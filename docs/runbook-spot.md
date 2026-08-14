# SPOT 现货运行手册

现货统一遵循 [Aeron 六产品线运行手册](runbook-aeron-six-product-lines.md)，使用
`PRODUCT_LINE=SPOT` 和 `surprising.spot.*` Topic。

现货验收必须覆盖 base/quote 双资产充值、卖方资产预占、买方报价资产预占、完全/部分成交、撤单
释放、空订单簿、Leader 切换、Snapshot、冷恢复、Exporter 重放和 `fundsDiff=0`。不得产生资金费、
强平、交割或期权行权事件。
