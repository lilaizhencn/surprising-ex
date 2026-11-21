# 性能与故障验证记录

## 2026-11-19：本地交易链路间歇停顿，Aeron 客户端等待路径修复

**结论：部分验证；已修复可复现的空转/短命对象分配缺陷，运行中的故障尚未闭环。**

### 范围、环境与边界

- 所有时间为上海时间（Asia/Shanghai）。本地 `master` 基线 `850b2e8170730fe8e908b388e48e267e2af388fd`；本轮只在原 checkout 修改，未提交、未推送、未重启或替换运行 JAR。
- macOS 27.2.1 arm64，15 CPU、24 GiB；HotSpot OpenJDK 27.0.1.0.3、Maven 3.10.0，检查时磁盘可用约 431 GiB。原有 `init.sql` 脏改动逐字节校验一致。
- 复用启动时已有的 `surprising` JFR（maxsize=64 MB、maxage=30 min），没有启动大堆转储、全堆直方图、压测或交易。仅有界公开盘口/匿名 WS、进程/GC/线程/FD 与 Redis PING/INFO/SLOWLOG 元数据诊断；未读业务 key、未直连业务 DB、未改 Redis 配置。
- 单元测试使用伪 Session；不是正式真实单节点、单 matcher、全局 in-flight=256 的性能验收。未宣称吞吐容量、端到端延迟改进或零 B/op。

### 现场时间线与内存证据

- Gateway/Core/Maker/Redis 均保持运行，launchd `runs=1`，未见该时段退出/重启；有界系统记录未见对应睡眠/唤醒或内存杀进程证据。
- 21:05:41.131 首见会话索引因 Valkey 不可用暂停；21:05:41.289 realtime batch QueryTimeoutException；21:05:42.258 后做市公开盘口 503，21:05:43.136 明确 `RATE_LIMIT_STORAGE_UNAVAILABLE`，随后实时/WS 清理失败与 Aeron image unavailable。这些是故障链证据，不据此把 Redis 或 Aeron 超时认作最初根因。
- 已有 JFR 的 21:05 CPU 样本出现整机负载 100%。21:06 的部分 safepoint 同步等待长达约 0.51 s，但不等于 ZGC STW；部分采样发生在诊断期间，不能倒推为 21:05 首因。21:04–21:06 现有 Gateway GC 日志最大标记 Pause 约 0.620 ms；未见 Allocation Stall/OOM。并发 GC 的 4.214 s 不能当成全停顿时间。
- 21:06:58–21:11:08 的已有 JFR：采样分配权重总计约 46,958.8 MiB；`ConcurrentHashMap$EntryIterator` 占 43.79%，`EgressDispatcher` 捕获 lambda 占 33.76%；Aeron `dispatchResponses` 栈合计约 77.5%。权重为采样估计，不是精确分配计数。
- 同窗回收后堆约 340–490 MiB；DirectBuffer 始终 19 个、memoryUsed=8,739,329 B；活动线程从 228 到 230（峰值 232）。该窗 GC pauses 总计 4.66 ms，最大 0.0475 ms。21:21 左右另读到堆 used=362 MiB、capacity=974 MiB、max=1024 MiB，Gateway RSS 约 1,608 MiB、FD 604（此前 609）。这些不支持“当前持续单调泄漏”的断言，也不能排除长时或未观测 native 泄漏；NMT 未启用，不擅自补零。
- Redis 是本机原生进程，不是模拟器中的 Redis。INFO 显示 resident 约 4.6 MB、used 约 1.8 MB、5 连接、0 blocked client；有界 SLOWLOG 元数据最长约 77 ms，21:05:43 的 EVALSHA 约 10.5 ms。150 次、10 次/秒 PING：p50 约 0.104 ms、p99 约 0.403 ms、max 约 0.997 ms，无 200 ms 超时。只能说明采样时服务端快，不能排除故障时调度/传输/客户端排队；不调大 200 ms 超时来掩盖。
- 21:24:49 再测：SPOT/1001 与 LINEAR_PERPETUAL/604 公开盘口 HTTP 200，约 106/18 ms，序列继续变化但 bids/asks 均为空；匿名 WS `/ws/v1` 握手 101、约 23 ms。做市仍在 21:13、21:23 出现输入/账户读取 503，故不据健康检查或握手成功宣称报价恢复。

### 确认的代码缺陷与局部修复

