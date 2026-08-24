package com.surprising.aeron.service.state;

public final class BalanceRuntime {

    private final long userId;
    private final int assetId;
    private long availableUnits;
    private long lockedUnits;
    private Thread owner;

    public BalanceRuntime(long userId, int assetId, long availableUnits, long lockedUnits) {
        if (userId <= 0 || assetId < 0 || availableUnits < 0 || lockedUnits < 0) {
            throw new IllegalArgumentException("invalid runtime balance");
        }
        this.userId = userId;
        this.assetId = assetId;
        this.availableUnits = availableUnits;
        this.lockedUnits = lockedUnits;
    }

    void bindOwner() {
        Thread current = Thread.currentThread();
        if (owner == null) owner = current;
        else if (owner != current) throw new IllegalStateException("balance runtime is bound to another thread");
    }

    void releaseOwnerForHandoff() {
        owner = null;
    }

    public long userId() { bindOwner(); return userId; }
    public int assetId() { bindOwner(); return assetId; }
    public long availableUnits() { bindOwner(); return availableUnits; }
    public long lockedUnits() { bindOwner(); return lockedUnits; }

    public void replace(long nextAvailableUnits, long nextLockedUnits) {
        bindOwner();
        if (nextAvailableUnits < 0 || nextLockedUnits < 0) {
            throw new IllegalArgumentException("invalid runtime balance");
        }
        availableUnits = nextAvailableUnits;
        lockedUnits = nextLockedUnits;
    }

    public void reserve(long units) {
        bindOwner();
        if (units <= 0 || availableUnits < units) throw new IllegalArgumentException("insufficient runtime balance");
        long nextLockedUnits = Math.addExact(lockedUnits, units);
        long nextAvailableUnits = availableUnits - units;
        availableUnits = nextAvailableUnits;
        lockedUnits = nextLockedUnits;
    }

    public void release(long units) {
        bindOwner();
        if (units < 0 || lockedUnits < units) throw new IllegalArgumentException("invalid runtime release");
        long nextAvailableUnits = Math.addExact(availableUnits, units);
        long nextLockedUnits = lockedUnits - units;
        lockedUnits = nextLockedUnits;
        availableUnits = nextAvailableUnits;
    }

    public void consumeLocked(long units) {
        bindOwner();
        if (units < 0 || lockedUnits < units) throw new IllegalArgumentException("invalid runtime locked consumption");
        lockedUnits -= units;
    }

    public void credit(long units) {
        bindOwner();
        if (units < 0) throw new IllegalArgumentException("invalid runtime credit");
        availableUnits = Math.addExact(availableUnits, units);
    }
}
