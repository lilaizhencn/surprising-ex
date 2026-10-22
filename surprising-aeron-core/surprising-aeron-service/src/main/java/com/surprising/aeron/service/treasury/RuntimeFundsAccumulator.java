package com.surprising.aeron.service.treasury;

import com.surprising.aeron.service.settlement.FundsPosting;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * 单命令或单项结算独占的原语资金变化缓冲。按资产、所有者类别、所有者编号和子账合并有符号金额，同键抵消为零就移除；用于资金守恒核对和回执交接，不保存第二份账户余额。跨线程消费必须先通过完成标记交接所有权，清空只重置有效长度并保留容量。
 */
public final class RuntimeFundsAccumulator {
    /** 按枚举 ordinal 解码当前缓冲字节列的类别表，只是本地存储编码，不使用 FundsPosting 固定 wireCode。 */
    private static final FundsPosting.OwnerKind[] OWNER_KINDS = FundsPosting.OwnerKind.values();
    /** 按枚举 ordinal 解码当前缓冲子账字节列的取值表。 */
    private static final FundsPosting.Subledger[] SUBLEDGERS = FundsPosting.Subledger.values();
    /** 各有效资金项的资产稠密编号列。 */
    private int[] assetIds;
    /** 各有效资金项所有者类别的本地 ordinal 字节列。 */
    private byte[] ownerKinds;
    /** 各有效资金项的所有者编号列。 */
    private long[] ownerIds;
    /** 各有效资金项子账类别的本地 ordinal 字节列。 */
    private byte[] subledgers;
    /** 各有效资金项合并后的有符号变化金额列。 */
    private long[] units;
    /** 本轮有效资金项数，容量尾部不属于当前事实。 */
    private int size;

    /**
     * 按十六项初始容量创建原语资金缓冲。
     */
    public RuntimeFundsAccumulator() {
        this(16);
    }

    /**
     * 按至少一项的容量创建各原语列，实际有效长度初始为零。
     * @param capacity 期望初始项数，非正数在此按至少一项处理
     */
    public RuntimeFundsAccumulator(int capacity) {
        // 按至少一项修正后的初始原语列容量。
        int initialCapacity = Math.max(1, capacity);
        assetIds = new int[initialCapacity];
        ownerKinds = new byte[initialCapacity];
        ownerIds = new long[initialCapacity];
        subledgers = new byte[initialCapacity];
        units = new long[initialCapacity];
    }