1. `AeronClientPool.EgressDispatcher` 已计算短时 `waitNanos`，却用“有任何 pending/mailbox/deferred request”阻止 park：等待网络响应、未连接、窗口满、反复 ADMIN_ACTION 时都会无进展空转。响应越慢，等待窗口中 CPU/分配放大越明显；这可形成拥堵反馈，不需要真正的保留内存泄漏。它是确认的缺陷与故障放大因素，尚不能证明是所有停摆的唯一根因。
2. 改为只在**立即可以投递的新排队请求**存在时跳过 park；保留 `parked` 发布及入队 `unpark` 防止丢唤醒。未进展的 in-flight 等待继续既有约 100 µs 短轮询，完全空闲仍按原策略上限约 1 ms。OS 调度会延长实际等待，不把这些数值当端到端延迟保证。
3. 每 Lane 缓存响应 BiConsumer；`SurprisingAeronClient.drainResponses` 空集合早返，不再每次空轮询创建捕获 lambda/entry iterator。实际有响应时的对象分配仍存在，不声称零分配。
4. 不改变请求槽/窗口边界、offer 顺序、幂等号、超时值、心跳间隔、已接收结果未知语义、风控、权限、资金核查或失败关闭。没有全局改 matcher/account worker、自行关闭模拟器或改 Redis。

### 测试：旧失败、新通过

```bash
mvn -q -pl surprising-aeron-core/surprising-aeron-client -am \
  -Dtest=AeronClientPollingIdleTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -q -pl surprising-aeron-core/surprising-aeron-client -am \
  -Dtest=AeronClientPollingIdleTest,AeronClientAgentTest,AeronClientPoolTest,SurprisingAeronClientConcurrencyTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
mvn -q -pl surprising-aeron-core/surprising-aeron-client -am test
```

- 修改生产代码前，新增 4 项测试全部因等待不到真实 dispatcher `TIMED_WAITING` 而失败：等待响应、断线排队、窗口满、反复 ADMIN_ACTION。修改后 4/4 通过，且恢复连接/释放响应/结束 ADMIN_ACTION 后 future 均正确完成，验证复用同一响应 consumer。
- 相关四个测试类共 39/39 通过（4+23+8+4），覆盖原有顺序、背压、重复关联、结果未知、超时、连接恢复与关闭语义；不代表真实资金端到端审计完成。
- 全客户端 75 项中 74 通过，1 失败：`SurprisingAeronClientMediaDriverTest.repeatedConnectionTimeoutsCloseAeronBeforeTheOwnedDriver`。原测试硬编码本地默认集群端点并要求连接超时；当前本地集群实际在运行，连接成功导致“应抛异常”断言失败。失败在 connect 阶段，非本次响应轮询逻辑；未为过测试改它或重跑这条会接触现有集群的用例。测试只连接，未提交业务命令。没有对旧版本做独立基线运行，故明确标记环境依赖而不虚称已完成全套基线对比。
- `git diff --check` 通过。未执行真实订单、资金或持仓变更，无正式资金守恒/全链路性能结论。

### 证据归档与剩余工作

- 既有 JFR `2026_11_19_21_06_58.jfr` SHA-256：`dffb5440c80c5e6e60f1264a6a03d3baa810ee075f6471f2b9dc0e96bc2faf57`。只读提取的 summary、allocation-by-class/site、gc-pauses、修复前结果摘要位于 `surprising-aeron-core/surprising-aeron-client/target/pt133-diagnostics/`（构建产物，未跟踪）。原 JFR 为运行中环形仓库，未复制/删除/停录，可能按原策略自动轮转；并非永久归档保证。
- 当前 Gateway（PID 67708）仍是旧版本。获得重载授权后需构建依赖并替换/重启 **`com.surprising.product-line.local-perpetual.gateway`**；它承载两个产品上下文及内嵌 price/realtime/lifecycle。该补丁不要求重启 Core、Maker、Redis/Postgres/Kafka。网关重载会短暂中断 REST/WS、行情及生命周期发布，恢复需核对读视图重新建基线、真实报价和 WS 订阅；运行中的自动做市如何协调应先由用户决定。此轮未重启。
- 重载后须按同窗继续记录 CPU、分配、回收后堆/direct/native、线程/FD、公开盘口非空与更新、WS 真实订阅和做市依赖恢复，观察超过此前反复故障间隔，方可闭环。当前不能证明长稳修复或全部故障仅由本缺陷导致。
- 另见独立 FundCheck SQL 契约错误：`init.sql` 的 `gateway_wallet_withdrawals.amount` 是 TEXT，不能直接 SUM；该表也没有 `tx_hash` 列。它每 5 分钟触发提现核查失败，是独立问题，不解释所有 API/WS 不可用。本轮未修改资金审计、更未清除/绕过提现熔断；不能简单把查询结果改零或凭猜测映射 txHash。

