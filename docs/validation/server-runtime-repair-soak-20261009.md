# 运行开销修复、两小时测试服务器观察及系统审查

结论：原观察确认的计算、连接、回放自旋、重复分配及实时路由首次连接丢消息问题已修复，相关代码已提交并发布测试服务器。完整稳态观察已结束；本轮负载下的存活、查询和实时数据校验见下面实测结果。系统整体业务不能判为全部通过：闭合K线少计真实成交、资金费历史投影缺失和同一窗口重复 FINAL 记录仍是已确认问题。没有把两小时观察解释为无泄漏或容量验收。

## 观察口径与部署

- 正式预热600秒；稳态 2026-10-08T17:19:56.653994+00:00 至 2026-10-08T19:19:56.923422+00:00，实际 7200.269 秒。/proc 覆盖 7192.912 秒、241 次；业务采样 25 次。边界最后一次 /proc 与计时器不完全对齐，未把预热或发布前窗口算入7200秒。
- 12 vCPU、约47 GiB，HotSpot27，LINEAR_PERPETUAL 三合约现有做市；SPOT未启动。三个只读WS观察者分别从本机公网、服务器公网和服务器回环订阅15个频道。没有额外测试成交、充值或资金调整。
- Gateway a3ddabc1 / PID2209970；price 261b8969 / PID2201655；realtime与lifecycle 96e7d74c / PID2201656、2201657；maker 86092e41 / PID2214636。服务器拉取固定提交自行构建。Core原PID2100963及原二进制保留；Kafka原PID2176038保留，57 topics/57 partitions均为1，没有重置topic、Archive或业务checkpoint。
- 五应用 -Xms512m，Gateway -Xmx4g，其余 -Xmx2g，ZGC、AlwaysPreTouch、NMT summary；Core原-Xmx8g，NMT未启用，不能补报其native分类。Kafka固定1GiB。消费者并发配置均1、Streams单线程；maker Hikari最多2/minIdle0。
- 正式窗口前的错误BTCUSDT参数、未使用ticker 404、查询时间范围及发布前/补单扫描前窗口保留为观测偏差或诊断，不计入本轮有效7200秒；Kafka CLI九列表格曾按十列快速解析，零结果已废弃，最终解析处理stderr插入及跨行记录，未提交空topic位点保持未知。
- 仅验证当时master工作源码，没有签出或重跑旧源码作历史性能比较。部署后未在正式窗口改版本。NMT与定时只读诊断的开销包含在当前观察条件中。

## 修复内容与业务边界

1. price共享行情帧只解析一次，校验源/币对后交给对应报价；正常不匹配返回空结果，删除正则和异常过滤开销。WebSocket listener身份隔离，旧连接回调不能清理或中止新连接，配置改变时重订阅，停止时释放本服务连接。
2. maker每阶段读取一次不可变配置；ppm运算精确拆分商/余数，避免重复BigInteger；逐条交易终态确认后批次更新派生订单缓存，不确定结果失效并重新查询Core。槽位直接解析已有clientOrderId，补单扫描使用直接循环且保留容量、交叉保护和精确算术，没有新增业务事实副本或全局索引。
3. maker使用有界HC5连接池、明确连接/取连接/读取超时及关闭生命周期；资金命令不自动重试。设置/策略恢复后台数据库持久化，事务提交后失效派生缓存，回滚和冷事务不泄漏未提交值；诊断内存有界，停止逐周期写两张大诊断表。
4. Aeron空队列不创建迭代器/lambda；CommittedTradeReplay匹配无进展时有界park且保留超时/中断，只有真实结算完成后推进checkpoint。测试服务器Core未替换，本轮服务器回放代码位于realtime部署中。
5. realtime节点冷连接临时NOT_CONNECTED/背压按节点FIFO重试，单router线程拥有缓冲，最多8192帧/16MiB、每节点每轮32帧、5秒过期。失败释放并触发源恢复。真实Aeron/Redis测试覆盖首次连接、FIFO、其他节点不被阻塞、过期、停止和恢复。
6. Gateway查询边界买盘降序、卖盘升序，使首档表示买一/卖一；不改变Core canonical内部顺序或撮合协议。

