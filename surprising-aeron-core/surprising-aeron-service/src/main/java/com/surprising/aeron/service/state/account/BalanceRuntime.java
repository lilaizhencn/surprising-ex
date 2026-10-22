package com.surprising.aeron.service.state.account;

import lombok.Getter;
import lombok.experimental.Accessors;
/**
 * 账户 Lane 持有的某用户某资产余额，可原位冻结、释放、消耗或入账。调用方遵守原 Lane 或读取屏障边界；本对象只更新两项金额，不自行组织线程或核对整笔交易的资金守恒。
 */
public final class BalanceRuntime {

    /**  本份余额所属的正数用户编号。 返回本份余额所属的正数用户编号。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final long userId;
    /**  资产的运行时整数编号。 返回资产的运行时整数编号。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final int assetId;
    /**  当前可以使用的整数余额。 返回当前可以使用的整数余额。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long availableUnits;
    /**  当前已经冻结或占用的整数余额。 返回当前已经冻结或占用的整数余额。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long lockedUnits;

    /**
     * 核对用户为正、资产编号及两项金额非负，创建一份当前资产余额。
     * @param userId 本份余额所属的正数用户编号
     * @param assetId 资产的运行时整数编号
     * @param availableUnits 当前可以使用的整数余额
     * @param lockedUnits 当前已经冻结或占用的整数余额
     */
    public BalanceRuntime(long userId, int assetId, long availableUnits, long lockedUnits) {
        if (userId <= 0 || assetId < 0 || availableUnits < 0 || lockedUnits < 0) {
            throw new IllegalArgumentException("invalid runtime balance");
        }
        this.userId = userId;
        this.assetId = assetId;
        this.availableUnits = availableUnits;
        this.lockedUnits = lockedUnits;
    }


    /**
     * 先核对两项后金额都非负，再原位替换可用和锁定值，业务来源及守恒由调用方原流程负责。
     * @param nextAvailableUnits 替换后的非负可用金额
     * @param nextLockedUnits 替换后的非负锁定金额
     */
    public void replace(long nextAvailableUnits, long nextLockedUnits) {
        if (nextAvailableUnits < 0 || nextLockedUnits < 0) {
            throw new IllegalArgumentException("invalid runtime balance");
        }
        availableUnits = nextAvailableUnits;
        lockedUnits = nextLockedUnits;
    }

    /**
     * 要求正数金额且可用足够，先精确计算锁定增加，再把同额从可用移入锁定。
     * @param units 本次需要从可用移到锁定的正数金额
     */
    public void reserve(long units) {
        if (units <= 0 || availableUnits < units) throw new IllegalArgumentException("insufficient runtime balance");
        // 锁定加本次冻结金额的精确结果，溢出时还没有写余额字段。
        long nextLockedUnits = Math.addExact(lockedUnits, units);
        // 可用扣除本次冻结后的金额，先前已检查足额。
        long nextAvailableUnits = availableUnits - units;
        availableUnits = nextAvailableUnits;
        lockedUnits = nextLockedUnits;
    }

    /**
     * 要求非负金额且锁定足够，先精确计算可用增加，再把同额从锁定移回可用，允许零操作。
     * @param units 本次从锁定退回可用的非负金额
     */
    public void release(long units) {
        if (units < 0 || lockedUnits < units) throw new IllegalArgumentException("invalid runtime release");
        // 可用加释放金额的精确结果，溢出时保留原余额。
        long nextAvailableUnits = Math.addExact(availableUnits, units);
        // 锁定扣除释放金额后的非负结果。
        long nextLockedUnits = lockedUnits - units;
        lockedUnits = nextLockedUnits;
        availableUnits = nextAvailableUnits;
    }

    /**
     * 从锁定扣除非负金额，不把它退到可用；成交或结算的其他资金去向由调用方处理。
     * @param units 本次要从锁定实际消耗的非负金额
     */
    public void consumeLocked(long units) {
        if (units < 0 || lockedUnits < units) throw new IllegalArgumentException("invalid runtime locked consumption");
        lockedUnits -= units;
    }

    /**
     * 把非负金额精确加入可用余额，不改变锁定，允许零操作。
     * @param units 本次要增加到可用的非负金额
     */
    public void credit(long units) {
        if (units < 0) throw new IllegalArgumentException("invalid runtime credit");
        availableUnits = Math.addExact(availableUnits, units);
    }
}
