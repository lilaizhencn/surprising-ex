# Aeron Core 性能优化进展（2026-10-11，部分验证）

本轮完成常驻 Lane 控制派发、实例/Lane scratch、Matcher 分片 scope 与条件唤醒协议。Owner 完全退出账户/系统资金读写、Shared-Nothing 所有权及整链路零堆分配仍未完成。

- `c1fdbae7`：撮合准备、改单路由、自成交候选及前置撤单使用 Owner 的 ActiveOrderIndex，索引复用池由实例独占。
- `b89673f9`：控制账户任务投递到真实常驻 Lane worker，Owner 异步收集结果。
- `cd9b026b`：串行入口持有解码器/指纹 scratch，Lane 独享成交、风险、身份和杠杆 scratch。
- 当前模块：Matcher 每分片独享时间戳/scope 和 primitive 用户注册表；Owner/Lane 作用域分别保存，无关线程不能继承 Owner scope。删除无独占写者的新 orderToLane/positionToLane Map。Lane 公布 parked 后复查邮箱/交接，生产者仅在 parked 时 unpark，保留 100µs 兜底。

226 个回归和 50 个边界/撮合准入/benchmark测试执行通过，源码指纹不变；20 模块 clean package 与源码/JAR类字节核验通过。没有将未经本轮核验的全工程测试数或源码检索计数写作验收结果。

固定 global/session 256、1 成员/1 Matcher/4 Lane，U 本位永续 MIXED/batch20/128 symbol、G1、warm60/measure60：最新 l1-owner-routing-5 观测 **408,903.948 终态业务项/s**、39,058.242 Core消息/s，资金差与未完成为0，运行源码不变。CPU_Speed_Limit 为70/72/75，容量证据无效，不能验收50万目标或认定因果增速。

JMH metadataAndSequenceFanout（1线程/每配置1fork，3×1s预热+5×1s测量，4Lane/256、-prof gc）：BUSY_SPIN 814,531.136±52,095.011技术提交项/s、1,472.047B/项；BLOCKING 784,347.812±37,947.371、1,472.049B/项。99.9%区间重叠，微基准不是完整Core吞吐，也不能证明零堆分配。

最新同 build5 的三项单因素诊断：2 Lane 421,317.217、service BACKOFF 423,504.424、Owner BUSY_SPIN 433,385.854 终态业务项/s。均资金差/未完成0、源码稳定；正式窗口CPU限频72–75，使容量和因果增速对照无效。未改生产默认配置，也未折算不限频成绩。

最新独立 JFR（l5-current-scopes-jfr）正式窗口1791693540294–1791693600310估算主要Core线程459.80MB/s、约1,191.24B/业务项；与上一轮1,187.13B同量级，没有整链路分配下降的证据。各线程观察区间略有差异，非精确对象数。GC pause92次、总445.48ms、最长5.39ms，DataLoss0。唯一Owner监视器事件是JFR内部StringPool，耗时1.24µs，未捕获业务锁争用。主要分配仍在preparedOrder、OrderRuntime.snapshot、MatcherResult.from及编解码。Lane正式窗口ThreadPark各25–38次；该事件不能统计生产者unpark调用量。

普通PLACE平仓容量检查已迁入所属Lane，仅通过完成收据交出撤单ID；Matcher在资金准入成功后合并自成交撤单并撮合。余额不足保留原单，post-only拒绝的新单正确提交已执行的撤单前缀。最新232项资金/顺序回归与50项边界测试执行、20模块clean package通过。相同最新build6的两轮观测417,223.030和428,543.832业务项/s，资金差/未完成0，CPU限频仍使容量无效。独立JFR约1189.92B/业务项，仍无整体分配下降证据；Owner两次监视器事件均来自JFR内部StringPool。局部平仓决策有8/64个存量订单时分配560/3904B/次，下一步需要削减其集合物化成本。详见[普通下单验证](docs/validation/lane-place-close-capacity-20261011.json)。

Treasury 前值捕获与资金差计算已改为复用 primitive 数组，单/多资产溢出在首笔写入前拒绝；240项资金回归+50项边界执行及20模块clean package通过。局部四组共1200万事务ThreadMXBean增量均为0，资金守恒通过。最新无采样425088.514业务项/s、资金差/unfinished0；正式窗口限频70/72/75，容量无效。独立整链路JFR约1172.21B/业务项、GC pause92次/总453.10ms/最大9.15ms、DataLoss0；Owner监视器样本来自JFR内部StringPool。整链路仍分配订单/快照/撮合与协议对象，生产SystemLedgerLane接线仍未完成，详见[资金原语缓冲验证](docs/validation/treasury-primitives-20261011.json)。

pendingReservationSequences监视器暂留，待剩余Owner读取迁入Lane并落实所有权断言后一起移除。Owner仍有权威读取、Treasury写入及风险/输出职责，不宣称所有权已彻底切开。按用户范围，本轮不执行快照、恢复或wallet场景。

证据与范围见 [验证记录](docs/validation/lane-owned-scopes-wakeup-20261011.json)。原始数据位于 ../performance-artifacts/lane-parallel-20261010T144735Z/，保留源码/JAR SHA、日志、测量/资金指标、系统采样、XML、JMH及JFR窗口聚合；单因素汇总见current-factors-results.json，最新JFR见l5-current-scopes-jfr-window-analysis.json。
