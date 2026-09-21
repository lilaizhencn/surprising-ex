/**
 * 交易核心运行状态边界。
 *
 * <p>{@code TradingRuntimeState} 及 Account Lane 是余额、冻结、订单、持仓、风险和财库事实的唯一
 * 可写权威状态。命令直接调用对应的 {@code Runtime*} 业务转换；查询、恢复和快照才允许物化
 * {@code TradingCoreState}，物化结果不得反向参与热路径写入。</p>
 *
 * <p>instrument 在启动阶段注册后保持 canonical 对象引用；撮合与结算直接传递该引用。跨线程事件只
 * 携带确定性业务事实和必要的 primitive 标识，不创建第二套状态、版本 hash 或兼容 reducer。</p>
 */
package com.surprising.aeron.service.state;
