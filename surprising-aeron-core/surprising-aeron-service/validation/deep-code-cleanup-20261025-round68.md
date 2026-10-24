# 第六十八轮：账户控制与划转直接使用原始业务数据

本轮基于3b983cac848d343056517d7aac3acedab1fb2271，解决A214–A221八组。累计216项解决、A11候选、4项保留；全量字段与约束审计继续。

业务仍按命令解码与身份检查→所属账户Lane执行→原完成回执→Owner发布→原提交/账本校验推进。划转源端扣可用余额、保存待完成命令，目标端独立入账，源端确认后删除待完成身份；完整请求及长期资金身份负责防重。

| 编号 | 清理对象 | 实现与保留职责 |
| --- | --- | --- |
| A214 | 账户续步的 Lane 编号与装箱结果中转 | 所属Lane已由派发位图确定；布尔变化写入原transferCreated字段改名changedOnLane，保证金资产编号写入原assetId。Owner仍在原release/acquire回执后读取，stateChanged发布标记单独保留。 |
| A215 | 杠杆参数副本 | 两份标量改为引用原不可变UpdateLeverageCommand，净减1字段；实际杠杆键与合约编号保留。 |
| A216 | 待完成划转运行时包装 | 删除userId+command包装，Lane、运行时、快照接口、哈希与回放直接使用TransferFundsCommand；所属用户取sourceUserId，完整命令幂等比较保留。 |
| A217 | 划转快照与哈希中的重复源用户 | 待完成划转段只读v3，省去重复数值字段1，每条少16字节；哈希每个原命令字段混入一次。容量、重复ID、版本与跨段配对检查保留。 |
| A218 | 待完成查询包装和重复用户线字段 | 查询直接编码原命令列表，每条少8字节；Core schema升17，Gateway从同一命令构造原公开请求，保留源/目标用户与其余公开字段。 |
| A219 | 转入重建余额命令与纯转发 | 余额续步只接收实际资产和增量，转入直接传金额；收款用户先于目标产品校验的拒绝顺序保留。 |
| A220 | 非在线余额和划转同步入口 | 当前classfile完整描述符证明调用来自冷测试及一次基准setup；删除3个生产同步入口，调用方共用原AccountControlTask，基准只改setup且未运行JMH。 |
| A221 | 非在线衍生账户同步入口 | 删除3个仅冷测试调用的同步入口，测试改走原账户续步；保留实际账户计算、业务检查、修订及Owner发布。 |

生产20文件新增127行、删除325行，净减198行；没有新增生产类、接口或阶段。所核对五个类型的非static、非synthetic声明实例字段24→18：AccountControlTask从20→18，两个二字段包装类删除。删除的是6个同步入口及1个私有纯转发方法，不把改签名、生成访问器或调用点数量算成方法删除。

划转的11个原命令业务字段完整保留。源用户不再复制到包装、查询或快照；Owner用于派发的transferId→userId路由索引仍有真实用途，恢复时从命令重建。查询每条省8字节，快照每条省一个8字节字段头加8字节用户值。内部schema16→17，划转段v2→v3；只读取当前格式，遵循用户无需读取旧格式的确认。其余快照格式未改。

账户结果仍经过原完成位的release/acquire交接；没有把Lane的变化位提前当成Owner发布位。重复转出即使没有新扣款，原stateChanged发布语义仍保留。9个底层余额、来源校验、扣款、完成及衍生账户方法正文完全相同；原整数运算、溢出预检、冻结资金、完整命令冲突、容量、公平分页、修订和回滚边界保留。

HotSpot JDK27与Maven3.9.16下，18模块干净构建通过。完整回归1973例：1972通过，1例Linux CPU亲和性检查在Mac OS X跳过，0失败/错误。另在PIPELINED配置补跑131例，真实PostgreSQL投影5例及Gateway7例全部通过。新增检查覆盖查询帧的精确长度及损坏拒绝、快照v3的原命令完整性与旧版拒绝、单/四Lane续步复用、资产编号超过127、保证金增减、模式/杠杆实际与重复变化；可用900、冻结100时转出901被拒绝且余额/路由不变。

修改生产代码前，完整基线发现旧测试问题：schema9指纹、直接缓冲调用array、恢复仍期待节点队列容量来自快照、两个匿名账本初始化器误读继承的空runtime、风险查询前后比较未规范化冷值、Direct槽仍反射旧内部owner，以及已经删除的隐式用户V1成功预期。均修正测试，未放宽生产守卫；当前转账的回放、近期结果淘汰、恢复与冲突断言保留，并新增V1拒绝检查。本轮升schema后还有3个严格报文/摘要预期需要同步；两个独立Python向量分别验证16与17。迁移脚本一度生成两个非法测试import，修复后干净构建与回归通过，失败日志均归档。

