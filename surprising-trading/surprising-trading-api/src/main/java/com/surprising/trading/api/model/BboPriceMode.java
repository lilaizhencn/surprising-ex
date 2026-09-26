package com.surprising.trading.api.model;

/** One-time server-side limit price selection; never a continuously pegged order. */
public enum BboPriceMode {
    OPPONENT_1(false, 1), OPPONENT_5(false, 5), SAME_SIDE_1(true, 1), SAME_SIDE_5(true, 5);

    private final boolean sameSide;
    private final int depth;

    BboPriceMode(boolean sameSide, int depth) {
        this.sameSide = sameSide;
        this.depth = depth;
    }

    public boolean sameSide() { return sameSide; }
    public int depth() { return depth; }
}
