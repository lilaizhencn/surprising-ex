# 第六十一轮：资金费续页只依赖已保存价格

本轮基于 `f181569550fc9214b393d7fb7b621596cd3bd51f`，2026-10-24开始、2026-10-25完成。解决A181；累计187项解决，A11候选、A105/A117/A124/A129保留，全量审计仍继续。

Owner先读取本币对资金费进度：首轮才读取并要求当前标记价，续页直接使用已保存的固定价格。账户Lane收付、账本记录进度和差额，Owner按原完成边界返回。首轮缺行情的拒绝优先级、递增结算号、配置对象身份、费率/游标匹配、幂等、整数金额及资金守恒保持原规则。

没有新增生产类、字段、接口或执行阶段；没有修改命令、快照、HTTP/WebSocket格式。本轮删除的是无业务依赖的续页缺行情拒绝，没有删除固定计算价等实际金融输入。

新增回归覆盖两种永续：首轮缺价仍优先拒绝；行情由100变200后续页仍按100计算；缺当前行情时结算编号/费率/游标及配置身份错误仍拒绝；八页完成后线性每人收付10、反向每人收付1；明确缺行情的冷状态编码为分块快照并恢复后继续资金费。逐页检查资金守恒、重复命令不再修改及下一轮反向费率。

执行前确认HotSpot JDK27、Maven3.9.16与217Gi可用磁盘。资金费、金融矩阵、系统账本、已提交回放、生命周期、风险共6类67例全部通过，0失败/错误/跳过；18模块运行包构建成功。打包命令跳过测试，67例来自独立回归命令。实际运行包中的资金费classfile与当前编译输出逐字相同，major71。

随后依次启动Core、Gateway、Price、Realtime、Maker，衍生品另启动Lifecycle。使用本轮独立原生PostgreSQL、Kafka、Redis和确定性行情；每次只启用当前产品，停止后才启动下一条。每条真实HTTP/WS测试1例，均0失败/错误/跳过。

| 产品线 | 业务实测 | 恢复前后命令数 | 恢复前后状态哈希 |
| --- | --- | --- | --- |
| SPOT | 通过，资金差额0 | 41 | `ca79a9796c3262ce`，一致 |
| LINEAR_PERPETUAL | 通过，资金差额0 | 890 | `792aae41b8823561`，一致 |
| INVERSE_PERPETUAL | 通过，资金差额0 | 441 | `c99c1d5fa7182ab4`，一致 |
| LINEAR_DELIVERY | 通过，资金差额0 | 761 | `75e5d27f457a02fa`，一致 |
| INVERSE_DELIVERY | 通过，资金差额0 | 744 | `46b1f61a80de5095`，一致 |
| OPTION | 通过，资金差额0 | 1995 | `d62f0af9ef954f1f`，一致 |

六线均包含实际做市、限价下撤单、市价成交、终态/余额查询、用户执行推送、公共盘口与成交WS/REST身份核对，以及配置热更新、版本/非法输入拒绝和标的状态变更。衍生品另核对风险查询；现货另核对FOK、私有订单历史和成交归属；两种永续核对开平仓/只减仓，并各运行真实Core资金费/风险扫描/强平门禁及恢复后金额/持仓检查，门禁各14条命令，资金差额0。

两种交割到期后撤挂单和两条触发单，结算到CLOSED，核对幂等重试、相反方向损益流水及持仓/冻结归零；精确损益分别200000000 USDT最小单位与78431 BTC最小单位。期权OCO终态回执/用户查询/管理员时间线通过，CALL/PUT×实值/平值/虚值共6行到期结算通过，逐对权利金1000000000、taker费500000、maker费200000，资金差额0。

共8次真实Cluster快照冷恢复：实际请求ClusterTool snapshot，确认service与consensus有效快照各新增1条，保存原日志和目录，停止JVM，确认旧驱动失活后重启Core。每次恢复命令数及哈希均与停机前一致，没有重建空数据代替恢复。

保留所有红测、夹具修正和脚本失败记录。脚本的查询状态、JVM开放参数及驱动心跳超时错误均已修正；反向永续第一次门禁重启被保护拒绝后保留原数据复验成功。未放宽生产校验或金融断言。

实际命令、退出码、日志/运行包/源码SHA-256、逐产品业务标记及恢复数据见[机器证据](round61-runtime-20261025.json)。

```text
mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=AsyncFundingCommandTest,CorePerpetualFinancialMatrixTest,SystemLedgerControlTest,CommittedFundingReplayTest,CoreLifecycleStateTest,CoreRiskStateTest -Dsurefire.failIfNoSpecifiedTests=false test
```

```text
mvn -pl surprising-aeron-core/surprising-aeron-service,surprising-aeron-core/surprising-aeron-tools,surprising-aeron-core/surprising-aeron-benchmarks,surprising-price/surprising-price-provider,surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-provider,surprising-realtime/surprising-realtime-provider,surprising-gateway,surprising-maker -am -DskipTests package
```

```text
mvn -pl surprising-gateway -am -Dtest=SixProductLineContractLiveTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- 未执行浏览器/Flutter点击，本轮未修改HTTP/WebSocket及前端契约。
- 未验证三节点切主/强杀、资金费/结算/ADL在途崩溃。
- 未执行完整资金费定时周期、保险/ADL端到端调度、自动止盈止损执行及额外算法单/统计查询可选分支；ADL/保险金额及回滚只属于本轮67例回归的对应场景。
- 缺当前行情的分块快照续页是单元场景；8次真实Cluster恢复没有删除行情来制造同一场景。
- 未启动wallet，未运行JMH/JFR/压测，不作性能提升或全功能覆盖结论。

摘要归档后停止本轮进程、核对所属PID与监听端口，并清理临时目录；最终清理状态以机器证据cleanup为准。下一轮必须在本轮六线验收通过后才开始。