## 2026-11-19 21:34–21:59：单网关重载实测与空报价假健康修复

- 获准只重载网关后，21:33:46 向原 Gateway PID 67708 发 SIGTERM；launchd 恢复新 PID 90813，21:34:01 liveness=200。Core 67587/70427、Maker 68116、Redis 66120 未重启。没有调用做市控制、手工下撤单、清熔断或改业务数据。
- 新执行包 SHA-256 `a7344e5e1ab2268f162beab3f1c9e44e7590d06b7f2da7b1fc8f19d6fca73fc8`。新旧所有项目 class 对比仅 AeronClientPool/SurprisingAeronClient 及内部类 10 项变化；内嵌 client JAR 和编译产物匹配。原执行包保留在忽略的 `target/pt133-diagnostics/gateway-before-reload.jar`。
- 21:35:43–21:55:44 完成低频 20 分钟观察，每 30 秒一组、共 41 组。两个产品盘口各 41/41 HTTP200，中位数约 2.39/1.67 ms、最大 64.65/9.93 ms，序列持续推进，但所有采样买卖档均为空。此前 21:15/21:24 已测到空盘口，故不是这次重载新引入，不能说报价恢复。
- 默认深度匿名 WS 在 21:40:35–21:41:35 收到 SPOT 419、LINEAR_PERPETUAL 464 次序列更新，均为空内容，无 WS 错误。自定义 depth=5 的长期订阅只收初始空基线是现有“无档位变化不发送空 delta”的协议优化，不是据此认定 WS 挂了。诊断解析需解开 `data.value`（VersionedEvent）。
- 观察窗未新增 Valkey 超时、RATE_LIMIT_STORAGE_UNAVAILABLE、router.failure 或 Maker503。已有 Maker JFR 的 793 次 orderbook404 全集中 21:33:59–21:34:13，为网关启动路由就绪窗口；不能混作稳定运行阶段失败。
- 补丁后的已有 JFR 21:36:08–21:40:12 分配权重 9633.63 MiB/244 s，约39.5 MiB/s，旧窗口约188 MiB/s；不是同负载压测，不外推吞吐。旧77.5%轮询分配热点已不再突出。21:40:12–21:44:24 分配仍约39 MiB/s、GC后堆332–354 MiB（首末均344），DirectBuffer保持19个/8,739,329 B、活动线程180。Gateway总CPU仍177–480%，主要已见回放 matcher/account 线程，不能说空闲CPU已完全优化。RSS20分钟由约1.60升至1.91GiB、FD621降606；未据短窗排除native/映射驻留增长，未做大堆转储或强制GC。
- 21:49:43 公开品种 SPOT1001、PERP604 均TRADING；公开行情探针3/2来源达标，BINANCE/BYBIT/OKX均连接且源龄约45–280ms。这仅证明价格源，不等于Maker参考深度、补单回执或用户资金条件通过。

### 确认但尚未加载的 Maker 状态缺陷

`MarketMakerService` 原本在启用参考行情而 reference=null 时产生空 QuotePlan、执行原有撤空保护，随后仍 `markSuccess`/CYCLE_SUCCESS。离线回归两项在旧代码均失败（预期DEGRADED、实际RUNNING）。新代码保留原有空计划撤单对账，不新增替代价格、不放宽资金/权限/风控；缺少必要参考时标记REFERENCE_MARKET_UNAVAILABLE/CYCLE_FAILED，不再启动成功报价后的交易步骤。参考恢复、正常补单成功后才能恢复RUNNING。

另外给已有补单拒绝和该参考缺失分支增加每进程每5秒最多一条 `maker.quote.unavailable`，仅产品/策略/品种与静态代码，不含账户、余额、订单或私有回执。完整细节仍只在既有内存运行事件中。本补丁只在源码和离线测试中，运行中Maker未重新构建替换或重载，不能称其已经修好现场报价。

