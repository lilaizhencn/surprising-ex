package com.surprising.websocket.provider.model;

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
    public static final int DEFAULT_DEPTH = 50;

    public record Level(long priceTicks, long quantitySteps, long orderCount) {}

    public static DepthUpdate between(CoreOrderBookView previous, CoreOrderBookView current) {
        return between(previous, current, DEFAULT_DEPTH);
    }

    public static DepthUpdate between(CoreOrderBookView previous, CoreOrderBookView current, int maxDepth) {
        int limit = Math.max(1, Math.min(maxDepth, DEFAULT_DEPTH));
        List<CoreBookLevelView> prevBids = topLevels(previous, CoreOrderSide.BUY, limit);
        List<CoreBookLevelView> prevAsks = topLevels(previous, CoreOrderSide.SELL, limit);
        List<CoreBookLevelView> currBids = topLevels(current, CoreOrderSide.BUY, limit);
        List<CoreBookLevelView> currAsks = topLevels(current, CoreOrderSide.SELL, limit);

        List<Level> bids = diffSide(prevBids, currBids, true);
        List<Level> asks = diffSide(prevAsks, currAsks, false);
        return new DepthUpdate(previous == null ? "SNAPSHOT" : "DELTA",
                Long.toString(current.exportSequence()),
                previous == null ? null : Long.toString(previous.exportSequence()),
                limit, List.copyOf(bids), List.copyOf(asks));
    }

    private static List<CoreBookLevelView> topLevels(CoreOrderBookView view, CoreOrderSide side, int limit) {
        if (view == null || view.levels().isEmpty()) {
            return List.of();
        }
        List<CoreBookLevelView> sideLevels = new ArrayList<>();
        for (CoreBookLevelView level : view.levels()) {
            if (level.side() == side) {
                sideLevels.add(level);
            }
        }
        if (sideLevels.isEmpty()) {
            return List.of();
        }
        sideLevels.sort(side == CoreOrderSide.BUY
                ? Comparator.comparingLong(CoreBookLevelView::priceTicks).reversed()
                : Comparator.comparingLong(CoreBookLevelView::priceTicks));
        return sideLevels.size() <= limit ? sideLevels : sideLevels.subList(0, limit);
    }

    private static List<Level> diffSide(List<CoreBookLevelView> previous, List<CoreBookLevelView> current, boolean descending) {
        if (previous.isEmpty()) {
            if (current.isEmpty()) return List.of();
            List<Level> added = new ArrayList<>(current.size());
            for (CoreBookLevelView level : current) {
                added.add(new Level(level.priceTicks(), level.quantitySteps(), level.orderCount()));
            }
            return added;
        }
        Map<Long, CoreBookLevelView> remaining = new HashMap<>(previous.size() * 2);
        for (CoreBookLevelView level : previous) {
            remaining.put(level.priceTicks(), level);
        }
        List<Level> changes = new ArrayList<>();
        for (CoreBookLevelView level : current) {
            CoreBookLevelView old = remaining.remove(level.priceTicks());
            if (!level.equals(old)) {
                changes.add(new Level(level.priceTicks(), level.quantitySteps(), level.orderCount()));
            }
        }
        for (CoreBookLevelView removed : remaining.values()) {
            changes.add(new Level(removed.priceTicks(), 0, 0));
        }
        if (changes.size() > 1) {
            changes.sort(descending
                    ? Comparator.comparingLong(Level::priceTicks).reversed()
                    : Comparator.comparingLong(Level::priceTicks));
        }
        return changes;
    }
}
