package com.surprising.account.api.model;

/** Reserves, finalizes, or releases a fixed slice of one user's bankruptcy deficit. */
public record DeficitReservationAccountCommand(
        String asset,
        long amountUnits) {

    public DeficitReservationAccountCommand {
        if (asset == null || asset.isBlank()) {
            throw new IllegalArgumentException("asset is required");
        }
        asset = asset.trim().toUpperCase();
        if (!asset.matches("[A-Z0-9]{2,20}")) {
            throw new IllegalArgumentException("invalid asset: " + asset);
        }
        if (amountUnits <= 0) {
            throw new IllegalArgumentException("amountUnits must be positive");
        }
    }
}
