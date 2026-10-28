# 返佣资金与深层关系稳定性验证

结论：资金、异常回滚、路径终止及有界处理专项通过；完整 Gateway 测试编译仍被既有 Core 测试阻塞。生产容量未验收，耗时比较受系统换页影响，只作诊断。

## 最终行为

返佣继续在 Gateway，通过已提交实际费用事实异步核算。没有修改交易在线 Core、撮合、手续费计算或产品资金协议。

- 绑定邀请在独占配置锁内验证唯一上级和祖先关系。查询祖先使用 `UNION` 去重；核算一次读取有效路径与历史资料，Java 用迭代和 visited 集合验证连续性、唯一根、循环及历史条款重叠，不递归调用 Java 栈。默认 4096 个路径节点，超过上限整笔失败并保留原游标，绝不截断分账。
- 核算按产品串行，配置共享读、独占写。费用、完整路径佣金、累计舍入桶、结算工作组及游标原子提交。每批按费用数、完整分配行数、步骤间时间共同限额，不扫描其他兄弟成员来算一笔钱。
- 修复逐层碎片舍入：原顶层累计取整正确，但各下级逐笔取整。同订单 100 笔各 1 单位费用、20% / 60% / 50% 的例子，修复前留存 **20 / 0 / 0**，修复后为 **8 / 6 / 6**。先运行新回归确认旧实现失败，再修改实现。各条款持久化历史收到额度，计算本次累计应下拨增量；留存之和始终等于本次顶层佣金池。
- 成熟应付通过可重建工作组索引准备，每组只锁定有限前缀；不再每秒扫描、改写所有用户下次结算时间。超过 int64 的总应付拆成可表示的多张结算单。
- 付款逐张认领，每次新 token；旧租约的迟到结果不能改写新租约或终态。未知结果原业务身份退避重试，明确拒绝仅生成一张替代单。租约过期可能同时发出请求，资金防重依靠原 Core 身份；认领 token 只用于隔离数据库迟到回写。每次资金调用前核验关联应付与承诺金额一致；金额、端点和原始事实不可改写，不能给已认领结算单增加佣金或移走在途明细。
- 核算与资金 I/O 为两个固定调度任务。异常产品退避、其他产品继续；准备失败仍尝试发送既有承诺。缺预算/账户不可用按组延后，不阻塞其他产品。SQL、事务、团队查询并发均有门限。

## 配置与容量边界

下列为 `surprising.affiliate` 启动配置。后台的业务比例/成熟周期仍单独配置，默认 20%、24 小时。

| 配置后缀 | 默认值 | 作用 |
| --- | ---: | --- |
| `max-path-nodes` | 4096 | 整笔有效路径最多节点数；超限保留游标 |
| `fee-batch-size` | 64 | 单产品每批最多事实数 |
| `max-allocation-rows` | 8192 | 一批最多完整分配行；必须覆盖一条完整路径 |
| `batch-millis` | 250 | 每个完整费用/分组/付款完成后的让出目标 |
| `query-timeout-seconds` | 2 | 只作用于 Affiliate 的 JdbcTemplate |
| `transaction-timeout-seconds` | 5 | 核算与结算准备事务期限 |
| `payout-groups` | 16 | 一次准备最多组数 |
| `payout-rows` | 256 | 每组最多成熟佣金行 |
| `payout-attempts` | 10 | 一次最多逐张尝试付款数 |
| `claim-seconds` | 120 | 认领租约，Core 幂等身份跨租约保持不变 |
| `concurrent-reads` | 4 | 每实例昂贵团队查询并发；繁忙返回 503 |

250ms 不会强制中断半笔分账或网络资金调用；SQL/事务有独立期限，资金请求沿用现有账户客户端期限。连接池获取连接、进程调度、网络故障等不能由 SQL timeout 统一承诺墙钟上限。不同 Gateway 仍共享现有 DataSource，所以扩容必须一起核算数据库连接容量。