新增状态仅用于技术边界：router拥有有界待发送帧；HTTP池拥有连接；listener拥有连接代际；配置缓存是数据库的不可变派生值。没有新增资金权威状态。所有关键交易仍等待原确认边界，单Core owner、顺序、产品隔离和恢复语义保持。

## 持续运行实测

CPU按/proc ticks/HZ100及实际相邻时间计算，1.0代表一核；PSS为实际共享页按比例归属，不能用Aeron logical mapping容量替代。首/末15分钟中位数用于减少单点堆扩展/GC影响。

| 进程 | 平均CPU核 | PSS起止MiB | 首/末15分钟PSS中位MiB | 平台线程范围 | FD范围 |
| --- | ---: | ---: | ---: | ---: | ---: |
| gateway | 1.122 | 2710.2 → 2589.8 | 2384.7 → 2304.9 | 103–105 | 136–144 |
| price | 0.447 | 1213.0 → 1182.7 | 1169.5 → 1182.2 | 109–112 | 106–109 |
| realtime | 0.690 | 2477.3 → 2259.6 | 2169.4 → 2153.5 | 103–106 | 154–157 |
| derivatives-lifecycle | 0.288 | 995.4 → 1075.4 | 1007.1 → 1070.4 | 81–83 | 50–52 |
| maker | 0.307 | 2351.3 → 1611.1 | 2021.9 → 1471.3 | 86–87 | 47–53 |
| core | 1.290 | 2992.2 → 2992.9 | 2989.3 → 2994.0 | 60–61 | 97–97 |
| kafka | 0.279 | 1001.6 → 1007.0 | 1001.8 → 1006.8 | 120–120 | 271–276 |

整机idle中位 62.93%、iowait中位 1.38%、steal最大 0.00%。整机还有其他工作负载，不能把所有host变化归因本次交易应用。完整逐进程区间、PSS/private/RSS斜率、swap/faults、IO、线程CPU和PSI见JSON，斜率不单独作为泄漏结论。

导出checkpoint tradeSequence 170650 → 172751，增量 2101；logPosition增量 302529600，倒退 0 次。没有把业务日志总量加载到当前Core的证据；本次未重启Core或触发历史恢复。

五应用健康异常采样 0，行情接口/基本数据约束异常 0。盘口是动态采样，不能承诺所有时刻恰好一tick。

| 合约 | 买卖一价差ticks范围 | 24小时分钟窗口条数 | 最新分钟推进 |
| --- | ---: | ---: | --- |
| BTCUSDT | 1–76 | 1441–1441 | 2026-10-08T17:19:00Z → 2026-10-08T19:19:00Z |
| ETHUSDT | 1–73 | 1441–1441 | 2026-10-08T17:19:00Z → 2026-10-08T19:19:00Z |
| SOLUSDT | 1–1 | 1441–1441 | 2026-10-08T17:19:00Z → 2026-10-08T19:19:00Z |

K线更新时刻仅有批次开始和文件完成包围范围，没有每个HTTP响应的精确时间；JSON报告上下界，负的下界表示该响应在批次开始后更新，不能解释为负延迟。最终独立请求另有开始/完成时间。

Kafka活跃分区组记录 20–20，有提交位点的活跃lag合计 1–29。停用历史组不计当前积压；空topic未提交位点不补零。瞬时committed-offset lag包含提交间隔，结合业务推进和全时间序列判断，不以一次非零断言阻塞。PG诊断表插入计数、连接/等待、deadlock/temp增量及Redis used/RSS/clients/eviction/error累计值均入JSON，初始累计错误不冒充新增错误。

## WebSocket连续性

关闭次数按精确UTC仅计正式7200秒稳态，其他频道累计和连接次数覆盖预热及结束采集，范围没有混用为纯稳态吞吐。

