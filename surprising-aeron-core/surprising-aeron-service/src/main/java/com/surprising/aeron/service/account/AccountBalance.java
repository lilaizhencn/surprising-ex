package com.surprising.aeron.service.account;

import lombok.Getter;
import lombok.experimental.Accessors;
/** 账户分片持有的某用户某资产余额，可原位冻结、释放、消耗或入账。本对象只更新两项金额，不自行组织线程或核对整笔交易的资金守恒。 */
public final class AccountBalance {

    @Accessors(fluent = true, chain = false)
    @Getter
    private final long userId;
    @Accessors(fluent = true, chain = false)
    @Getter
    private final int assetId;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long availableUnits;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long lockedUnits;

    /** 核对用户为正、资产编号及两项金额非负，创建一份当前资产余额。 */
    public AccountBalance(long userId, int assetId, long availableUnits, long lockedUnits) {
        if (userId <= 0 || assetId < 0 || availableUnits < 0 || lockedUnits < 0) {
            throw new IllegalArgumentException("invalid runtime balance");
        }
        this.userId = userId;
        this.assetId = assetId;
        this.availableUnits = availableUnits;
        this.lockedUnits = lockedUnits;
    }

    /** 先核对两项后金额都非负，再原位替换可用和锁定值，业务来源及守恒由调用方原流程负责。 */
    public void replace(long nextAvailableUnits, long nextLockedUnits) {
        if (nextAvailableUnits < 0 || nextLockedUnits < 0) {
            throw new IllegalArgumentException("invalid runtime balance");
        }
        availableUnits = nextAvailableUnits;
        lockedUnits = nextLockedUnits;
    }

    /** 要求正数金额且可用足够，先精确计算锁定增加，再把同额从可用移入锁定。 */
    public void reserve(long units) {
        if (units <= 0 || availableUnits < units) throw new IllegalArgumentException("insufficient runtime balance");
        long nextLockedUnits = Math.addExact(lockedUnits, units);
        long nextAvailableUnits = availableUnits - units;
        availableUnits = nextAvailableUnits;
        lockedUnits = nextLockedUnits;
    }

    /** 要求非负金额且锁定足够，先精确计算可用增加，再把同额从锁定移回可用，允许零操作。 */
    public void release(long units) {
        if (units < 0 || lockedUnits < units) throw new IllegalArgumentException("invalid runtime release");
        long nextAvailableUnits = Math.addExact(availableUnits, units);
        long nextLockedUnits = lockedUnits - units;
        lockedUnits = nextLockedUnits;
        availableUnits = nextAvailableUnits;
    }

    /** 把非负金额精确加入可用余额，不改变锁定，允许零操作。 */
    public void credit(long units) {
        if (units < 0) throw new IllegalArgumentException("invalid runtime credit");
        availableUnits = Math.addExact(availableUnits, units);
    }
}
