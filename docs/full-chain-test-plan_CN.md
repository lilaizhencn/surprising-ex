# 全链路功能测试用例清单

本文是后续回归和压测的主清单。状态说明：

- `[x]` 已有本轮或现有报告中的验证证据。
- `[ ]` 尚未完成，需要后续执行。
- `[!]` 当前不适用或有设计限制，原因写在预期结果中。

## 本轮新增验证

- [x] TP close long：SELL TAKE_PROFIT，mark price >= trigger price 时生成 reduce-only 平仓单。
  预期：trigger condition 为 `GREATER_OR_EQUAL`，订单为 reduce-only，触发后状态推进到 `TRIGGERED`。
- [x] SL close long：SELL STOP_LOSS，mark price <= trigger price 时生成 reduce-only 平仓单。
  预期：trigger condition 为 `LESS_OR_EQUAL`，数量使用该档 `quantitySteps`。
- [x] SL close short：HEDGE + SHORT + BUY STOP_LOSS，mark price >= trigger price 时生成 reduce-only 平仓单。
  预期：`positionSide=SHORT` 传到 order-provider，不落到 NET。
- [x] TP/SL 多档位后端触发：同一 symbol 下两条满足条件的 trigger order 被 claim。
  预期：两档分别生成 `trigger-<triggerOrderId>` client order，每档使用自己的 `quantitySteps`。
- [x] TP/SL mark price 不可用。
  预期：trigger-provider 查不到触发事件对应的持久化 mark price 时，不 claim 条件单、不生成平仓单。
- [x] 强平早于 TP/SL。
  预期：如果强平已经清掉仓位，后续 TP/SL 触发时生成的 reduce-only 平仓单会被 order-provider 拒绝，条件单转 `TRIGGER_FAILED`，不会反向开仓。
- [x] 前端多档位录入和打通。
  预期：交易面板可以新增多条 TP/SL，每档选择平多/平空、触发价、数量；提交后走 gateway `trading-trigger` 下单；底部账户面板展示并支持撤销。
- [x] 撮合 HEDGE 仓位侧成交事件。
  预期：taker 的 `positionSide` 来自 order command，maker 的 `positionSide` 从订单表读取，成交事件保留 `LONG/SHORT`。
- [x] 风控 HEDGE 仓位侧扫描与强平候选事件。
  预期：账户持仓事件触发扫描时按 `positionSide=SHORT` 定位仓位，风险事件和强平候选事件不退回 `NET`。
- [x] 强平 HEDGE 仓位侧下单。
  预期：SHORT 仓位强平时预撤同侧 reduce-only，生成 `BUY + reduceOnly + positionSide=SHORT` 的系统市价单，并写强平审计。
- [x] 资金费 HEDGE 仓位侧扣款。
  预期：资金费候选从 DB 映射 `positionSide`，扣款时只消耗对应 `LONG/SHORT` 仓位保证金，ledger reference 包含仓位侧。
- [x] ADL HEDGE 仓位侧审计。
  预期：ADL 更新/释放对应 `positionSide` 的仓位和保证金，并在 `adl_events.target_position_side` 记录被减仓仓位桶。
- [x] 撮合侧 mark price 不可用兜底。
  预期：order-provider 校验后到 matching 前 mark price 过期时，matching 返回 `MARK_PRICE_UNAVAILABLE`，不撮合、不生成成交。
- [x] 真实 provider full-stack smoke。
  预期：在真实 PostgreSQL/Kafka 和 instrument/candlestick/index-price/mark-price/order/matching/account/risk/liquidation/funding/insurance/ADL/websocket/trigger/gateway/market-maker provider 下，仓位模式切换、HEDGE LONG/SHORT 持仓、TP/SL OCO、逐仓保证金、部分成交撤单、撮合/account 重启恢复、资金费、强平、保险基金、ADL、公开/私有 WebSocket 推送和会计不变量全部通过。

验证命令：