零金额调账事实没有删除：真实实时导出及SQL审计消费它来完成开户/调账记录，收益现金流投影另行跳过零金额。旧组合测试预期改为核对金额0和准确事后余额；真实SQL零开户及大额总余额用例通过，未将必要审计事实计为冗余。

| 产品线 | HTTP/WebSocket | 本线冷恢复次数 | 主场景恢复命令数 | 主场景恢复哈希 |
| --- | --- | --- | --- | --- |
| SPOT | 通过 | 3 | 42 | 029bdac2dc88ec19 |
| LINEAR_PERPETUAL | 通过 | 6 | 754 | 24a235751149dfb0 |
| INVERSE_PERPETUAL | 通过 | 6 | 439 | de6158d3f2f66087 |
| LINEAR_DELIVERY | 通过 | 3 | 770 | 882a132d21b86956 |
| INVERSE_DELIVERY | 通过 | 3 | 777 | 3e243a3b8a3521cd |
| OPTION | 通过 | 3 | 2181 | 05ce578cf258f5ce |

六线均完成仓库定义的关键做市、下单/撤单/成交、账户/终态、公开及私有WS、配置和输入拒绝验证。两种永续额外完成真实资金费、强平、保险、ADL与恢复；交割检查到期结算和持仓/冻结归零，期权检查OCO及六行CALL/PUT到期与资金守恒。逐线完成并停止后才启动下一线。

每条产品线另用独立Core目录做同产品跨用户划转：初始1000/100，转出37后963/100并保留完整待完成命令；实际请求快照、停止原JVM、从原数据恢复，再拒绝错误源用户/收款人/完成用户，合法入账与完成后963/137、总额1100。已完成状态再冷恢复，重复转出/转入不再扣/加款，待完成查询为空。每线两次，共12次新增恢复；跨产品划转另由六线PIPELINED本地双Core测试覆盖，不混称网络跨产品实测。

共24次实际Cluster冷恢复。每次有效service/consensus快照记录均增加1，确认旧驱动失活后从原目录启动，应用命令数与完整业务哈希一致。驱动失活轮询的暂时非零返回是等待记录，最终均通过。

现货完成3次恢复后，线性永续首次启动被遗留的现货active.lock拦在服务启动前：CORE_ONLY停止只释放core-PRODUCT.lock。确认本轮PID已空后，使用原现货RUN_ID的完整停止入口释放锁，补上逐线完整停止，再从原记录继续；没有重置Core交易数据或改生产代码。失败和原成功场景一并保留。

当前4018份classfile均major71，完整所属类/名称/描述符确认6个同步入口及旧余额准备签名、两个包装类型的定义与调用均消失；AccountControlTask无Boolean/Integer引用。Core、tools、benchmarks及Gateway实际运行包的相关classfile与当前编译结果一致，其他生产Java源码哈希与基线相同。HTTP/WebSocket及前端契约未改。

实际命令、退出码、固定金额、恢复哈希、完整测试身份、源码/运行包/日志摘要及门禁源码见[机器证据](round68-runtime-20261025.json)。

```text
mvn -pl surprising-aeron-core/surprising-aeron-service,surprising-aeron-core/surprising-aeron-tools,surprising-aeron-core/surprising-aeron-benchmarks,surprising-price/surprising-price-provider,surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-provider,surprising-realtime/surprising-realtime-provider,surprising-gateway,surprising-maker -am -DskipTests clean package
mvn -pl surprising-aeron-core/surprising-aeron-service -am test
mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=AccountControlTaskResultTest,CoreTransferLaneLifecycleTest,CoreFundsIdempotencyTest,CorePerpetualFinancialMatrixTest,CoreDeliveryOptionFinancialMatrixTest,RuntimeStateBusinessRulesTest,UserRevisionReuseTest -Dsurprising.aeron.execution-mode=PIPELINED -Dsurefire.failIfNoSpecifiedTests=false test
INSTRUMENT_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:15432/sixline_live INSTRUMENT_TEST_DB_USER=sixline_qa INSTRUMENT_TEST_DB_PASSWORD=qa mvn -pl surprising-realtime/surprising-realtime-provider,surprising-gateway -am -Dtest=AccountCommandGatewayTest,CommittedBalanceAdjustmentPostgresTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl surprising-gateway -am -Dtest=SixProductLineContractLiveTest -Dsurefire.failIfNoSpecifiedTests=false test
```

真实Cluster为单成员FUSED；PIPELINED另有上述回归。没有验证多节点切主、强杀或在途崩溃，没有运行浏览器/Flutter点击、wallet、JMH/JFR或压测，不宣称性能提升、长周期定时任务或所有可选功能均覆盖。验证摘要归档后关闭本轮依赖并核对PID/端口、清理本轮临时目录；实际清理结果见机器证据cleanup。