    /**
     * 忽略零金额，检查资产与类别，线性查找同键并精确相加；抵消为零时用尾项填洞，否则更新或追加。本入口不额外校验所有者编号与类别的组合。
     * @param assetId 本条资金变化的非负资产编号
     * @param ownerKind 本条资金变化的所有者类别
     * @param ownerId 本条资金变化的所有者编号，组合约束由调用方保证
     * @param subledger 本条资金变化归入的子账
     * @param deltaUnits 有符号资金变化量，零值直接忽略
     */
    public void add(int assetId, FundsPosting.OwnerKind ownerKind, long ownerId,
             FundsPosting.Subledger subledger, long deltaUnits) {
        if (deltaUnits == 0) return;
        if (assetId < 0 || ownerKind == null || subledger == null) {
            throw new IllegalArgumentException("invalid runtime funds posting");
        }
        // 当前所有者类别在本地列中的 ordinal 字节。
        byte ownerKindCode = (byte) ownerKind.ordinal();
        // 当前子账在本地列中的 ordinal 字节。
        byte subledgerCode = (byte) subledger.ordinal();
        for (/* 当前查找、读取或物化的有效资金项下标。 */ int index = 0; index < size; index++) {
            if (assetIds[index] != assetId || ownerKinds[index] != ownerKindCode
                    || ownerIds[index] != ownerId || subledgers[index] != subledgerCode) continue;
            // 同键资金变化精确相加后的金额，零表示需要移除。
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

    /**
     * 将分录值中的各项逐条加入当前缓冲，空输入或空分录不处理；逐项合并并不提供整批异常回滚。
     * @param delta 需要逐项加入的分录值，可为空
     */
    public void add(RuntimeFundsDelta delta) {
        if (delta == null || delta.postingCount() == 0) return;
        // 待读取或物化的分录列表，转出时由新建列表独立持有。
        var postings = delta.postings();
        for (/* 当前查找、读取或物化的有效资金项下标。 */ int index = 0; index < postings.size(); index++) {
            // 当前分录对象，或守恒汇总循环中的有效项下标。
            RuntimeFundsDelta.Posting posting = postings.get(index);
            add(posting.assetId(), posting.ownerKind(), posting.ownerId(), posting.subledger(), posting.units());
        }
    }

    /**
     * 借用给定缓冲的有效原语前缀逐条合并，不清空源；本方法不检查自合并，途中异常不提供整批回滚。
     * @param other 需要遍历加入的原语资金缓冲，可为空
     */
    public void add(RuntimeFundsAccumulator other) {
        if (other == null) return;
        for (/* 当前查找、读取或物化的有效资金项下标。 */ int index = 0; index < other.size; index++) {
            add(other.assetIds[index], OWNER_KINDS[other.ownerKinds[index]], other.ownerIds[index],
                    SUBLEDGERS[other.subledgers[index]], other.units[index]);
        }
    }

    /**
     * 把有效原语前缀物化为独立分录对象列表，交给已去重入口包装；当前为空时返回共用空值，不在这里检查资金守恒。
     */
    public RuntimeFundsDelta toDelta() {
        if (size == 0) return RuntimeFundsDelta.empty();
        // 待读取或物化的分录列表，转出时由新建列表独立持有。
        ArrayList<RuntimeFundsDelta.Posting> postings = new ArrayList<>(size);
        for (/* 当前查找、读取或物化的有效资金项下标。 */ int index = 0; index < size; index++) {
            postings.add(new RuntimeFundsDelta.Posting(assetIds[index], OWNER_KINDS[ownerKinds[index]],
                    ownerIds[index], SUBLEDGERS[subledgers[index]], units[index]));
        }
        return RuntimeFundsDelta.fromDistinct(postings);
    }

    /**
     * 非外部调整时逐资产精确汇总所有子账变化，合计必须为零；外部调整开关由调用方决定，开启时直接跳过守恒核对。
     * @param externalAdjustment 本命令是否按外部资金调整处理，为 true 时跳过资产合计为零检查
     */
    public void requireConserved(boolean externalAdjustment) {
        if (externalAdjustment) return;
        for (/* 当前查找、读取或物化的有效资金项下标。 */ int index = 0; index < size; index++) {
            // 当前按资产核对的稠密编号。
            int assetId = assetIds[index];
            // 当前项是否为该资产在有效前缀中的首次出现，避免重复汇总。
            boolean first = true;
            for (/* 为判断资产是否已核对而检查的前面项下标。 */ int previous = 0; previous < index; previous++) {
                if (assetIds[previous] == assetId) {
                    first = false;
                    break;
                }
            }
            if (!first) continue;
            // 当前资产所有有效资金变化的精确合计。
            long total = 0;
            for (/* 当前分录对象，或守恒汇总循环中的有效项下标。 */ int posting = index; posting < size; posting++) {
                if (assetIds[posting] == assetId) total = Math.addExact(total, units[posting]);
            }
            if (total != 0) {
                throw new IllegalArgumentException(
                        "runtime funds delta is not conserved for asset " + assetId);
            }
        }
    }

    /**
     * 仅把有效项数归零，保留全部原语数组和旧尾部，后续只读取新有效前缀。
     */
    public void clear() {
        size = 0;
    }

    /**
     * 检查本代是否没有有效资金变化项。
     */
    public boolean isEmpty() { return size == 0; }

    /**
     * 在双方独占且目标为空时交换所有原语数组并移交有效长度，源接回目标空存储；源为空时不交换，不重新合并金额或改变当前前缀顺序。
     * @param target 不同于当前源且由调用方独占的目标缓冲，空目标入口还要求其有效长度为零
     */
    public void transferToEmpty(RuntimeFundsAccumulator target) {
        if (target == null || target == this || target.size != 0) {
            throw new IllegalArgumentException("funds transfer requires a distinct empty target");
        }
        if (size == 0) return;
        // 交换期间暂存的目标资产列，源随后接管该空存储。
        int[] assets = target.assetIds; target.assetIds = assetIds; assetIds = assets;
        // 交换期间暂存的目标所有者类别列。
        byte[] owners = target.ownerKinds; target.ownerKinds = ownerKinds; ownerKinds = owners;
        // 交换期间暂存的目标所有者编号列。
        long[] ids = target.ownerIds; target.ownerIds = ownerIds; ownerIds = ids;
        // 交换期间暂存的目标子账类别列。
        byte[] ledgers = target.subledgers; target.subledgers = subledgers; subledgers = ledgers;
        // 交换期间暂存的目标金额列。
        long[] amounts = target.units; target.units = units; units = amounts;
        target.size = size;
        size = 0;
    }

    /**
     * 目标为空就交换存储；目标已有批次前缀时先检查所有同键金额溢出并准备容量，再合并并清源。金额预检失败不消费源，也不改变目标有效资金项。
     * @param target 不同于当前源且由调用方独占的目标缓冲，空目标入口还要求其有效长度为零
     */
    public void transferTo(RuntimeFundsAccumulator target) {
        if (target == null || target == this)
            throw new IllegalArgumentException("funds transfer requires a distinct target");
        if (target.size == 0) {
            transferToEmpty(target);
            return;
        }
        for (/* 合并预检中当前源缓冲有效项下标。 */ int source = 0; source < size; source++) {
            for (/* 合并预检中当前目标缓冲已有项下标。 */ int existing = 0; existing < target.size; existing++) {
                if (assetIds[source] == target.assetIds[existing]
                        && ownerKinds[source] == target.ownerKinds[existing]
                        && ownerIds[source] == target.ownerIds[existing]
                        && subledgers[source] == target.subledgers[existing]) {
                    Math.addExact(units[source], target.units[existing]);
                    break;
                }
            }
        }
        target.ensureCapacity(Math.addExact(target.size, size));
        target.add(this);
        clear();
    }

    /**
     * 移除指定有效项并用最后一项填洞，不保持原始插入顺序；调用方提供有效下标。
     * @param index 待移除项的有效下标
     */
    private void removeAt(int index) {
        // 删除后原末项所在下标，用来填补被删除的位置。
        int last = --size;
        if (index == last) return;
        assetIds[index] = assetIds[last];
        ownerKinds[index] = ownerKinds[last];
        ownerIds[index] = ownerIds[last];
        subledgers[index] = subledgers[last];
        units[index] = units[last];
    }

    /**
     * 有效量超过容量才按所需量或两倍旧容量扩展全部原语列，保留当前内容。
     * @param required 各原语列至少需要容纳的有效项数
     */
    private void ensureCapacity(int required) {
        if (required <= assetIds.length) return;
        // 本次原语列扩容后的长度，至少容纳 required 项。
        int capacity = Math.max(required, Math.multiplyExact(assetIds.length, 2));
        assetIds = Arrays.copyOf(assetIds, capacity);
        ownerKinds = Arrays.copyOf(ownerKinds, capacity);
        ownerIds = Arrays.copyOf(ownerIds, capacity);
        subledgers = Arrays.copyOf(subledgers, capacity);
        units = Arrays.copyOf(units, capacity);
    }
}