```bash
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  mvn -q -pl surprising-trading/surprising-trigger-provider -am \
  -Dtest=TriggerOrderServiceTest,TriggerOrderRepositoryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  mvn -q -pl :surprising-risk-provider,:surprising-liquidation-provider,:surprising-funding-provider,:surprising-adl-provider,:surprising-matching-provider,:surprising-order-provider,:surprising-trigger-provider -am \
  -Dtest=RiskServiceTest,LiquidationServiceTest,FundingRepositoryTest,AdlRepositoryTest,MatchingServiceTest,OrderValidatorTest,TriggerOrderServiceTest,OrderServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
  mvn -q -pl :surprising-integration-test -am \
  -Dtest=PostLiquidationFundingInsuranceAdlIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

POSTGRES_PORT=55433 \
PAIR_COUNT=3 LOAD_CONCURRENCY=2 BOOK_DEPTH_LEVELS=5 RUN_FAILURE_SCENARIOS=true \
BUILD_SERVICES=auto KEEP_TMP=true WS_TIMEOUT=240 \
JAVA_HOME="$(/usr/libexec/java_home -v 21)" \
  ./scripts/full-stack-real-config-smoke.sh

npm run lint
```

结果：以上后端定向测试、真实 provider full-stack smoke 和 Web lint 均通过。full-stack smoke 日志保留在 `/tmp/surprising-full-stack-real-config.3qu3b9`；本机默认 `5432` 被本地 PostgreSQL/SSH 占用，所以该轮使用 `POSTGRES_PORT=55433` 隔离 Docker PostgreSQL。ADL 事件表核对结果为 `1|NET`，说明真实 ADL 场景写入了 `target_position_side`。

## 下单

- [x] LIMIT GTC 开仓。
  预期：订单 `ACCEPTED`，冻结保证金，撮合未成交时 remaining=quantity。
- [x] MARKET IOC 开仓。
  预期：使用新鲜 mark price 计算保护价和 notional，成交后账户结算。
- [x] FOK 无法完全成交。
  预期：订单终态不保留开放数量，冻结保证金释放。
- [x] IOC 部分成交。
  预期：已成交部分迁移到持仓保证金，未成交部分释放订单冻结。
- [x] GTX/post-only 不应吃单。
  预期：如果会立即成交，撮合返回 `POST_ONLY_WOULD_TAKE`。
- [x] reduce-only 平仓。
  预期：只能减少已有仓位，超过可平量或反向开仓被拒绝。
- [x] LIMIT 价格带保护。
  预期：BUY 价格超过 mark price 上边界拒绝，SELL 价格低于下边界拒绝。
- [x] 市价单 mark price 不可用。
  预期：order-provider 拒绝 `mark price unavailable`，matching 侧也能拒绝 `MARK_PRICE_UNAVAILABLE`。
- [ ] index source 不足导致 mark price 停止刷新后的全链路验证。
  预期：普通下单 fail closed；撤单仍可用；系统进入 cancel-only/reduce-only 应急状态需要单独设计和验证。

## 撤单

- [x] 未成交订单撤单。
  预期：订单状态 `CANCELED`，remaining 清零，冻结保证金全部释放。
- [x] 部分成交后撤剩余。
  预期：只释放未成交部分冻结；已成交部分的仓位保证金和手续费保持。
- [x] 重复撤单。
  预期：不会产生重复 cancel command，也不会重复释放冻结余额。
- [x] matching 重启后撤单/继续撮合。
  预期：matching 从 PostgreSQL 开放订单恢复订单簿，恢复后可继续成交或撤单。

## 持仓模式

- [x] NET 净仓。
  预期：同一合约同一保证金模式下只有 `positionSide=NET` 一行，买卖会增加、减少或翻转净仓。
- [x] HEDGE 双向持仓链路的基本支持。
  预期：普通订单、条件单、账户持仓已经支持 `LONG/SHORT` 字段传递和持久化。
- [x] HEDGE 关键资金链路单元回归。
  预期：matching trade、risk event/candidate、liquidation order、funding payment、ADL event 都保留仓位侧。