| 观察路径 | 总连接次数 | 稳态非正常关闭次数 | depth sequence gap | 无效盘口/交叉盘口/无效K线 |
| --- | ---: | ---: | ---: | ---: |
| ws-internal | 1 | 0 | 0 | 0 / 0 / 0 |
| ws-server-public | 1 | 0 | 0 | 0 / 0 / 0 |
| ws-final-public | 18 | 17 | 0 | 0 / 0 / 0 |

本机公网1006异常需要继续定位；服务器公网与回环提供对照，但不同网络、Cloudflare边缘和Node版本，不能据此确定Cloudflare或服务端就是根因。重连/重新订阅后的快照恢复与序列校验单列，不把断线期间无数据隐瞒成绝对连续。主动停止观察者产生的1000关闭不计故障。

## 闭合K线独立核对

结果 FAIL_OR_INCOMPLETE：从固定last stable offset前有界读取 5313 条已提交公开成交，assign单分区、autoCommit=false，不加入业务组。数据库元数据前后比对，按真实ticks/合约乘数独立重算三个合约最近各20个完整已闭合分钟，核对OHLC、base/quote volume、tradeCount及首尾tradeId/sequence。实测60根中3根少计最后一笔真实成交：ETH 19:03、19:12 UTC及SOL 19:04 UTC。分钟关闭后处理器丢弃迟到成交并记录去重，数据库冲突不更新、多周期聚合也忽略修订；这是已确认且尚未修复的业务错误。修复必须同时处理分钟重算、单调持久化、多周期替换和缓存推送，不能只放宽时间判断。逐分钟值、差异/缺口、编码及原始文件哈希见JSON。此检查不等于私有余额、资金费或所有历史K线已验证。

## JFR / GC / native归因

正式窗口前后六JVM各30秒profile录制，单录制16MiB上限，共 12 份、12764396 bytes，记录DataLoss 0 bytes。初始录制位于预热末，最终录制在完整稳态后；稳态未持续运行采样profilers。jfr summary/view、Java执行采样、分配权重、异常类/调用点、GC pause、safepoint/VM操作、JIT/deopt、I/O和native diff入档。原始JFR含环境信息，权限受限、不提交原始录制。

NativeMethodSample里EPoll等待不算CPU热点；异常出现不自动算业务失败：HC5 isStale读超时是捕获的闲置连接探测，Netty TraceRecord的Throwable是跟踪记录而非LEAK告警，Files.createDirectories的FileAlreadyExists是正常目录探测。Core NMT缺失，JFR平台线程分配不完整覆盖虚拟/短命线程；两段30秒暂停采样不能声称覆盖两小时全部暂停。Micrometer GC并发周期时间不当作STW暂停。没有用一次RSS下降或零DataLoss证明无泄漏。

lifecycle PSS起止约995→1075MiB，而GC后live heap范围74–84MiB，NMT committed仅增加1973KiB且主要为Code；不足以认定内存泄漏。五应用直接缓冲区数量和容量在25次采样中不变。Gateway产品选择完整JSON树与MVC再次解析、导出恢复快照的大数组复制仍是分配审查重点；前者必须保留嵌套/批量选择冲突校验，后者必须保留提交及恢复边界，未把必要的防御复制直接删掉。具体候选见JSON findings.resourceReview。

诊断收尾尝试关闭NMT时，HotSpot27返回不支持shutdown参数；NMT summary仍在运行，已明确记录，未为关闭诊断重启应用。所有有时限JFR已结束。

## 本地验证与适用范围

