package com.surprising.aeron.service.state.account;

import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.product.api.ProductLine;

/** Owned values are immutable. Only an Account Lane's authority and leased command cells are reusable. */
public final class UserRuntime {
    private ProductLine productLine;
    private long userId;
    private long revision;
    private CorePositionMode positionMode;
    private final boolean laneOwned;
    private final boolean commandSlot;

    public UserRuntime(long userId) {
        this(ProductLine.LINEAR_PERPETUAL, userId, 0, CorePositionMode.ONE_WAY);
    }

    public UserRuntime(ProductLine productLine, long userId, long revision, CorePositionMode positionMode) {
        this(productLine, userId, revision, positionMode, false, false);
    }

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

    private static void validate(ProductLine productLine, long userId, long revision, CorePositionMode positionMode) {
        if (productLine == null || userId <= 0 || revision < 0 || positionMode == null)
            throw new IllegalArgumentException("invalid runtime user");
    }

    public ProductLine productLine() { return productLine; }
    public long userId() { return userId; }
    public long revision() { return revision; }
    public CorePositionMode positionMode() { return positionMode; }

    /** Called only by the owning Lane; an imported/owned value becomes its authority once. */
    public UserRuntime laneValue() {
        return laneOwned ? this : new UserRuntime(productLine, userId, revision, positionMode, true, false);
    }

    /** Validate before mutation; ordinary owned values retain their original value semantics. */
    public UserRuntime withState(long revision, CorePositionMode positionMode) {
        validate(productLine, userId, revision, positionMode);
        if (commandSlot) throw new IllegalStateException("a user receipt cannot become account authority");
        if (!laneOwned) return new UserRuntime(productLine, userId, revision, positionMode);
        this.revision = revision;
        this.positionMode = positionMode;
        return this;
    }

    public UserRuntime publicationValue() {
        return !laneOwned && !commandSlot ? this : new UserRuntime(productLine, userId, revision, positionMode);
    }

    /** The physical command buffer owns this cell until its consumer finishes. */
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

    public void clearCommandSlot() {
        if (!commandSlot) throw new IllegalStateException("only a user command cell may be cleared");
        productLine = null;
        userId = revision = 0;
        positionMode = null;
    }

    @Override public boolean equals(Object other) {
        return other instanceof UserRuntime user && productLine == user.productLine && userId == user.userId
                && revision == user.revision && positionMode == user.positionMode;
    }

    @Override public int hashCode() {
        int hash = productLine == null ? 0 : productLine.hashCode();
        hash = 31 * hash + Long.hashCode(userId);
        hash = 31 * hash + Long.hashCode(revision);
        return 31 * hash + (positionMode == null ? 0 : positionMode.hashCode());
    }

    @Override public String toString() {
        return "UserRuntime[productLine=" + productLine + ", userId=" + userId + ", revision=" + revision
                + ", positionMode=" + positionMode + "]";
    }
}
