# 订单链路八项简化验证（2026-10-18）

## Gateway 入口与无调用逻辑

本阶段完成审查中的第 1、2、5、6、7、8 项：

- `OrderService` 一次完成标准化与对冲方向推导，字段未变化时复用不可变请求；BBO 请求仍解析盘口后构造最终请求。
- 本地校验通过后只传最终请求，删除 `PreparedAeronOrder` 和批次平行 `ValidationResult` 列表；试单仍保留规则版本。
- `AeronOrderCommandService` 不再接收未使用的校验参数，命令结果载荷只复制读取一次。
- 合并 `placeAeron/testLocal/amendAeron` 单调用方转发方法；不改变 Core 命令、幂等 ID 或回执协议。
- `OrderPlacementStateService` 保留实际平仓业务校验，直接返回有符号数量；删除 `ReduceOnlyPosition` 及无生产调用的旧查询方法。
- `OrderMarginMath` 仅保留 `OrderValidator` 实际使用的价格上下界，删除只有测试调用的旧保证金公式及对应孤立测试。

HotSpot Corretto JDK 27（27+33-FR）、Maven 3.9.16：

```bash
mvn -pl surprising-gateway -am -Dtest=OrderServiceTest,OrderBatchServiceTest,AeronOrderCommandServiceTest,OrderControllerTest,OrderPlacementStateServiceTest,OrderValidatorTest,OrderMarginMathTest,BboOrderServiceTest,StableOrderIdentityTest,AlgoOrderServiceTest,CancelAllAfterServiceTest,InstrumentOrderDrainServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

退出码 0；130 项通过，失败/错误/跳过均为 0；20 模块编译通过。
补充用例覆盖六产品线对冲开平仓方向与请求字段、批次全部校验后才提交。首次编译发现测试残留旧调用及仍需使用的枚举 import，修正后以上完整命令通过。

这是本地功能验证，不代表资金端到端或性能验收。此阶段未启动交易集群、做市、wallet，未执行快照恢复或六产品线在线资金对账；后续阶段继续验证点查询和 Core 保证金写入。

工作区并行出现的 `TradingCoreOwner.java` 修改不属于本轮实现，保留原状且不纳入本轮提交。