现场究竟落在参考缺失、风控/业务拒单、暂停/覆盖设置哪一支，尚无足够授权内的运行状态证据。最小额外材料是这两个产品/品种的脱敏系统状态：enabled/paused、status/lastError/lastCycleTime、最近CYCLE_FAILED的静态原因与拒绝计数、参考深度freshness；不需要用户余额、订单明细、资金变更或清熔断。没有伪造管理员头、没有访问受限管理接口。若改走本轮新增静态诊断，则须先另行授权仅Maker重载；本轮不做。

本轮最终相关离线测试：MarketMakerServiceTest、QuotePlannerTest、RestReferenceMarketProviderTest 共 96 项，0失败/0错误；新回归覆盖现货/永续参考缺失、原撤空保护、参考恢复正常补单。新Maker代码未加载。`git diff --check`通过，init.sql校验一致。


## 2026-11-19 22:05 上海时间：获批仅重载Maker，现场拒绝码已证实

- 授权边界：仅既有local-perpetual Maker；未重启Gateway/Core/Redis/DB，未发人工交易或资金指令，未读用户余额/订单明细，未改策略阈值或风控。
- Maven命令：`mvn -q -pl surprising-maker -am package -DskipTests`，成功。原Maker依赖中Aeron client与新Maven聚合产物不同，因此运行包只装入本轮编译的MarketMakerService及其5个内部类，所有原配置与依赖保持逐字节不变；未趁本次授权更新Maker的Aeron依赖。
- 旧PID68116；2026-11-19T22:05:14.526145+08:00发送一次SIGTERM给既有launchd job，keepalive启动新PID9208（22:05:17上海时间）；22:05:24.541应用Started，22:05:25.450出现新增补丁日志。旧PID不存在，9096监听仅9208；无重复启动脚本。
- 原运行JAR SHA256：`84864450ece5b6bd6906016124f66e4239a06abc70c67942d8d4d10fbb415058`；新实际运行JAR SHA256：`19808a9ecf7eb4b47762d6c57615a42cbb00adabaf5f7b8b5b1ea7073d137892`；所有改变的class与Maven编译结果逐字节一致，实际运行包校验一致。
- 真实运行日志（不是单测夹具）：22:05:25.450 SPOT/1001，22:05:35.491 LINEAR_PERPETUAL/604，以及其它四品种/两策略均观测到`INSUFFICIENT_AVAILABLE_BALANCE`。这是Core已返回的补单业务拒绝，不是HTTP503或WS断流。全局限频日志只保存首个拒绝码，不能推断每笔单、每个账户都被拒绝。
- 代码依据：MakerService 1026–1055组批并消费Core回执；AccountBook 4176–4181/4494–4498在用户不存在、资产余额不存在或availableUnits小于requiredReservation任一情形返回相同拒绝码。判断在reserve之前，不能由错误码直接断言余额数值、冻结泄漏或需要充值。Maker quoteAccount 796–800在必要参考缺失时只生成空计划，因此这些实际补单拒绝周期已产生有效报价计划，并非仅因本地空盘口自引用而根本不生成报价。
- 22:06:08–22:06:38单次30秒公开探针：两产品API均200且序列推进，仍全部0买/0卖；匿名WS两订阅ACK，现货171次、永续173次更新，全部为空；无WS错误、无新增Valkey超时/503。新时间窗的7次普通cycle异常全部在22:05:16旧进程关闭阶段，早于新PID启动，不混入新进程稳定性结果。
- 结论：部分验证；诊断/假健康补丁已加载，但盘口未恢复。当前明确阻塞是资金准入业务拒绝，其底层是缺失账户/资产、真实可用不足还是异常冻结尚未区分。不以诊断补丁宣称恢复，不盲改资金、阈值或重启。下一步需要另行授权只读既有做市测试账户的存在性、可用/冻结及活动预占汇总，经合法入口取证；本轮没有该项读取。原FundCheck独立错误仍未修，未清熔断。
- 证据位于ignored target/pt133-diagnostics：maker-approved-build.log、maker-authorized-reload.json、maker-after-reload-evidence.json、maker-reload-ws-probe.jsonl；原运行JAR已保留。相关96项离线测试沿用本轮已通过结果，重载后无额外源码逻辑修改。


### 2026-11-19T22:15:14.650815+08:00：获批只读余额对账，但合法认证入口尚未就绪

