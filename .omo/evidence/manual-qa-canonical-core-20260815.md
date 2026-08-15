# Canonical Core wrapper manual QA

日期：2026-08-15

## 通过项

- `bash -n scripts/*.sh`：通过。
- `PRODUCT_LINE=SPOT scripts/integration-smoke.sh`：`spotMatchSmoke=PASS`、`status=OK`、`exportStatus=PASS`。
- `PRODUCT_LINE=SPOT MANAGE_CORE=true KEEP_RUNTIME=false scripts/live-runtime-trading-reconciliation.sh`：`status=OK`、`exportStatus=PASS`。
- `PRODUCT_LINE=SPOT MATRIX_EXECUTE=true scripts/run-product-line-recovery-matrix.sh`：三节点 node0 stop/rejoin 与 cold restart 的 `stateHash` 均为 `f7a62ff8670b7aee`，角色日志同时包含 `role=LEADER` 与 `role=FOLLOWER`；`ROLE_EVIDENCE=PASS`、`EXPORT_FAILURE=PASS`、`FUNDS_DIFFERENCE=0`。
- `PRODUCT_LINE=LINEAR_PERPETUAL FRESH=true CAPACITY_DURATION_SECONDS=2 CAPACITY_WARMUP_SECONDS=1 CAPACITY_WORKERS=1 CAPACITY_CONNECTIONS=1 CAPACITY_USER_COUNT=2 scripts/run-product-line-capacity.sh`：`capacity=PASS`，98 commands、49 matches、0 failures、`fundsDiff=0`，生成 capacity manifest。
- `PRODUCT_LINE=SPOT scripts/kafka-trading-smoke.sh`：`kafkaTradingSmoke=PASS productLine=SPOT scope=CORE_INPUT_EXPORT_BRIDGE`。
- `PRODUCT_LINE=INVERSE_PERPETUAL MATRIX_EXECUTE=true scripts/run-product-line-recovery-matrix.sh`：`recoveryMatrix=PASS`，stateHash `817d7a3f2ad83ce7`，`EXPORT_FAILURE=PASS`、`FUNDS_DIFFERENCE=0`。
- `PRODUCT_LINE=LINEAR_DELIVERY MATRIX_EXECUTE=true scripts/run-product-line-recovery-matrix.sh`：`recoveryMatrix=PASS`，stateHash `e3cdf9364dc1ccc2`，`EXPORT_FAILURE=PASS`、`FUNDS_DIFFERENCE=0`。
- `PRODUCT_LINE=INVERSE_DELIVERY MATRIX_EXECUTE=true scripts/run-product-line-recovery-matrix.sh`：`recoveryMatrix=PASS`，stateHash `3528c2f5b5f47914`，`EXPORT_FAILURE=PASS`、`FUNDS_DIFFERENCE=0`。
- `PRODUCT_LINE=OPTION MATRIX_EXECUTE=true scripts/run-product-line-recovery-matrix.sh`：`recoveryMatrix=PASS`，stateHash `60021f30db43cec7`，`EXPORT_FAILURE=PASS`、`FUNDS_DIFFERENCE=0`。
- `CAPACITY_DURATION_SECONDS=20 CAPACITY_WARMUP_SECONDS=3` 分别执行 `SPOT`、`LINEAR_PERPETUAL`、`INVERSE_PERPETUAL`、`LINEAR_DELIVERY`、`INVERSE_DELIVERY`、`OPTION`：六条产品线均 `capacity=PASS`，`failures=0`、`fundsDiff=0`、`bookLevels=0`。
- `PRODUCT_LINE=SPOT FRESH=true CAPACITY_DURATION_SECONDS=2 CAPACITY_WARMUP_SECONDS=0 CAPACITY_WORKERS=1 CAPACITY_CONNECTIONS=1 CAPACITY_USER_COUNT=2 scripts/run-product-line-capacity.sh`：`capacity=PASS`，46 commands、23 matches、0 failures、`fundsDiff=0`。
- `PRODUCT_LINE=INVERSE_PERPETUAL FRESH=true CAPACITY_DURATION_SECONDS=2 CAPACITY_WARMUP_SECONDS=0 CAPACITY_WORKERS=1 CAPACITY_CONNECTIONS=1 CAPACITY_USER_COUNT=2 scripts/run-product-line-capacity.sh`：`capacity=PASS`，24 commands、12 matches、0 failures、`fundsDiff=0`。
- `PRODUCT_LINE=LINEAR_DELIVERY FRESH=true CAPACITY_DURATION_SECONDS=2 CAPACITY_WARMUP_SECONDS=0 CAPACITY_WORKERS=1 CAPACITY_CONNECTIONS=1 CAPACITY_USER_COUNT=2 scripts/run-product-line-capacity.sh`：`capacity=PASS`，42 commands、21 matches、0 failures、`fundsDiff=0`。
- `PRODUCT_LINE=INVERSE_DELIVERY FRESH=true CAPACITY_DURATION_SECONDS=2 CAPACITY_WARMUP_SECONDS=0 CAPACITY_WORKERS=1 CAPACITY_CONNECTIONS=1 CAPACITY_USER_COUNT=2 scripts/run-product-line-capacity.sh`：`capacity=PASS`，46 commands、23 matches、0 failures、`fundsDiff=0`。
- `PRODUCT_LINE=OPTION FRESH=true CAPACITY_DURATION_SECONDS=2 CAPACITY_WARMUP_SECONDS=0 CAPACITY_WORKERS=1 CAPACITY_CONNECTIONS=1 CAPACITY_USER_COUNT=2 scripts/run-product-line-capacity.sh`：`capacity=PASS`，48 commands、24 matches、0 failures、`fundsDiff=0`。
- `mvn -q -f surprising-trading/surprising-trigger-provider/pom.xml -am -Dtest=TriggerOrderServiceTest test`：37 个测试通过，覆盖 Aeron 触发扫描跨页 cursor。
- `mvn -q -pl surprising-aeron-core/surprising-aeron-exporter -am test`：通过，覆盖带 acknowledged cursor 的导出批次和 batch+ack 周期。
- `mvn -q -pl surprising-aeron-core/surprising-aeron-protocol,surprising-aeron-core/surprising-aeron-client,surprising-aeron-core/surprising-aeron-service -am clean test`：通过，Core service 91 个测试覆盖 `COMMAND_RESULT_QUERY` 结果核验。
- `mvn -q -pl surprising-aeron-core/surprising-aeron-exporter -am clean test`：通过，确认新 query 类型与 input/export bridge 回归兼容。
- `mvn -q -f surprising-trading/surprising-trigger-provider/pom.xml -am clean test`：通过，Core-only 配置门禁和 Aeron placement 不再同步调用费率 RPC。
- `mvn -q -pl surprising-aeron-core/surprising-aeron-service -am clean test`：通过，修复 CoreProbeState 构造线程提前绑定 Runtime owner 后，三节点 service thread 查询失败的问题。

## 边界

- 以上是单产品线 Core-only/受控本地证据，不等同 HTTP provider、做市进程、Kafka 集群和 WebSocket 全链路证据。
- recovery 的 node stop/rejoin 是本地受控 leader/follower 角色转换，不证明生产网络分区、进程崩溃和磁盘故障语义；capacity 是小规模实验，不是百万级容量结论。
- capacity 的 p99/max 在本地 Docker 受控环境受启动/调度抖动影响，不能用于容量承诺；需要独立长时间 baseline/soak 和真实 Kafka/PG projection lag 采样。
- 所有运行均在结束时停止容器和网络；未删除非目标产品线资源。
