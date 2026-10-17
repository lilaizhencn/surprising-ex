package com.surprising.aeron.service.state;

import java.util.List;

public record AccountLaneSnapshot(
        int laneId,
        long revision,
        long appliedSequence,
        long committedSequence,
        long localStateHash,
        long localFundsHash,
        List<Long> userIds) {

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
            Long[] boxed = new Long[size];
            boxed[0] = array[0];
            for (int i = 1; i < size; i++) {
                if (array[i] == array[i - 1]) {
                    throw new IllegalArgumentException("duplicate account lane user");
                }
                boxed[i] = array[i];
            }
            userIds = List.of(boxed);
        }
    }
}
