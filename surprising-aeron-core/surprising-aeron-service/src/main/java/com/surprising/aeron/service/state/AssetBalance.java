package com.surprising.aeron.service.state;

import java.util.Locale;

public record AssetBalance(String asset, long availableUnits, long lockedUnits) {

    public AssetBalance {
        asset = normalizeAsset(asset);
        if (availableUnits < 0 || lockedUnits < 0) {
            throw new IllegalArgumentException("balance units must not be negative");
        }
    }

    public long totalUnits() {
        return Math.addExact(availableUnits, lockedUnits);
    }

    public AssetBalance adjustAvailable(long delta) {
        long nextAvailable = Math.addExact(availableUnits, delta);
        if (nextAvailable < 0) {
            throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE", "available balance is insufficient");
        }
        return new AssetBalance(asset, nextAvailable, lockedUnits);
    }

    public AssetBalance reserve(long units) {
        requirePositive(units);
        if (availableUnits < units) {
            throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE", "available balance is insufficient");
        }
        return new AssetBalance(asset, Math.subtractExact(availableUnits, units), Math.addExact(lockedUnits, units));
    }

    public AssetBalance release(long units) {
        requirePositive(units);
        if (lockedUnits < units) {
            throw new IllegalStateException("locked balance is lower than reservation release");
        }
        return new AssetBalance(asset, Math.addExact(availableUnits, units), Math.subtractExact(lockedUnits, units));
    }

    public AssetBalance consumeLocked(long units) {
        requirePositive(units);
        if (lockedUnits < units) {
            throw new CoreStateRejectedException("INSUFFICIENT_LOCKED_BALANCE", "locked balance is insufficient");
        }
        return new AssetBalance(asset, availableUnits, Math.subtractExact(lockedUnits, units));
    }

    public AssetBalance credit(long units) {
        requirePositive(units);
        return new AssetBalance(asset, Math.addExact(availableUnits, units), lockedUnits);
    }

    static String normalizeAsset(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("asset is required");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9]{2,20}")) {
            throw new IllegalArgumentException("invalid asset: " + value);
        }
        return normalized;
    }

    private static void requirePositive(long units) {
        if (units <= 0) {
            throw new IllegalArgumentException("units must be positive");
        }
    }
}
