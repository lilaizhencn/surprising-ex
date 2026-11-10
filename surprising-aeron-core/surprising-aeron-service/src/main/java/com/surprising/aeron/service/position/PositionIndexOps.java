package com.surprising.aeron.service.position;

import com.surprising.aeron.service.account.AccountPartition;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

/**
 * 持仓在 AccountPartition 内部的标的与用户暴露索引操作。
 */
public final class PositionIndexOps {

    private PositionIndexOps() {}

    /**
     * 替换或维护持仓索引。
     */
    public static void replacePositionIndexes(AccountPartition lane, long positionKey,
                                               Position previous, Position replacement) {
        if (previous == null) {
            indexPosition(lane, positionKey, replacement);
            return;
        }
        if (previous.symbolId() == replacement.symbolId()
                && (previous.signedQuantitySteps() != 0) == (replacement.signedQuantitySteps() != 0)) {
            return;
        }
        unindexOpenPosition(lane, positionKey, previous);
        indexOpenPosition(lane, positionKey, replacement);
    }

    /**
     * 登记用户持仓键，并按非零暴露条件维护标的活动持仓索引。
     */
    public static void indexPosition(AccountPartition lane, long positionKey, Position position) {
        lane.indexPositionKey(position.userId(), positionKey);
        indexOpenPosition(lane, positionKey, position);
    }

    /**
     * 只为非零持仓在标的与用户下保存有序原始键列表，二分插入避免重复身份。
     */
    public static void indexOpenPosition(AccountPartition lane, long positionKey, Position position) {
        if (position.signedQuantitySteps() == 0) return;
        LongObjectHashMap<LongArrayList> byUser = lane.symbolPositionKeys(position.symbolId());
        if (byUser == null) {
            byUser = new LongObjectHashMap<>();
            lane.storeSymbolPositionKeys(position.symbolId(), byUser);
        }
        LongArrayList keys = byUser.get(position.userId());
        if (keys == null) {
            keys = new LongArrayList(2);
            byUser.put(position.userId(), keys);
        }
        int index = keys.binarySearch(positionKey);
        if (index < 0) keys.addAtIndex(-index - 1, positionKey);
    }

    /**
     * 删除用户持仓键，并撤销对应标的活动索引。
     */
    public static void unindexPosition(AccountPartition lane, long positionKey, Position position) {
        lane.unindexPositionKey(position.userId(), positionKey);
        unindexOpenPosition(lane, positionKey, position);
    }

    /**
     * 从标的及用户有序键列表删除指定身份，列表或分组空后逐层清理。
     */
    public static void unindexOpenPosition(AccountPartition lane, long positionKey,
                                            long userId, int symbolId) {
        LongObjectHashMap<LongArrayList> byUser = lane.symbolPositionKeys(symbolId);
        LongArrayList keys = byUser == null ? null : byUser.get(userId);
        if (keys == null) return;
        int index = keys.binarySearch(positionKey);
        if (index < 0) return;
        keys.removeAtIndex(index);
        if (keys.isEmpty()) byUser.remove(userId);
        if (byUser.isEmpty()) lane.removeSymbolPositionKeys(symbolId);
    }

    /**
     * 旧持仓非零时按其用户和标的删除活动持仓索引，零持仓无需处理。
     */
    public static void unindexOpenPosition(AccountPartition lane, long positionKey, Position position) {
        if (position.signedQuantitySteps() == 0) return;
        unindexOpenPosition(lane, positionKey, position.userId(), position.symbolId());
    }
}