默认支持的是有限资源内的多层团队。宽度一万不代表深度一万。应用计算与必须落账的记录为 O(实际路径深度)，需要为每层保留可审计记录；不是 O(1)。路径 SQL 使用索引查父链，不逐层发出资料查询。团队人数统计按需遍历去重，仍与团队规模相关，使用索引、并发门限和 SQL 超时保护。没有建立深链下 O(n²) 的全量祖先闭包表。

核算每 250ms 轮转一个就绪产品；空产品等 10 秒，异常退避 2–60 秒。资金每秒调度，未知结果退避 10–300 秒。手动结算沿用有限批次语义，自动关闭的用户如需大批人工清算，应分批触发；不能把一次 `queued=true` 当作全部到账。

## 验证结果

环境：macOS 26.7.2 / x86_64，Corretto HotSpot JDK 27+33-FR，Maven 3.9.16，PostgreSQL 18.4。基线起点 master `6b5b4b04d`，期间其他任务提交了独立 Core 重构 `534aeea38`；本任务没有回退它的改动。最终安装当前 reactor 依赖并重新编译 Gateway 后执行专项。

| 项目 | 实际结果 |
| --- | --- |
| 相关依赖与主源构建 | `mvn -pl surprising-gateway -am install -Dmaven.test.skip=true` 成功；最终 Gateway `package` 成功；测试没有被此命令验证 |
| 返佣专项 | 46 项通过：原有/扩展 PG 15、纯公式 3、可信资金调用 1、安全 PG 19、HTTP 4、资源隔离 4 |
| 相关认证回归 | 77 项，75 通过、2 条 PRIMARY_AUTH 专用环境条件不满足而跳过 |
| 汇总 | 123 项，0 failures/errors、2 skipped；增强的批次冲突回滚用例另重新编译、单独通过 |
| 新库与迁移 | 完整 `init.sql` 在独立空库成功；安全迁移可重跑，回填累计收到额度，历史金额不变 |
| 深层与宽团队 | 4096 层 + 10000 名宽分支成员，末端仍保留全部 2000 单位；没有递归栈溢出，佣金池与各层留存相等 |
| 损坏关系 | 循环、重叠历史、路径超限都失败，无费用/资金/累计桶提交，无游标前移；调整资源上限后从原游标完整重算 |
| 批次与并发 | 100 个相同日志位置不同订单分页无漏计；完整路径行预算边界恢复；中途冲突整批回滚顶层与下拨累计桶；锁住 SPOT 时另一个产品继续 |
| 结算 | 一万无关资料不被重排周期；成熟前缀分批；预算阻塞隔离；租约 fencing；账目不平不调用资金；未知结果退避；金额 int64 边界不溢出 |
| 真实确定性 Core | 首次扣款响应丢失，Core snapshot 后恢复，仅扣一次、入一次；预算不足明确拒绝无扣款，补资后一张替代单支付一次，总资金守恒 |
| HTTP | 超时/损坏历史/繁忙返回可恢复 `message`+503，不泄漏 SQL；认证 401 保持不变 |
| 浏览器 | Chrome 用户 390×844、后台 1440×1000，503→点击刷新→恢复；无无限重试、pageerror 或页面横向溢出；见 [结果](browser-result.json) |

浏览器只对本轮新增的错误恢复行为使用明确 HTTP 夹具；真实 controller 由 MockMvc 测试、数据库与资金由独立 PostgreSQL/真实 Core 状态机测试验证。没有将浏览器夹具说成真实服务联调；完整原生邀请/团队流程的前一轮证据在[原始验收](../affiliate-20261028/README.md)。本轮没有修改 Web/Admin/Flutter 业务代码，没有重跑 iOS/Android 全流程。

截图：[用户繁忙](web-busy.png)、[用户恢复](web-recovered.png)、[后台繁忙](admin-busy.png)、[后台恢复](admin-recovered.png)。用户端沿用已有安全错误翻译，英语环境显示通用英文不可用提示；后台显示受控中文 message。

### 测试命令与联合测试限制