- [x] HEDGE 真实进程 smoke。
  预期：full-stack smoke 中同一用户同一合约能同时打开 `LONG` 和 `SHORT`，私有 WebSocket `position`/`positionRisk` 推送分别包含 `LONG` 和 `SHORT`。
- [ ] HEDGE 全链路生产级回归。
  预期：LONG 和 SHORT 同时存在时，order/matching/account/risk/funding/liquidation/insurance/ADL/WebSocket 全链路都按方向隔离计算。
- [x] 持仓模式切换保护。
  预期：有开放仓位、开放订单或开放条件单时，拒绝切换净仓/双向模式。

## 保证金模式

- [x] CROSS 开仓/平仓。
  预期：账户级余额承担 PnL/手续费/资金费，强平按账户组风险计算。
- [x] ISOLATED 开仓/平仓。
  预期：订单保证金成交后迁移到持仓保证金，平仓释放对应比例持仓保证金。
- [x] 手动追加逐仓保证金。
  预期：减少风险，写出 position updated outbox。
- [x] 安全减少逐仓保证金。
  预期：通过新鲜风险快照校验后释放可减部分。
- [x] 超额减少逐仓保证金。
  预期：拒绝，不产生负持仓保证金。
- [x] 同一 symbol 跨保证金模式冲突。
  预期：另一个模式有活跃仓位/订单/条件单时，普通开仓和条件单拒绝；reduce-only 风险降低订单允许。

## 止盈止损

- [x] 单条止盈。
  预期：达到 mark trigger price 后生成 reduce-only 平仓单。
- [x] 单条止损。
  预期：达到 mark trigger price 后生成 reduce-only 平仓单。
- [x] OCO sibling 取消。
  预期：同一 `ocoGroupId` 内只 claim 一条触发单，其他 sibling 自动取消。
- [x] 多档止盈/止损。
  预期：每个价格是一条独立 trigger order，每档有自己的 `quantitySteps`，满足条件时逐条触发。
- [x] 真实进程 TP/SL OCO 触发执行。
  预期：full-stack smoke 中 trigger-provider 经 gateway/order-provider 生成 reduce-only 平仓单，并完成撮合、账户结算和 WebSocket fanout。
- [x] 前端多档位。
  预期：用户可以一次配置多行，前端逐条调用 trigger order API；提交后可查询和撤销。
- [ ] 多档位总数量前置校验。
  预期：同一 symbol/side/positionSide 的开放 TP/SL 总待平量不应超过当前可平仓位。当前主要依赖 reduce-only 在触发执行时兜底，建议后续补前置聚合校验。
- [x] 强平早于 TP/SL。
  预期：如果 mark price 先触发强平，强平 reduce-only 订单优先进入风险处理；之后 TP/SL 触发时应因无可平仓位被拒绝或失败，不得重新开仓。
- [x] mark price 不可用时 TP/SL。
  预期：不 claim 触发单，不生成平仓单；价格恢复后按最新 mark 判断。

## 撮合

- [x] maker/taker 成交。
  预期：撮合结果、maker/taker trade、订单状态、订单簿 delta 都写出。
- [x] 自成交保护。
  预期：会自成交的订单被拒绝或阻断。
- [x] command 幂等。
  预期：同一 commandId 重放不重复撮合。
- [x] order fill guarded update。
  预期：remaining 不足或订单状态非法时 fail fast，不静默夹取数量。
- [x] 开放订单簿恢复。
  预期：matching 重启从 DB 恢复未完成订单。
- [ ] 高频做市持续运行下撮合稳定性。
  预期：做市程序持续报价、撤换单、taker 流量同时存在时，订单簿无交叉、无负 remaining、Kafka lag 可控。

## 账户资金

- [x] 下单冻结保证金。
  预期：available 减少、locked 增加，余额行 `FOR UPDATE` 防并发透支。
- [x] 成交迁移保证金。
  预期：订单冻结按实际成交价迁移到持仓保证金，多冻结部分释放。
