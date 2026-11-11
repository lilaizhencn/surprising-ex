package com.surprising.aeron.service.snapshot.state;

import java.util.List;
import com.surprising.aeron.support.collection.ImmutableLongArrayList;

/** 一个账户 Lane 的恢复元数据和用户成员列表，保存修订号、应用/提交序号及两种本地哈希。它不包含用户余额、订单或持仓明细，账户实体仍由交易状态快照恢复。 */
public record AccountLaneSnapshot(
        int laneId,
        long revision,
        long appliedSequence,
        long committedSequence,
        long localStateHash,
        long localFundsHash,
        List<Long> userIds) {

    /** 逐项要求正数用户且不得重复，排序后复制列表，空表和单项走直接分支。 */
    public AccountLaneSnapshot {
        if (laneId < 0 || laneId >= Long.SIZE || revision < 0 || appliedSequence < committedSequence
                || committedSequence < 0 || localStateHash == 0 || localFundsHash == 0 || userIds == null) {
            throw new IllegalArgumentException("invalid account lane snapshot");
        }
        int size = userIds.size();
        if (size == 0) {
            userIds = List.of();
        } else if (size == 1) {
            Long single = userIds.getFirst();
            if (single == null || single <= 0) {
                throw new IllegalArgumentException("invalid account lane snapshot");
            }
            userIds = List.of(single);
        } else {
            long[] array = new long[size];
            for (int i = 0; i < size; i++) {
                Long val = userIds.get(i);
                if (val == null || val <= 0) {
                    throw new IllegalArgumentException("invalid account lane snapshot");
                }
                array[i] = val;
            }
            java.util.Arrays.sort(array);
            for (int i = 1; i < size; i++) {
                if (array[i] == array[i - 1]) {
                    throw new IllegalArgumentException("duplicate account lane user");
                }
            }
            userIds = ImmutableLongArrayList.takeOwnership(array);
        }
    }
}
