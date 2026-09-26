package com.surprising.websocket.provider.service;

import com.surprising.aeron.protocol.CoreBookLevelView;
import com.surprising.aeron.protocol.CoreOrderBookView;
import com.surprising.aeron.protocol.CoreOrderSide;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** WebSocket depth protocol: absolute quantities at changed prices, zero removes a level. */
public record DepthUpdate(String updateType, String sequence, String previousSequence,
                          int depth, List<Level> bids, List<Level> asks) {
    public record Level(long priceTicks, long quantitySteps, long orderCount) {}
    private record PriceLevel(CoreOrderSide side, long priceTicks) {}

    public static DepthUpdate between(CoreOrderBookView previous, CoreOrderBookView current) {
        Map<PriceLevel, CoreBookLevelView> remaining = new HashMap<>();
        if (previous != null) {
            for (CoreBookLevelView level : previous.levels()) {
                remaining.put(new PriceLevel(level.side(), level.priceTicks()), level);
            }
        }
        List<Level> bids = new ArrayList<>();
        List<Level> asks = new ArrayList<>();
        for (CoreBookLevelView level : current.levels()) {
            CoreBookLevelView old = remaining.remove(new PriceLevel(level.side(), level.priceTicks()));
            if (!level.equals(old)) {
                (level.side() == CoreOrderSide.BUY ? bids : asks)
                        .add(new Level(level.priceTicks(), level.quantitySteps(), level.orderCount()));
            }
        }
        for (CoreBookLevelView removed : remaining.values()) {
            (removed.side() == CoreOrderSide.BUY ? bids : asks)
                    .add(new Level(removed.priceTicks(), 0, 0));
        }
        bids.sort(Comparator.comparingLong(Level::priceTicks).reversed());
        asks.sort(Comparator.comparingLong(Level::priceTicks));
        return new DepthUpdate(previous == null ? "SNAPSHOT" : "DELTA",
                Long.toString(current.exportSequence()),
                previous == null ? null : Long.toString(previous.exportSequence()),
                50, List.copyOf(bids), List.copyOf(asks));
    }
}