- 受影响局部/跨模块、真实Redis/Aeron、数据库提交/回滚/CAS、价格解析/重连、maker报价/超时/缓存、Gateway盘口及六产品资金/快照测试通过。逐类用例数、日志SHA和失败fixture修正保留在JSON，重复轮不叠加为唯一测试数。
- 当前源码JMH主轮2 forks、3×2s预热、3×2s测量；独立GC profiler1 fork、512MiB ZGC。实际quoteRefillScan满盘口40/240报价约62421/1850轮/s、40.078/42.708 B/轮；liquidityReplacements当前实际槽位路径0.0099/0.2085 B/轮接近测量底噪，不能宣称精确零分配。配置读fixture测量前两次装载，teardown无新增查询，不把fixture吞吐解释为PG容量。
- 真实单成员网络Archive：1 matcher、global in-flight256，30秒预热、60.002524秒测量；稳态Core消息2087169、成交5199360，约34784.687消息/s、86652.355 fills/s。drain后offered=terminal Core2087425、unfinished0、peak256，资金差0、恢复/业务hash a3af5eb1d5809cd1。latency直方图包含drain，未当作纯稳态HTTP p99；负载上限及匹配饱和门槛未满足，容量结论UNCONFIRMED。
- 没有在服务器触发真实强平、交割、期权、资金费全流程或Core重启恢复；本地六产品状态机测试与线上现有永续运行分别报告，不能把其余五条产品线判为已部署或全量验收。

## 系统审查发现与未完成的修复

小时快照脚本在稳态内因缺少独立工具JAR错误跳过，systemd仍返回成功；稳态后发布012a7465，使用必备Core executable，并把工具/超时/读取/请求错误报告为失败。8项脚本边界测试在本地和服务器通过，真实任务新增2条有效快照、Core PID及五应用保持正常；新脚本没有被计为已完成两小时长稳。

| 严重性 | 问题 | 证据状态 |
| --- | --- | --- |
| high | closed-candle-late-trade-loss | CONFIRMED correctness failure; NOT REPAIRED |
| high | funding-history-projection | confirmed feature gap, not repaired by resource changes |
| medium | funding-restart-final-duplicates | confirmed repeated FINAL records for same instrument/funding time; financial impact unproven |
| low | retired-instrument-caches | static retention candidate, not a reproduced leak |
| unassigned | public-websocket-network | confirmed local-public path discontinuity in completed steady; cause unlocated |
| medium | retired-strategy-workers | confirmed stale wakeup mapping after strategy edit; deletion worker retention candidate if definition retirement is supported |
| high | scheduled-snapshot-false-success | REPAIRED and deployed after steady; actual systemd invocation PASS, two newly valid records, unchanged Core PID |

高优先级是资金费投影：FundingSettlementRepository.latestCore / FundingPaymentRepository.corePage读取core_funding_*_projection，但现有导出路径无相应写入；线上表空、三个latest返回404。16:00UTC同一窗口BTC/ETH/SOL存在248/249/250条FINAL、164/174/168种费率；重放预测值和already-complete分支反复保存有关。FINAL不是已实际扣款证明，Core settlementId/idempotence仍是权威，未证明重复扣款。正确修复需从Archive准确导出已提交结算页，幂等投影/冻结最终费率，并验证跨页恢复与资金守恒；不能用当前仓位或猜测费率伪造历史。本轮资源修复没有修复此资金协议缺口。

策略编辑后的wakeup映射保留初始策略是静态确认路径；删除定义是否由当前后台支持尚未证实。退休合约/策略缓存保留是churn候选，固定三合约两小时不能复现。K线精度缓存候选已排除：InstrumentStorageService.requireUnchangedContractTerms禁止已创建合约修改资产、价格/数量单位及乘数，资产账务精度亦不可变，当前后台操作不能触发所假设的精度变更。各项最小复现/正确修复要求在JSON，不把候选写为已复现泄漏。

## 证据与清理

完整采集前计划、固定提交、真实命令、原始JMH样本、测试计数、网络计数、JFR聚合、NMT、全时间序列摘要、路径/大小/SHA-256、偏差及清理状态：[同名JSON](server-runtime-repair-soak-20261009.json)。raw路径若已删除仅供历史定位。

本轮独立本地Core/PG/JMH/构建进程已结束并保留分析与文件校验后清理生成物；原有本地服务保留。初始3GiB artifact预算曾超过，峰值约4935MiB，已记录偏差并剪除完成的本地Archive/独立构建；没有发生磁盘压力，不隐瞒预算偏差。服务器有效应用、回滚发布、受限私有配置备份、业务Archive/checkpoint/Kafka保留；观察者及原始诊断清理的最终状态以JSON cleanup为准。