- [x] 平仓 PnL。
  预期：按共享合约公式计算 realized PnL，写 ledger。
- [x] 手续费。
  预期：maker/taker 使用下单时手续费快照，不受成交后费率变更影响。
- [x] 成交幂等。
  预期：`account_processed_trades(symbol, trade_id)` 防重复落账。
- [x] 负余额保护。
  预期：余额释放和扣款使用 guarded update，失败时 fail fast。

## 风控、强平、保险基金、ADL

- [x] 风险快照。
  预期：账户组和逐仓风险按新鲜 mark price 计算。
- [x] 强平候选。
  预期：候选复核使用最新风险快照和实时持仓，不一致时取消候选。
- [x] 强平下单。
  预期：前置撤用户 reduce-only，生成系统 reduce-only 市价单。
- [x] 强平费。
  预期：按可收 collateral 封顶，不生成新的 deficit。
- [x] 保险基金覆盖亏损。
  预期：只减少显式 deficit，不增加用户 available。
- [x] ADL。
  预期：保险基金不足时按队列减少盈利方仓位并清除剩余 deficit，审计事件记录 `target_position_side`。
- [ ] 高频做市和并发用户流量下强平/ADL。
  预期：强平、保险基金、ADL 与普通成交并发时不出现重复扣款、负余额、非法 OI。

## WebSocket

- [x] 公开 depth 推送。
  预期：下单、成交、撤单后收到 depth snapshot/delta。
- [x] mark/funding 推送。
  预期：mark price 和 funding 事件可被客户端接收。
- [x] 私有订单推送。
  预期：只推给认证用户，不泄漏给其他订阅者。
- [x] 私有成交/持仓推送。
  预期：account 结算后收到 match/position 事件。
- [x] 私有风险推送。
  预期：`accountRisk` / `positionRisk` 按用户隔离 fanout。
- [ ] 多用户长连接 fanout 压测。
  预期：大量用户订阅时私有推送无串号、延迟可观测、失败连接清理不影响其他连接。

## 性能与稳定性

- [x] exchange-core 裸撮合 benchmark。
  预期：只代表内存撮合能力，不代表系统吞吐。
- [x] 真实 order/matching/account/websocket/gateway 做市商压力 smoke。
  预期：订单全部成交结算，Kafka lag 最终为 0，无负余额，无非法 OI。
- [x] matching/account/WebSocket 本机多节点 smoke。
  预期：consumer group 成员可识别，WebSocket 节点都收到 depth。
- [x] account failover。
  预期：停掉一个 account 节点后剩余节点消费完 match trades，最终 lag 为 0。
- [x] matching owner failover。
  预期：停止 symbol owner 后重启，开放订单恢复且可继续成交。
- [x] 真实 provider full-stack smoke。
  预期：不全量打包，`BUILD_SERVICES=auto` 在 jar 未变化时跳过 Maven package；脚本覆盖真实 provider 链路和做市商 run-once 铺单，最终 WebSocket/accounting invariants 通过。
- [ ] 生产级长时间全链路压测。
  预期：高频做市持续运行、盘口参数跟随主流交易所、普通用户高并发下单、强平/ADL/资金费同时发生，并输出逐节点 p50/p95/p99 和数据库/Kafka/JVM 指标。

## 当前设计限制

- [!] TP/SL 多档位不是单个“组合订单”原子提交。
  原因：后端已有单条 trigger order API，多档通过多条 trigger order 表达；前端当前逐条提交。部分提交失败时需要前端提示用户检查开放条件单列表。
- [!] 当前 TP/SL trigger price type 只支持 `MARK_PRICE`。
  原因：后端 `TriggerPriceType` 目前只定义 `MARK_PRICE`。如要支持 Last/Index 触发，需要新增价格流、claim 条件和 UI。
- [!] 主流交易所真实盘口订阅驱动做市尚未实现。
  原因：现有 market-maker stress 使用静态深度/价差/数量配置，不能代表真实市场微结构。