- 只尝试标准psql现有认证：`psql -X -w -h 127.0.0.1 -p 15432 -U surprising -d surprising_exchange`，预设BEGIN READ ONLY/SHOW transaction_read_only/ROLLBACK。连接在认证阶段以“no password supplied”结束，未读任何业务记录；未改密码/pg_hba，未从进程或配置提取secret，未换身份绕过。当前子代理未获Vault/Form/密码管理器工具，已查工具目录确认，需父经正常保密登录流程补足连接。既有业务只读权限已批准，不需要重复请求同一权限。
- Core正规查询仍需合法身份：GatewayDirectAccessFilter 121–130验证身份及用户归属；AccountController 89–95/202–207管理员余额接口；未伪造X-Admin-User-Id或X-User-Id，未调用内部免认证查询。
- 金额契约已继续核查：ProductTradingRules 134–140现货卖预占quantitySteps*quantityStepUnits，买预占priceTicks*quantitySteps*notionalMultiplierUnits+费用；164–185永续按开仓保证金和费用预占，不能把quantitySteps直接当USDT。AccountBalance 42–56 reserve/release等额移动available/locked；AccountBook 3573–3588终态撤单释放remaining reservedUnits并发布变化。
- MakerService 873–917先补已成交档、再小批确认撤改、回补失败停止继续撤空；该设计不能单靠代码排除现场残留冻结/其它持仓占用。尚无现场金额，不能宣称发现或排除泄漏/单位错误。
- init.sql 982–984明确Core才是在线余额、预占、持仓权威；已声明的相关PG表是ledger/adjustments，不能用账本净额替代当前Core冻结数。正式对账必须区分订单锁定、持仓保证金、划转/其它锁定，并按同一产品/资产/Core版本取一致快照。
- 额外纯离线测试：[{"name": "com.surprising.aeron.service.order.ReservationRuntimeTest", "tests": "1", "failures": "0", "errors": "0", "skipped": "0"}, {"name": "com.surprising.aeron.service.core.CoreSpotAssetUnitsTest", "tests": "18", "failures": "0", "errors": "0", "skipped": "0"}]。覆盖非单位数量步、部分成交、撤改及快照恢复，未连接运行Core、未下真实订单；仅证明这些测试场景，不证明现场账户正确。
- 本阶段未改生产源码、未重启、未提交push，init.sql校验一致。余额/冻结/预占实测仍待合法认证，不能补造数据。


### 2026-11-19T22:26:03.012937+08:00: configured authentication verified; previous missing-config inference corrected

- Standard local.env POSTGRES_* configuration was used only in process memory for the matching local PostgreSQL service. Standard psql authentication SUCCEEDED, transaction_read_only=on. No credentials were printed, persisted, or placed in command-line arguments. The earlier absence of automatic psql password did NOT establish missing project configuration. No need to ask the user to provide existing DB settings again.
- Runtime Maker configuration explicitly identifies test users 4 and 5, for SPOT (1001/1002/1003) and LINEAR_PERPETUAL (604/653/866). All business SQL predicates were restricted to these users and relevant products; output contains aggregates only.
- PG confirms both gateway users NORMAL and historical test-funding adjustments. This is NOT evidence of current Core balance availability. Old projection cutoffs: SPOT 2026-11-19 14:03:36.452637 Asia/Shanghai; LINEAR_PERPETUAL 17:59:28.639871 Asia/Shanghai. Scoped core_user_fact_projection rows=0. These stale/possibly obsolete projections cannot prove current Core users/assets absent or zero balances. Historical amounts remain raw asset units, not a guessed uniform decimal scale.
- Existing bootstrap login binding resolves to secrets/local-accounts.env. Its ADMIN_EMAIL/ADMIN_PASSWORD were used only in memory in ONE standard POST /api/v1/auth/login attempt, following the script's standard request fields and client metadata. The bootstrap script itself was NOT executed. Login returned HTTP403; no token was obtained, stored, returned, or harvested. No alternate identity, spoofed admin header, permission/authentication change, seed-password guess, or internal unauthenticated query was used.
- DB authentication is now solved; saved development admin material also EXISTS. The unresolved boundary is the service rejecting the normal admin login, not absent configuration. Exact 403 reason has not been established; do not assert password error, frozen user, or IP block. Next step requires completing the normal admin login/required verification, with account and trading read permissions. No chat password request is needed.
- Current Core available/locked/reservation totals remain UNREAD. A combined historical order aggregation failed and was not treated as zero orders/reservations; statement timeout remained bounded at 5 seconds. Smaller indexed adjustment aggregates succeeded. Evidence: maker-pg-scoped-summary.json and maker-standard-auth-boundary.json, with no credentials or order details.
- No production source change, restart, manual trading/funding command, commit, or push in this phase. Original init.sql checksum preserved.


