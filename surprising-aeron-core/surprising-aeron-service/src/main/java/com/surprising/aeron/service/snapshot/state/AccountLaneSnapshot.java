package com.surprising.aeron.service.snapshot.state;

import java.util.List;

/**
 * 一个账户 Lane 的恢复元数据和用户成员列表，保存修订号、应用/提交序号及两种本地哈希。它不包含用户余额、订单或持仓明细，账户实体仍由交易状态快照恢复；构造时把用户编号排序、去重校验并固定为只读列表。
 * @param laneId 此快照所属的账户 Lane 编号
 * @param revision 该 Lane 已记录的非负修订号
 * @param appliedSequence 此 Lane 已应用到的序号，不小于提交序号
 * @param committedSequence 此 Lane 已提交到的非负序号
 * @param localStateHash 该 Lane 保存的非零业务状态哈希
 * @param localFundsHash 该 Lane 保存的非零资金状态哈希
 * @param userIds 属于此 Lane 的正数用户编号；构造时复制、排序并拒绝重复
 */
public record AccountLaneSnapshot(
        int laneId,
        long revision,
        long appliedSequence,
        long committedSequence,
        long localStateHash,
        long localFundsHash,
        List<Long> userIds) {

    /**
     * 校验 Lane 编号在零到六十三之间、修订非负、应用序号不早于非负提交序号、两个哈希非零及用户列表非空；逐项要求正数用户且不得重复，排序后复制列表，空表和单项走直接分支。
     */
    public AccountLaneSnapshot {
        if (laneId < 0 || laneId >= Long.SIZE || revision < 0 || appliedSequence < committedSequence
                || committedSequence < 0 || localStateHash == 0 || localFundsHash == 0 || userIds == null) {
            throw new IllegalArgumentException("invalid account lane snapshot");
        }
        // 传入用户列表的条目数，用来选择空表、单项或排序分支。
        int size = userIds.size();
        if (size == 0) {
            userIds = List.of();
        } else if (size == 1) {
            // 只有一项时取出的用户编号，先检查正数再复制。
            Long single = userIds.getFirst();
            if (single == null || single <= 0) {
                throw new IllegalArgumentException("invalid account lane snapshot");
            }
            userIds = List.of(single);
        } else {
            // 排序与重复检查用的临时原语用户编号数组。
            long[] array = new long[size];
            for (/* 当前从输入复制或从已排序数组装入只读列表的下标。 */ int i = 0; i < size; i++) {
                // 输入列表中当前用户编号，空值或非正数都拒绝。
                Long val = userIds.get(i);
                if (val == null || val <= 0) {
                    throw new IllegalArgumentException("invalid account lane snapshot");
                }
                array[i] = val;
            }
            java.util.Arrays.sort(array);
            // 按排序结果装入的 Long 数组，随后由 List.of 固定列表。
            Long[] boxed = new Long[size];
            boxed[0] = array[0];
            for (/* 当前从输入复制或从已排序数组装入只读列表的下标。 */ int i = 1; i < size; i++) {
                if (array[i] == array[i - 1]) {
                    throw new IllegalArgumentException("duplicate account lane user");
                }
                boxed[i] = array[i];
            }
            userIds = List.of(boxed);
        }
    }
}
