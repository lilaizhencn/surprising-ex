# 第四十八轮：撮合、身份与快照冗余清理

基于 `2399425e9dfe0029c2af95f1d0f1ac78bb7fde13`，处理上一轮 A155–A160，并沿身份计数链新增、解决 A161。累计 155 项已解决，A154 待修复、A11 候选及 4 项保留；全量审计仍未完成。

| 问题 | 实际清理 | 保留的业务边界 |
| --- | --- | --- |
| A155 | 普通撮合、池化结算结果的订单号副本，以及控制任务仅用于绑定结果的订单号字段；清除对应接口、参数搬运和非负约束 | 原生发布仍核对实际订单号、准入身份，按真实事件和成功撤单前缀构建结算计划 |
| A156 | 3 个未接入生产的等待缓存字段、两个缓存方法、回显 getter 和不可达 Owner 分支 | 真实完成通知、本地续步、窗口退休与依赖前缀 |
| A157 | 无业务读端的字典版本、getter、累加、恢复公式及只服务版本统计的转发方法 | 确定性身份、碰撞检查、引用计数、Owner/Lane 归属、分配检查点与回滚 |
| A158 | 生产包中的身份辅助 Snapshot（8 组件）、ClientIdentity（2 组件）、导出/恢复及 Lane 辅助导入导出 | 实际 Cluster 恢复仍从交易事实重建身份，必要测试观察位于测试目录，不提供第二套恢复入口 |
| A159 | Image、内部和公开清单中的重复 Core 水位组件，以及快照头中的一份 long | 异步冻结点、独立 Matcher 和 Lane 配对、独立投影序号、来源摘要、资金/业务哈希、CRC |
| A160 | 两个默认传零修订号的准备重载 | 杠杆/持仓模式仍通过完整入口传入真实命令前修订 |
| A161 | Lane、单笔/批量准入、改单回执的 4 份分配计数，以及顺序批次、触发续步的 2 份身份令牌副本 | 实际身份准备与异常回滚使用 Lane 内局部键，不向 Owner 搬运无人消费的统计元数据 |

共删除 13 个运行态字段、两份辅助 record 的 10 个组件及 3 个重复水位组件；未新增生产字段或异步阶段。`CoreMatchingResult` 完整构造从 13 参减为 12 参。

分段快照升为 **v30**，头部从 **89 字节缩为 81 字节**，读写均为 1 个产品线字节加 10 个 long。Matcher 快照、交易状态快照和命令协议版本不变；按用户确认，不增加旧格式读取入口。

测试迁移保留客户号释放、重复引用、碰撞隔离、异线程读取及成交后的故障状态断言。六产品线的现有实际快照用例新增存活客户号、持仓身份及反向名称核对，再继续验证恢复后的 FIFO。客户身份读取通过原账户 Lane 查询辅助器；没有越过活动账户线程边界。`IdentityTestContents` 只观察静止边界的身份内容，位于测试目录，不携带恢复游标或生产格式。

首轮编译发现 5 处测试迁移遗漏，已修正。18 模块编译通过后，继续扫描发现并删除上述两份令牌残留，再对最终源码重新完成同一干净构建：

```text
mvn -pl surprising-aeron-core/surprising-aeron-service,surprising-aeron-core/surprising-aeron-benchmarks,surprising-aeron-core/surprising-aeron-tools,surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-provider,surprising-gateway,surprising-realtime/surprising-realtime-provider -am -DskipTests clean test-compile
```

最终结果：HotSpot JDK 27、Maven 3.9.16，18 个 reactor 模块全部 `SUCCESS`，退出码 0，用时 67.866 秒。日志 SHA-256：`2c4fcb0e344e81a4cbdc1f730bcd575643c4dea22039f9d8ace2dd77998f02ac`。

静态核对：4,014 份当前 classfile 均为 JDK 27 major 71；删除字段和访问器无残留定义/引用，两个旧辅助类型不存在，41 份本轮 Java 源码的哈希与最终编译输入一致。身份准备、释放、回滚及四种原生结果发布的实际业务处理保留；快照冻结、运行态恢复投影、订单状态转换、有序提交、Matcher 编码和长期资金身份源码保持。

清理后继续扫描批量结果构建：`LaneOrderResultTarget.capture/captureFinal` 和账户派发通过接口真实调用 `prepareResponse`，Owner 会移交响应租约。具体实现的直接调用多在测试，不代表缓冲、长度和租约字段无用，本轮保留。

仅执行编译与静态源码/classfile 核对。**未执行测试、服务、SQL、Aeron/Kafka、实际交易或恢复场景，也未执行性能工具**；不能据此宣布全部功能或资金恢复已经运行验证。A154 管理员调账审计生产链仍待修复，下一轮继续从持久请求、原命令结果和已提交余额事实检查。