```sh
# 当前主源及依赖
mvn -pl surprising-gateway -am install -Dmaven.test.skip=true
mvn -pl surprising-gateway package -Dmaven.test.skip=true
# 正常 Maven test 已实际执行，停在既有 Core 旧测试编译失败
mvn -pl surprising-gateway test -Dtest=CommissionAllocationTest,AffiliatePostgresTest,CommittedPayoutTransferTest,AffiliateSafetyPostgresTest
# 在完整 init.sql 的独立 PG 数据库中执行；依赖通过 dependency:build-classpath 导出
# 使用 javac -proc:none --release 27 显式编译全部 Affiliate 和下述五个 Auth 测试当前源码
mvn -pl surprising-gateway surefire:test -Dtest=CommissionAllocationTest,AffiliatePostgresTest,CommittedPayoutTransferTest,AffiliateSafetyPostgresTest,AffiliateHttpTest,AffiliateResourceTest,AuthServiceTest,PrimaryAuthenticationDatabaseTest,AuthPagePostgresTest,AuditLogPagePostgresTest,AuthInputBoundaryPostgresTest
```

设置 `INSTRUMENT_TEST_JDBC_URL`、`INSTRUMENT_TEST_DB_USER`、`INSTRUMENT_TEST_DB_PASSWORD` 指向本轮独立库。各 Affiliate 用例另建/删隔离 schema；Auth 夹具会清理本轮库的用户，严禁指向共享库或生产库。精简原始输出见 [verification.txt](verification.txt)。

联合测试仍被 `CoreTestCompletion.placeAdmissionOutstanding`、`MaintenanceIntegrationTest.totalUnits/enterAsynchronousCommandScope/exitAsynchronousCommandScope`、`MultiProductMoneyIntegrationTest.totalUnits` 的既有编译错误阻塞。本任务没有恢复已移除 Core API，也没有跳过/改写这三个测试来宣称整体通过。

## 数据库旁路性能诊断

执行前[计划](plan.md)固定深度 8/128/1024、宽度 10000、每组预热 3 笔/测量 20 笔，全部下拨比例 100%，覆盖末端金额不因深度提前变零的最坏账本长度。每笔独立新订单、实际 PG 提交；使用相同计数代理与 DriverManagerDataSource。生产连接池会改变连接成本，本报告不外推。

**定性：部分验证，速度验收无效。** JFR 配对窗口有全系统 swap-in +225 页；无 profiler 当前运行 +236 页，基线没有全系统换页采样。按项目性能规范，不能把这些耗时作为有效性能提升/容量结论。语句准备调用、完整记录数、索引执行计划及资金核对仍是可核查的结构性证据。未重新运行旧版本。

| 深度 | JDBC 准备调用/笔：前→后 | JFR 下 p50 ms：前→后 | JFR 下样本 max ms：前→后 | 主线程分配 B/笔：前→后 |
| --- | --- | --- | --- | --- |
| 8 | 27 → 15 | 41.949 → 50.769 | 57.035 → 80.488 | 184535 → 191192 |
| 128 | 267 → 15 | 341.605 → 96.844 | 482.795 → 171.068 | 646357 → 465478 |
| 1024 | 2059 → 15 | 2438.710 → 444.641 | 2649.529 → 601.009 | 3821415 → 2360792 |

这里的 JDBC 准备调用统计 `prepare*`/`createStatement`，**不是数据库实际执行总次数，也不是 TCP 往返总数**。各层佣金与累计桶仍需逐行写入，只是通过同连接的 JDBC 批次提交。浅层新增累计下拨、安全索引与事务保护有额外成本，未宣称所有深度都更快。

| 当前无 profiler 运行 | p50 / p90 / 样本 p99=max，ms | 主线程 CPU ms/笔 | 团队概览 ms |
| --- | --- | --- | --- |
| 8 层 | 52.911 / 74.225 / 80.622 | 7.960 | 203.041 |
| 128 层 | 70.058 / 91.702 / 93.251 | 6.177 | 178.598 |
| 1024 层 | 342.157 / 418.807 / 455.554 | 12.241 | 119.878 |

