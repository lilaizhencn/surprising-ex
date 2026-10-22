package com.surprising.aeron.service.account;

import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.product.api.ProductLine;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 用户身份、版本和持仓模式的运行态值。普通值保持值语义，Lane 权威值可在原所有权内原位更新，命令槽只在消费者释放后复用；完整余额、冻结和持仓仍在账户表或业务快照中。
 */
public final class UserRuntime {
    /** 用户元数据所属的独立产品线。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private ProductLine productLine;
    /** 正数用户编号。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long userId;
    /** 当前用户业务版本，允许初始版本为零。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private long revision;
    /** 单向或双向持仓模式。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private CorePositionMode positionMode;
    /** 本对象是否由账户 Lane 作为可原位更新的权威元数据持有。 */
    private final boolean laneOwned;
    /** 本对象是否为原物理命令缓冲独占的可复用收据槽。 */
    private final boolean commandSlot;

    /**
     * 简化入口默认 U 本位永续、版本零和单向持仓，创建普通用户值。
     * @param userId 正数用户编号
     */
    public UserRuntime(long userId) {
        this(ProductLine.LINEAR_PERPETUAL, userId, 0, CorePositionMode.ONE_WAY);
    }

    /**
     * 按给定产品、用户、版本及持仓模式创建普通值，不标记 Lane 权威或命令槽。
     * @param productLine 用户元数据所属的独立产品线
     * @param userId 正数用户编号
     * @param revision 当前用户业务版本，允许初始版本为零
     * @param positionMode 单向或双向持仓模式
     */
    public UserRuntime(ProductLine productLine, long userId, long revision, CorePositionMode positionMode) {
        this(productLine, userId, revision, positionMode, false, false);
    }

    /**
     * 校验用户元数据后，固定本对象的 Lane 权威和命令槽用途标记。
     * @param productLine 用户元数据所属的独立产品线
     * @param userId 正数用户编号
     * @param revision 当前用户业务版本，允许初始版本为零
     * @param positionMode 单向或双向持仓模式
     * @param laneOwned 本对象是否由账户 Lane 作为可原位更新的权威元数据持有
     * @param commandSlot 本对象是否为原物理命令缓冲独占的可复用收据槽
     */
    private UserRuntime(ProductLine productLine, long userId, long revision, CorePositionMode positionMode,
                        boolean laneOwned, boolean commandSlot) {
        validate(productLine, userId, revision, positionMode);
        this.productLine = productLine;
        this.userId = userId;
        this.revision = revision;
        this.positionMode = positionMode;
        this.laneOwned = laneOwned;
        this.commandSlot = commandSlot;
    }

    /**
     * 要求产品及持仓模式存在、用户编号为正、版本非负。
     * @param productLine 用户元数据所属的独立产品线
     * @param userId 正数用户编号
     * @param revision 当前用户业务版本，允许初始版本为零
     * @param positionMode 单向或双向持仓模式
     */
    private static void validate(ProductLine productLine, long userId, long revision, CorePositionMode positionMode) {
        if (productLine == null || userId <= 0 || revision < 0 || positionMode == null)
            throw new IllegalArgumentException("invalid runtime user");
    }

    /**
     * 已是 Lane 权威值就返回自身，其他值复制为新的 Lane 权威对象；调用方仍负责在所属 Lane 接管它。
     */
    public UserRuntime laneValue() {
        return laneOwned ? this : new UserRuntime(productLine, userId, revision, positionMode, true, false);
    }

    /**
     * 先校验新版本及模式，命令收据槽禁止直接更新账户；普通值返回新对象，Lane 权威值在原位更新并返回自身。
     * @param revision 当前用户业务版本，允许初始版本为零
     * @param positionMode 单向或双向持仓模式
     */
    public UserRuntime withState(long revision, CorePositionMode positionMode) {
        validate(productLine, userId, revision, positionMode);
        if (commandSlot) throw new IllegalStateException("a user receipt cannot become account authority");
        if (!laneOwned) return new UserRuntime(productLine, userId, revision, positionMode);
        this.revision = revision;
        this.positionMode = positionMode;
        return this;
    }

    /**
     * 普通值可直接作为发布值，Lane 权威或命令槽则复制成独立普通值，避免后续复用影响消费者。
     */
    public UserRuntime publicationValue() {
        return !laneOwned && !commandSlot ? this : new UserRuntime(productLine, userId, revision, positionMode);
    }

    /**
     * 首次建立或复用原命令独占收据槽，复制身份、版本和模式；目标必须是命令槽且调用方已等消费者释放。
     * @param target 调用方持有且已经允许复用的命令槽，null 时新建
     */
    public UserRuntime copyIntoCommandSlot(UserRuntime target) {
        if (target == null)
            return new UserRuntime(productLine, userId, revision, positionMode, false, true);
        if (!target.commandSlot) throw new IllegalArgumentException("user target is not a command cell");
        target.productLine = productLine;
        target.userId = userId;
        target.revision = revision;
        target.positionMode = positionMode;
        return target;
    }

    /**
     * 只清理命令槽的产品、身份和模式引用及版本，存储继续保留，清理后不能作为有效用户使用。
     */
    public void clearCommandSlot() {
        if (!commandSlot) throw new IllegalStateException("only a user command cell may be cleared");
        productLine = null;
        userId = revision = 0;
        positionMode = null;
    }

    /**
     * 按产品、用户、版本和持仓模式比较业务值，不比较 Lane 或命令槽用途。
     * @param other 需要比较的任意对象
     */
    @Override public boolean equals(Object other) {
        return other instanceof UserRuntime /* 已经确认类型的待比较用户值，只核对业务字段。 */ user && productLine == user.productLine && userId == user.userId
                && revision == user.revision && positionMode == user.positionMode;
    }

    /**
     * 组合与 equals 相同的业务字段，清理后的空引用也能生成确定哈希。
     */
    @Override public int hashCode() {
        // 逐字段累计的用户业务哈希，忽略所有权用途标记。
        int hash = productLine == null ? 0 : productLine.hashCode();
        hash = 31 * hash + Long.hashCode(userId);
        hash = 31 * hash + Long.hashCode(revision);
        return 31 * hash + (positionMode == null ? 0 : positionMode.hashCode());
    }

    /**
     * 输出用户身份、产品、版本和持仓模式，供诊断与断言使用。
     */
    @Override public String toString() {
        return "UserRuntime[productLine=" + productLine + ", userId=" + userId + ", revision=" + revision
                + ", positionMode=" + positionMode + "]";
    }
}
