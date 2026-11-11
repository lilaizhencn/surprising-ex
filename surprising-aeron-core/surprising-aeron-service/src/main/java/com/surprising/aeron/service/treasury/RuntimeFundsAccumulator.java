package com.surprising.aeron.service.treasury;

import com.surprising.aeron.service.settlement.TradeSettlement;
import java.util.Arrays;

/** 单命令或单项结算独占的原语资金变化缓冲。按资产、所有者类别、所有者编号和子账合并有符号金额，同键抵消为零就移除。跨线程消费必须先通过完成标记交接所有权，清空只重置有效长度并保留容量。 */
public final class RuntimeFundsAccumulator {
    private static final TradeSettlement.Posting.OwnerKind[] OWNER_KINDS = TradeSettlement.Posting.OwnerKind.values();
    private static final TradeSettlement.Posting.Subledger[] SUBLEDGERS = TradeSettlement.Posting.Subledger.values();
    private int[] assetIds;
    /** 各有效资金项所有者类别的本地 ordinal 字节列。 */
    private byte[] ownerKinds;
    /** 各有效资金项的所有者编号列。 */
    private long[] ownerIds;
    private byte[] subledgers;
    private long[] units;
    private int size;

    /** 按十六项初始容量创建原语资金缓冲。 */
    public RuntimeFundsAccumulator() {
        this(16);
    }

    /** 按至少一项的容量创建各原语列，实际有效长度初始为零。 */
    public RuntimeFundsAccumulator(int capacity) {
        int initialCapacity = Math.max(1, capacity);
        assetIds = new int[initialCapacity];
        ownerKinds = new byte[initialCapacity];
        ownerIds = new long[initialCapacity];
        subledgers = new byte[initialCapacity];
        units = new long[initialCapacity];
    }

    /** 忽略零金额，检查资产与类别，线性查找同键并精确相加。抵消为零时用尾项填洞，否则更新或追加。本入口不额外校验所有者编号与类别的组合。 */
    public void add(int assetId, TradeSettlement.Posting.OwnerKind ownerKind, long ownerId,
             TradeSettlement.Posting.Subledger subledger, long deltaUnits) {
        if (deltaUnits == 0) return;
        if (assetId < 0 || ownerKind == null || subledger == null) {
            throw new IllegalArgumentException("invalid runtime funds posting");
        }
        byte ownerKindCode = (byte) ownerKind.ordinal();
        byte subledgerCode = (byte) subledger.ordinal();
        for (int index = 0; index < size; index++) {
            if (assetIds[index] != assetId || ownerKinds[index] != ownerKindCode
                    || ownerIds[index] != ownerId || subledgers[index] != subledgerCode) continue;
            long merged = Math.addExact(units[index], deltaUnits);
            if (merged == 0) removeAt(index);
            else units[index] = merged;
            return;
        }
        ensureCapacity(size + 1);
        assetIds[size] = assetId;
        ownerKinds[size] = ownerKindCode;
        ownerIds[size] = ownerId;
        subledgers[size] = subledgerCode;
        units[size] = deltaUnits;
        size++;
    }

    /** 借用给定缓冲的有效原语前缀逐条合并，不清空源。本方法不检查自合并，途中异常不提供整批回滚。 */
    public void add(RuntimeFundsAccumulator other) {
        if (other == null) return;
        for (int index = 0; index < other.size; index++) {
            add(other.assetIds[index], OWNER_KINDS[other.ownerKinds[index]], other.ownerIds[index],
                    SUBLEDGERS[other.subledgers[index]], other.units[index]);
        }
    }

    /** 非外部调整时逐资产精确汇总所有子账变化，合计必须为零。 */
    public void requireConserved(boolean externalAdjustment) {
        if (externalAdjustment) return;
        for (int index = 0; index < size; index++) {
            int assetId = assetIds[index];
            boolean first = true;
            for (int previous = 0; previous < index; previous++) {
                if (assetIds[previous] == assetId) {
                    first = false;
                    break;
                }
            }
            if (!first) continue;
            long total = 0;
            for (int posting = index; posting < size; posting++) {
                if (assetIds[posting] == assetId) total = Math.addExact(total, units[posting]);
            }
            if (total != 0) {
                throw new IllegalArgumentException(
                        "runtime funds delta is not conserved for asset " + assetId);
            }
        }
    }

    /** 仅把有效项数归零，保留全部原语数组和旧尾部，后续只读取新有效前缀。 */
    public void clear() {
        size = 0;
    }

    /** 检查本代是否没有有效资金变化项。 */
    public boolean isEmpty() { return size == 0; }

    /** 移除指定有效项并用最后一项填洞，不保持原始插入顺序。 */
    private void removeAt(int index) {
        int last = --size;
        if (index == last) return;
        assetIds[index] = assetIds[last];
        ownerKinds[index] = ownerKinds[last];
        ownerIds[index] = ownerIds[last];
        subledgers[index] = subledgers[last];
        units[index] = units[last];
    }

    private void ensureCapacity(int required) {
        if (required <= assetIds.length) return;
        int capacity = Math.max(required, Math.multiplyExact(assetIds.length, 2));
        assetIds = Arrays.copyOf(assetIds, capacity);
        ownerKinds = Arrays.copyOf(ownerKinds, capacity);
        ownerIds = Arrays.copyOf(ownerIds, capacity);
        subledgers = Arrays.copyOf(subledgers, capacity);
        units = Arrays.copyOf(units, capacity);
    }
}
