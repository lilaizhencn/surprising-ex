# Aeron Core 性能优化进展（2026-10-11，部分验证）

本轮完成常驻 Lane 控制派发、实例/Lane scratch、Matcher 分片 scope 与条件唤醒协议。Owner 完全退出账户/系统资金读写、Shared-Nothing 所有权及整链路零堆分配仍未完成。

- `c1fdbae7`：撮合准备、改单路由、自成交候选及前置撤单使用 Owner 的 ActiveOrderIndex，索引复用池由实例独占。
- `b89673f9`：控制账户任务投递到真实常驻 Lane worker，Owner 异步收集结果。
- `cd9b026b`：串行入口持有解码器/指纹 scratch，Lane 独享成交、风险、身份和杠杆 scratch。
- 当前模块：Matcher 每分片独享时间戳/scope 和 primitive 用户注册表；Owner/Lane 作用域分别保存，无关线程不能继承 Owner scope。删除无独占写者的新 orderToLane/positionToLane Map。Lane 公布 parked 后复查邮箱/交接，生产者仅在 parked 时 unpark，保留 100µs 兜底。

226 个回归和 50 个边界/撮合准入/benchmark测试执行通过，源码指纹不变；20 模块 clean package 与源码/JAR类字节核验通过。没有将未经本轮核验的全工程测试数或源码检索计数写作验收结果。

固定 global/session 256、1 成员/1 Matcher/4 Lane，U 本位永续 MIXED/batch20/128 symbol、G1、warm60/measure60：最新 l1-owner-routing-5 观测 **408,903.948 终态业务项/s**、39,058.242 Core消息/s，资金差与未完成为0，运行源码不变。CPU_Speed_Limit 为70/72/75，容量证据无效，不能验收50万目标或认定因果增速。

JMH metadataAndSequenceFanout（1线程/每配置1fork，3×1s预热+5×1s测量，4Lane/256、-prof gc）：BUSY_SPIN 814,531.136±52,095.011技术提交项/s、1,472.047B/项；BLOCKING 784,347.812±37,947.371、1,472.049B/项。99.9%区间重叠，微基准不是完整Core吞吐，也不能证明零堆分配。

此前独立 JFR 正式窗口估算主要 Core 线程合计427.23MB/s、约1,187.13B/业务项，线程观察区间略有差异，非精确对象数。Owner/Lane JavaMonitorEnter为0、录制DataLoss为0。订单快照、撮合结果、协议和集合仍有分配。删除ThreadLocal不等于删除业务对象分配。

pendingReservationSequences监视器暂留，待剩余Owner读取迁入Lane并落实所有权断言后一起移除。Owner仍有权威读取、Treasury写入及风险/输出职责，不宣称所有权已彻底切开。按用户范围，本轮不执行快照、恢复或wallet场景。

证据与范围见 [验证记录](docs/validation/lane-owned-scopes-wakeup-20261011.json)。原始数据位于 ../performance-artifacts/lane-parallel-20261010T144735Z/，保留源码/JAR SHA、日志、测量/资金指标、系统采样、XML、JMH及JFR窗口聚合。后续按锁定计划，仅改变一个因素验证2vs4Lane、service等待策略和Owner等待策略。