### Terminology correction: PG metadata is not an online balance projection

The previous inference that PG balance projection stopped at the two watermark timestamps is RETRACTED. core_projection_watermark(product_line,last_export_sequence,updated_at) tracks the committed order/history export prefix, not balance updates. ProjectionWatermarkRepository 18-24 advances it only when the export sequence increases; CommittedOrderProjectionService 275-380 writes historical orders/executions/receipts and related historical facts. Assembly is conditional on surprising.trade-export.enabled=true (CommittedProjectionConfiguration 16-18). A static updated_at alone does not prove a failed consumer.

core_user_fact_projection has product_line,export_sequence,user_id,user_revision,raw_user_delta, and NO timestamp column. Current tracked source and running Gateway project classes have no literal references to this table; its leftover structure/empty rows must not be treated as current user/asset/balance truth. No claim about its historical raw payload schema is made without the old writer.

Current AccountService 33-35,66-81,98-120 reads Core-derived realtime views through ValkeyUserQueries, not PG. ValkeyUserQueries 39-43,69-83 requires READY/current-prefix views and has no PG balance fallback. The actual funds admission is Core AccountBook 4176-4181. Standard HTTP balance reads are therefore a realtime read view, not literally a direct read of Core memory. The PG watermark timestamps do not establish the cause of current INSUFFICIENT_AVAILABLE_BALANCE.

Separately, the prior admin login request declared X-Auth-Client=admin but omitted Origin. GatewayBearerFilter 34-43 deterministically rejects this request as ADMIN_ORIGIN_DENIED before AuthController/password checks. Existing bootstrap http helper also lacks Origin. No further login has yet been attempted in this correction phase.


### 2026-11-19 22:52 Asia/Shanghai: continuous operation and stack retention

User corrected the request to STACK information, not order reconciliation. Stop active business investigation, login attempts and sampling. No new probes or routines.

Existing launchd jobs are running/keepalive: Gateway 90813, Maker 9208, perpetual Core 67587, spot Core 70427. No PID changed and no service was reloaded in this stage. They are independent of the terminal within the current GUI login, not guaranteed across logout, sleep or shutdown. No power settings changed.

Existing Gateway/Maker JFR.check verified recording surprising RUNNING: default, maxsize=64m, maxage=30m, dumponexit=true, stackdepth=128; JavaExceptionThrow stackTrace=true/throttle=100/s; JavaErrorThrow stackTrace=true. Both Core launch jobs already specify equivalent recording limits, but only their launch arguments and liveness were checked in this stage, not their live recording state. GC/safepoint files already use filecount=5,filesize=20M; account for the active file separately. Application INFO logs already rotate at 20MB/file, retain 3 days, and cap archives at 100MB plus the active file.

Offline validation against the running Gateway artifact's own logging dependencies PASS: a msg-only PatternLayout implicitly renders an attached Throwable stack. Explicitly adding percent-ex is unnecessary and does not justify reload. Calls logging only an exception message cannot gain a missing Throwable stack through configuration.

