package com.surprising.aeron.service.state;

public final class BalanceRuntime {

    private final long userId;
    private final int assetId;
    private long availableUnits;
    private long lockedUnits;

    public BalanceRuntime(long userId, int assetId, long availableUnits, long lockedUnits) {
        if (userId <= 0 || assetId < 0 || availableUnits < 0 || lockedUnits < 0) {
            throw new IllegalArgumentException("invalid runtime balance");
        }
        this.userId = userId;
        this.assetId = assetId;
        this.availableUnits = availableUnits;
        this.lockedUnits = lockedUnits;
    }

    public long userId() { return userId; }
    public int assetId() { return assetId; }
    public long availableUnits() { return availableUnits; }
    public long lockedUnits() { return lockedUnits; }

    public void replace(long nextAvailableUnits, long nextLockedUnits) {
        if (nextAvailableUnits < 0 || nextLockedUnits < 0) {
            throw new IllegalArgumentException("invalid runtime balance");
        }
        availableUnits = nextAvailableUnits;
        lockedUnits = nextLockedUnits;
    }

    public void reserve(long units) {
        if (units <= 0 || availableUnits < units) throw new IllegalArgumentException("insufficient runtime balance");
        long nextLockedUnits = Math.addExact(lockedUnits, units);
        long nextAvailableUnits = availableUnits - units;
        availableUnits = nextAvailableUnits;
        lockedUnits = nextLockedUnits;
    }

    public void release(long units) {
        if (units < 0 || lockedUnits < units) throw new IllegalArgumentException("invalid runtime release");
        long nextAvailableUnits = Math.addExact(availableUnits, units);
        long nextLockedUnits = lockedUnits - units;
        lockedUnits = nextLockedUnits;
        availableUnits = nextAvailableUnits;
    }

    public void consumeLocked(long units) {
        if (units < 0 || lockedUnits < units) throw new IllegalArgumentException("invalid runtime locked consumption");
        lockedUnits -= units;
    }

    public void credit(long units) {
        if (units < 0) throw new IllegalArgumentException("invalid runtime credit");
        availableUnits = Math.addExact(availableUnits, units);
    }
}