仅 20 样本，样本 p99 等于 max，不能表示生产长尾。闭环串行发起存在 coordinated omission，没有按交易 TPS 或团队容量解释倒数。每个场景 23 张费用事实均完成，留存合计等于佣金池，差额为 0。完整指标见 [metrics.json](metrics.json)。

[SQL 计划](sql-plans.json)显示：1024 路径的条款查找使用 `affiliate_team_child_history`，资料历史使用 `affiliate_profile_history_time`；团队人数使用 `affiliate_referrals_inviter` 索引，每个去重成员一次查直接下级，避免每轮深度反复扫描全体邀请关系。一次独立诊断中路径 SQL 14.787ms、人数 SQL 123.838ms，同样不是生产延迟承诺。

JFR 配对各限制 64 MiB、`settings=profile,dumponexit=true`，主程序 `AffiliateScaleProbe`。两次 DataLoss 均为 0；[summary 与 view](jfr)覆盖整个进程，包括建夹具、预热和查询，不当作纯稳态。后一次 NativeMethodSample 主要是 `Net.poll`，Java 样本出现 PG bind/parse 与 JDBC batch；这只支持数据库/连接等待仍存在，不证明 Core CPU 或整体容量饱和。精确分配来自 ThreadMXBean，未用 JFR 无分配样本宣称零分配。

| 正式交易验收指标 | 本轮 |
| --- | --- |
| 报单、成交对、撤改单吞吐 | 未测；返佣数据库旁路不产生交易报单 |
| 综合业务/分片记账吞吐 | 未测 |
| Aeron WAL 报文/带宽 | 未测 |
| 资金与恢复 | 真实确定性 Core 资金与 snapshot 专项通过；未跑真实网络节点恢复 |
| 长稳、三节点、native 内存、磁盘带宽、缓存行 | 未测 |

需在无换页且资源门槛满足的环境，另按项目要求执行单真实 Aeron 成员、单 Matcher、全局 in-flight 256 的正式混合链路与返佣积压验收。本次不作交易 TPS、积压消化速度或长稳无泄漏承诺。

## 上线、对账与故障恢复

现有库停止所有 Gateway 写入，再事务执行 `deployment/migrations/20261028-affiliate-safety.sql`，之后统一更新 Gateway；新库用完整 `init.sql`。后台比例仍可配置。这里没有执行生产迁移或发布。

安全迁移回填明细索引字段、待结算工作组及每条历史条款累计收到额度，反复执行不改已有累计桶。它不会修改旧佣金、撤回旧资金或自动补发旧舍入差额。旧算法下少分给下级的额度已经留在上级；历史更正应先审计，再用授权更正流水处理，不能直接补发造成超池。

只读排查示例见 [reconcile.sql](reconcile.sql)，大账本全量对账在隔离副本或维护窗口运行。该脚本已在财务表为空的独立库执行检查语法；实际非零金额的对账由上述 PostgreSQL / Core 夹具断言完成。按产品和资产分别检查：

1. 费用佣金池与各层留存之和相等。
2. 每个未替换的结算单与关联佣金金额、收款人、产品和资产相等。
3. 关注来源 checked_at/error、下一笔未消费事实与游标距离；不能把 checked_at 正常当作没有积压。
4. 关注工作组 blocked、到期时间、UNKNOWN 的尝试次数与年龄；一直 UNKNOWN 先查原 Core transfer 身份，不换键。

坏关系/超深链：修复数据来源或经容量验证后调整资源上限，再从原游标重试；不要跳过费用。缺预算：后台配置并备资，检查预算用户状态，工作组会重新就绪。明确拒绝：补足原因后从原单重试，仅生成一张替代单。未知结果：按原身份恢复并核对 Core 状态，不直接改 PAID，不创建替代身份。结算工作索引可用迁移里的 INSERT…SELECT 重建，须停止写入并由管理员核对；其丢失不会改变佣金金额，但会延迟调度。

原始录制和临时工具在摘要/哈希归档后删除；本轮独立数据库、Vite 与浏览器进程清理状态见 [cleanup.json](cleanup.json)。
