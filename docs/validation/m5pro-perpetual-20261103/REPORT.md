# M5 Pro 单节点永续持续满载压测

结论：**部分验证**。真实 Aeron 单成员网络与 Archive 场景，普通单约 9.76 万单/秒，batch20 约 74.14 万单/秒。确认在途窗口持续满载，未证明 Core 物理饱和上限。不是生产混合全链路测试。

## 环境与计划

2026-11-03，master 9088fc7cc，测试前工作区干净。Apple M5 Pro 15 CPU 核/16 GPU 核、24GB、1TB SSD，macOS 27.2；HotSpot Homebrew JDK 27.0.1.0.3，Maven 3.9.11。正式发压前已接交流电。G1，节点 512m/1536m，客户端 128m/512m；一个成员、一个 matcher、两个账户 Lane；SHARED_NETWORK、YIELDING；256 symbols、100 users、1 worker/connection，全局 in-flight=256。种子默认9901，买卖各半 GTC，MATCH_STREAM / MATCH_BATCH_STREAM(batch20)，附带标记价更新，无额外做市进程。每场预热30秒，稳定测量60秒，排空另计，各1次；诊断batch20另跑30秒预热/30秒测量并启用profile JFR。门槛：资金差0、提交完成一致、排空无积压且无失败；不声明跨机器性能提升。

执行命令及完整日志见同目录两个 run 子目录；run.py 和 diagnostic.py 记录编排实现（绝对路径反映本次机器）。首次脚本错误使用不支持的 tradingProfile=MATCH_STREAM，被拒绝且未产生吞吐，不计成绩。构建使用 Maven package -DskipTests；定制 exchange-core 从相邻源码 install -DskipTests。

## 性能看板

| 层次/指标 | 普通单 | batch20 | 含义 |
| --- | ---: | ---: | --- |
| 业务报单 | 97,599.983 单/s | 741,367.667 单/s | 稳定60秒终态增量，不包含排空 |
| 撮合成交 | 约48,800 成交对/s | 约370,684 成交对/s | 每对两笔委托，稳定订单速率折算 |
| 综合操作 | 未独立测量 | 未独立测量 | 无资金费、强平等混合负载 |
| 账户分片清算 | 未采集 | 未采集 | 不以订单数替代记账数 |
| Aeron业务命令终态 | 约97,856 报文/s | 约37,325 报文/s | 含标记价；按整轮含排空计数折算，不等于物理Archive帧数 |
| 网络/落盘有效带宽 | 未采集 | 未采集 | 不按文件大小臆测 |

## 延迟

客户端发压到收到业务终态，包含网络、Cluster、Core、撮合清算；Archive启用不等于每个ACK都验证fsync。批量延迟为批命令终态分配给每项，非逐项独立测量。单位ms。

| 场景 | p50 | p90 | p95 | p99 | p99.9 | max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 普通单 | 2.357 | 2.646 | 2.758 | 14.819 | 20.135 | 27.869 |
| batch20 | 6.733 | 7.389 | 7.643 | 11.403 | 21.446 | 29.179 |

普通单窗口平均255.458，batch20平均255.847，最大均256；无offer重试。闭环有界持续异步负载，存在 coordinated omission：窗口满时不会生成无限外部到达队列，不能推导开放流量过载延迟。普通单每10秒约9.57万至10.15万单/s。窗口满不证明Core物理饱和；本轮未突破规范窗口256。

## 内存与系统资源

进程点采样：节点约607%–650% CPU、RSS约1.8–1.9GB；客户端约266%–283% CPU、RSS约0.43–0.48GB（macOS 100%约一核）。忙等会消耗CPU，不等于业务计算饱和。swap占用前后均3.25MB；未采集换页增量，不能据此证明无换页。系统未报告热/性能告警，但未实测频率。DirectBuffer、精确B/op、GC稳定窗口停顿占比未量化，不填零。

独立诊断JFR无DataLoss；全录制18次GC，完整GC表见gc.txt，不混入主吞吐。热点样本：SettlementLaneWorker.peekMatcherSettlement 31.53%，PipelinedPlaceBatchSubmission.get 20.50%，MatcherCommandPipeline.runMatching 5.64%；包含预热等全录制窗口，仅用于线索，不作稳定窗口瓶颈定论。JFR原件保留在target诊断目录，摘要和SHA256已归档。

## 正确性、缺口与清理

两轮主测试及诊断均正常退出、capacity=PASS、fundsDiff=0、bookLevels=0。普通单accepted=finalized=5,856,254；batch20=44,487,160；包含标记价的submitted=completed分别5,871,614和2,239,718。排空unfinishedRequests=0，账户/撮合/上下文队列0，failures=0；排空分别2.660ms和6.376ms。拒绝/超时未单独分项计数。没有强平/ADL，赤字指标不适用。未执行快照恢复、长稳、独立资金冻结持仓全量恢复审计、三段延迟、精确分配、锁/缓存行、磁盘带宽或重复轮次，故不是完整生产验收。

本轮所有测试节点和客户端已停止，未改业务源码。原始Archive与JFR保留target，后续确认无需复查后可清理。下一步若要确认物理饱和，应先完成对齐稳定窗口的线程/排队证据，并明确是否允许独立诊断扩大窗口（正式基线仍256）。
