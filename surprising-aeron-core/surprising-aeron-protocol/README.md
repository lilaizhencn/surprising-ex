# Aeron 协议

本模块定义六产品线共享的消息外壳、命令载荷、查询响应和实时帧。具体交易行为由 service 执行；协议中的产品线、标的、单位、精度、枚举与结果码是跨服务契约。

## 编解码入口

| 入口 | 职责 |
| --- | --- |
| `CoreMessageHeader`、`CoreMessageCodec` | 命令身份、来源水位、产品线和帧范围 |
| `TradingCommandCodec`、`TradingOrderBatchCodec` | 普通、控制及批量业务载荷 |
| `CoreProtocol` | 终态响应和当前命令 schema |
| 各查询 Codec | 查询参数与结果的确定性布局 |
| `RealtimeFrameCodec` | 实时帧、批次边界和读取请求 |

当前线码和版本以源码常量为准；不从历史清理报告推断格式。新增字段、枚举或单位必须同步所有生产者、消费者和受影响前端。

## 输入与响应租约

`CoreMessage.payloadLength()` 是有效载荷长度；`payloadUnsafe()` 可能包含未使用容量。公开复制接口可长期保留，借用接口只在明确租约内使用。

解码槽在命令终态及消费者完成前不能复用。Owner 完成 Matcher/账户工作、生成并编码响应后归还输入租约。`CoreResponse.data()` 返回独立响应副本；使用借用响应切片时必须同时使用偏移与长度。

`RealtimeFrameCodec.decode(ByteBuffer)` 读取 position/limit 之间的帧，不改变调用者游标；保留长度、身份、枚举、截断和多余字节校验。结果不借用传输缓冲区。

## 验证

选择受影响协议和跨模块功能用例。公共字段变化需要扩大到相应生产者与消费者；协议验证不能替代资金结算或恢复验证。

```bash
mvn -pl surprising-aeron-core/surprising-aeron-protocol -am test \
  -Dtest=<相关测试类> -Dsurefire.failIfNoSpecifiedTests=false
```