Diagnostic base: ~/.local/share/surprising-ex/perpetual-pmm-20/runtime/{local-perpetual,local-spot}. GC: logs/*-gc.log; application logs: logs/<product-line>/*.log; recordings: jfr/. Diagnostic logs/jfr directories now all have mode 0700 (perpetual already did; spot changed from 0755). Raw JFR can contain sensitive environment data: do not share it unfiltered. No OOM heap dump, forced GC, crash or thread dump was enabled/generated.

Limits: 64MB/30m applies per active recording, NOT to the entire directory. Historical perpetual JFR directory has 176 files/~1933MiB, spot 8/~13MiB; nothing deleted. Existing launchd stdout logs/{gateway,maker,core-node0}.log are plain files, not covered by application rotation; currently ~71-267KiB each. No new unbounded recording/logging or resident rotation process was introduced. OmitStackTraceInFastThrow remains true; do not guarantee all repeated fast exceptions or all hangs produce complete stacks.

Only offline formatting validation, parameter/liveness checks and diagnostic file metadata were inspected at this stage; no current business results read. init.sql matches its retained baseline hash. All existing source/test/init.sql edits preserved. No commit/push. Stop and wait for the next user notification.


### 2026-11-20 07:24 Asia/Shanghai: official stack stop and offline diagnosis

User asked to stop the local trading stack first, then locate overnight issues. Used official scripts only; no wipe; no restart; no trading, funding, login retry, or circuit-breaker changes.

Stop:
- 07:22:35 refreshed stale launchd pid cache: Gateway 67708->90813, Maker 68116->9208. Core pids already matched 67587/70427.
- 07:22:41-07:22:42 official `ACTION=down CORE_ONLY=true PRODUCT_LINE=SPOT RUN_ID=local-spot` stopped spot Core. Output: PRODUCT_LINE_RUNTIME=STOPPED.
- 07:22:51-07:24:06 official `scripts/local-perpetual.sh down` stopped checkpoints, frontend, perpetual Gateway/Maker/Core, then kafka, redis, postgres, and the caffeinate awake job. Script retains data under ~/.local/share/surprising-ex/perpetual-pmm-20. `pg_ctl -m fast` did not finish within its wait ("server does not shut down"); the script continued with `launchctl remove` (`|| true`). 07:24:22 verification: all com.surprising launchd jobs unloaded, former PIDs gone, ports 9094/8081/9096/9095/5173 and known postgres/redis/kafka ports closed. Logs/JFR/postgres/redis/kafka directories retained. One leftover vite/node listener on 5174 (PID 51261, parent 51243, ~13.5h) was not an official launchd job and was not killed.
- Dirty workspace preserved (init.sql and existing source/test/docs). No commit/push.

Diagnosis after stop (logs 2026-11-19 22:52 through shutdown; no live balances):
- Empty books remain explained primarily by Maker order rejects `INSUFFICIENT_AVAILABLE_BALANCE` (~5928 after 22:52, steady ~700/hour through 07:14). Live Core available/locked aggregates were never obtained earlier (admin login stopped after invalid identifier or password). PG history is still not a current balance. This is not closed.
- The ~6234 Maker 503s are not evidence that the public orderbook API was down. They are Maker client errors: 4665 simulated-trade and 1835 cycle failures, first 23:00:40, last 07:22:51 (shutdown). Paths: account position/balance GETs, POST /internal/v1/trading/maker/quote-inputs (1636), plus fewer local orderbook depth=100 GETs. Feign reason was only "503 ". Code: AccountService reads ValkeyUserQueries; missing READY/behind/unavailable throws ReadViewUnavailableException, mapped to HTTP 503 by GlobalGatewayExceptionHandler 88-91. quote-inputs maps IllegalStateException to 503 (MakerQuoteInputsController 56-57). Gateway app logs in this window had almost no "503" token and no overnight Valkey timeout; so 503 is consistent with read-view unavailability, but the exact READ_VIEW_* code was not present in Maker lines. Public depth=5 orderbook remained HTTP 200 with empty bids/asks when sampled in the morning.
- Perpetual Core 02:40:43.152 IllegalStateException is confirmed: "Matcher settlement ring is unfinished" at AccountBook.requireSnapshotFenceReady (AccountBook.java:1575) during onTakeSnapshot. CoreSnapshotLifecycle.pollSnapshotSections 175-177 releases the snapshot fence on RuntimeException, so this did not leave a stuck fence. It is not the cause of empty books (balance rejects started at 22:52). App logs show this single snapshot abort; later successful cluster snapshot messages were not present in these files. pollSnapshotSections returns null to wait for unfinished matching, but throws immediately if settlement depth != 0; that inconsistency is noted, not changed in this stop/diagnosis pass.
- Nacos: both Cores logged connection refused to 127.0.0.1:9848 continuously from 22:52 through shutdown (~4500 timestamped/refused lines per hour). Official local stack has no Nacos job. Gateway topology defaults surprising.aeron.topology.nacos.enabled matchIfMissing=true and falls back to static 127.0.0.1; Core registration/retry still produced this noise and very high started-thread counts. Not proven to be the quote-reject root cause. No disable change applied this pass.
- Aeron idle-spin patch is not implicated in these overnight symptoms: empty books, insufficient-balance rejects, and Maker 503s continued in the same pattern after the patched Gateway was already running; morning public API/WS still served empty books.
- FundCheck SQL errors continued (~306 gateway lines, about every 5 minutes) and remain an independent unfixed issue. No circuit breaker was cleared.

No services were restarted to load any fix.
