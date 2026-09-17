/**
 * 交易订单簿和外部 matcher 的确定性适配边界。
 *
 * <p>本包负责把不可变撮合输入交给 matcher、收集撮合结果、维护 matcher 分片进度和快照。
 * 本包不拥有用户余额、冻结、持仓、资金费、强平或交割状态；这些变化由提交阶段交给对应
 * 的业务状态所有者。</p>
 */
package com.surprising.aeron.service.matching;
