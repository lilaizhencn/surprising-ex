package com.surprising.aeron.service.account;

import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.product.api.ProductLine;
import lombok.Getter;
import lombok.experimental.Accessors;

/** 用户身份、版本和持仓模式的运行态值。 */
public final class UserAccount {
    @Accessors(fluent = true, chain = false)
    @Getter
    private ProductLine productLine;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long userId;
    @Accessors(fluent = true, chain = false)
    @Getter
    private long revision;
    @Accessors(fluent = true, chain = false)
    @Getter
    private CorePositionMode positionMode;
    private final boolean laneOwned;
    private final boolean commandSlot;

    /** 简化入口默认 U 本位永续、版本零和单向持仓，创建普通用户值。 */
    public UserAccount(long userId) {
        this(ProductLine.LINEAR_PERPETUAL, userId, 0, CorePositionMode.ONE_WAY);
    }

    /** 按给定产品、用户、版本及持仓模式创建普通值，不标记分片权威或命令槽。 */
    public UserAccount(ProductLine productLine, long userId, long revision, CorePositionMode positionMode) {
        this(productLine, userId, revision, positionMode, false, false);
    }

    private UserAccount(ProductLine productLine, long userId, long revision, CorePositionMode positionMode,
                        boolean laneOwned, boolean commandSlot) {
        validate(productLine, userId, revision, positionMode);
        this.productLine = productLine;
        this.userId = userId;
        this.revision = revision;
        this.positionMode = positionMode;
        this.laneOwned = laneOwned;
        this.commandSlot = commandSlot;
    }

    private static void validate(ProductLine productLine, long userId, long revision, CorePositionMode positionMode) {
        if (productLine == null || userId <= 0 || revision < 0 || positionMode == null)
            throw new IllegalArgumentException("invalid runtime user");
    }

    /** 已是分片权威值就返回自身，其他值复制为新的分片权威对象。 */
    public UserAccount laneValue() {
        return laneOwned ? this : new UserAccount(productLine, userId, revision, positionMode, true, false);
    }

    /** 先校验新版本及模式，命令收据槽禁止直接更新账户。 */
    public UserAccount withState(long revision, CorePositionMode positionMode) {
        validate(productLine, userId, revision, positionMode);
        if (commandSlot) throw new IllegalStateException("a user receipt cannot become account authority");
        if (!laneOwned) return new UserAccount(productLine, userId, revision, positionMode);
        this.revision = revision;
        this.positionMode = positionMode;
        return this;
    }

    /** 普通值可直接作为发布值，分片权威或命令槽则复制成独立普通值，避免后续复用影响消费者。 */
    public UserAccount publicationValue() {
        return !laneOwned && !commandSlot ? this : new UserAccount(productLine, userId, revision, positionMode);
    }

    /** 首次建立或复用原命令独占收据槽，复制身份、版本和模式。目标必须是命令槽且调用方已等消费者释放。 */
    public UserAccount copyIntoCommandSlot(UserAccount target) {
        if (target == null)
            return new UserAccount(productLine, userId, revision, positionMode, false, true);
        if (!target.commandSlot) throw new IllegalArgumentException("user target is not a command cell");
        target.productLine = productLine;
        target.userId = userId;
        target.revision = revision;
        target.positionMode = positionMode;
        return target;
    }

    /** 只清理命令槽的产品、身份和模式引用及版本，存储继续保留，清理后不能作为有效用户使用。 */
    public void clearCommandSlot() {
        if (!commandSlot) throw new IllegalStateException("only a user command cell may be cleared");
        productLine = null;
        userId = revision = 0;
        positionMode = null;
    }

    /** 按产品、用户、版本和持仓模式比较业务值，不比较分片或命令槽用途。 */
    @Override public boolean equals(Object other) {
        return other instanceof UserAccount user && productLine == user.productLine && userId == user.userId
                && revision == user.revision && positionMode == user.positionMode;
    }

    /** 组合与 equals 相同的业务字段，清理后的空引用也能生成确定哈希。 */
    @Override public int hashCode() {
        int hash = productLine == null ? 0 : productLine.hashCode();
        hash = 31 * hash + Long.hashCode(userId);
        hash = 31 * hash + Long.hashCode(revision);
        return 31 * hash + (positionMode == null ? 0 : positionMode.hashCode());
    }

    /** 输出用户身份、产品、版本和持仓模式，供诊断与断言使用。 */
    @Override public String toString() {
        return "UserAccount[productLine=" + productLine + ", userId=" + userId + ", revision=" + revision
                + ", positionMode=" + positionMode + "]";
    }
}
