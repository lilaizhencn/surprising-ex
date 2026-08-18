# 固定产品线 W4 测试脚本

每个脚本只对应一条产品线，参数、端口、运行 ID、钱包开关和主 worktree 均写死，不读取 `PRODUCT_LINE`、`PRODUCT_LINES` 或动态端口覆盖。

六个脚本共用 `../run.sh` 的唯一启动实现，实际启动顺序固定为：

1. PostgreSQL
2. Kafka
3. migrations（先加载 `gateway-schema.sql`，再加载根初始化和版本迁移）
4. Aeron Core `node0`、`node1`、`node2`
5. Exporter
6. Projector
7. Instrument
8. Price
9. Account
10. Order
11. Matching
12. Trigger
13. Risk
14. Funding（仅两条永续）
15. Liquidation、Insurance、ADL（现货跳过）
16. Gateway
17. Maker（最后启动）

运行方式：

```bash
./spot.sh test
./linear-perpetual.sh test
./inverse-perpetual.sh test
./linear-delivery.sh test
./inverse-delivery.sh test
./option.sh test
```

`start` 只启动固定产品线，`stop` 只清理对应运行。`test` 使用全新、带标签的 PostgreSQL/Kafka/Aeron 资源，完成后自动清理并生成 W4 manifest；wallet 永不启动。
