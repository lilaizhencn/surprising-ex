package com.surprising.aeron.service.state;

import java.util.Map;
import java.util.TreeMap;

public record LaneTopology(
        int routeVersion,
        int matchingEngineCount,
        int riskEngineCount,
        int matcherShardMask,
        int accountLaneCount,
        long accountLaneSeed,
        int matcherWindowSize,
        int matchingCompletionCapacity,
        int accountLaneQueueCapacity) {

    public static final int ROUTE_VERSION = 3;
    public static final int DEFAULT_MATCHING_ENGINE_COUNT = 1;
    public static final int DEFAULT_RISK_ENGINE_COUNT = 0;
    public static final int DEFAULT_ACCOUNT_LANE_COUNT = 4;
    public static final long DEFAULT_ACCOUNT_LANE_SEED = 0x6a09e667f3bcc909L;
    public static final int DEFAULT_MATCHER_WINDOW_SIZE = 4_096;
    public static final int DEFAULT_QUEUE_CAPACITY = 4_096;

    public LaneTopology {
        requirePowerOfTwo(matchingEngineCount, 1, 64, "matchingEngineCount");
        requirePowerOfTwo(riskEngineCount, 0, 64, "riskEngineCount");
        requirePowerOfTwo(accountLaneCount, 1, Long.SIZE, "accountLaneCount");
        requirePowerOfTwo(matcherWindowSize, 1, 1 << 20, "matcherWindowSize");
        requirePowerOfTwo(matchingCompletionCapacity, 1, 1 << 20, "matchingCompletionCapacity");
        requirePowerOfTwo(accountLaneQueueCapacity, 1, 1 << 20, "accountLaneQueueCapacity");
        if (routeVersion != ROUTE_VERSION || matcherShardMask != matchingEngineCount - 1) {
            throw new IllegalArgumentException("invalid deterministic lane topology");
        }
    }

    public static LaneTopology productionDefault() {
        return configured(false);
    }

    public static LaneTopology characterization() {
        return new LaneTopology(ROUTE_VERSION, 1, 0, 0, 1, DEFAULT_ACCOUNT_LANE_SEED,
                DEFAULT_MATCHER_WINDOW_SIZE, DEFAULT_QUEUE_CAPACITY, DEFAULT_QUEUE_CAPACITY);
    }

    public static LaneTopology configured(boolean allowCharacterization) {
        int matching = Integer.getInteger("surprising.aeron.matching-engines", DEFAULT_MATCHING_ENGINE_COUNT);
        int risk = Integer.getInteger("surprising.aeron.risk-engines", DEFAULT_RISK_ENGINE_COUNT);
        int accounts = Integer.getInteger("surprising.aeron.account-lanes", DEFAULT_ACCOUNT_LANE_COUNT);
        long seed = Long.getLong("surprising.aeron.account-lane-seed", DEFAULT_ACCOUNT_LANE_SEED);
        int window = Integer.getInteger("surprising.aeron.matcher-window-size", DEFAULT_MATCHER_WINDOW_SIZE);
        int completion = Integer.getInteger("surprising.aeron.matching-completion-capacity", DEFAULT_QUEUE_CAPACITY);
        int laneQueue = Integer.getInteger("surprising.aeron.account-lane-queue-capacity", DEFAULT_QUEUE_CAPACITY);
        return new LaneTopology(ROUTE_VERSION, matching, risk, matching - 1, accounts, seed,
                window, completion, laneQueue);
    }

    public int matcherShardId(int stableSymbolId) {
        if (stableSymbolId <= 0) throw new IllegalArgumentException("stableSymbolId must be positive");
        return stableSymbolId & matcherShardMask;
    }

    public int accountLaneId(long userId) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        return (int) (mix64(userId ^ accountLaneSeed) & (accountLaneCount - 1L));
    }

    public long accountLaneMask(long userId) {
        return 1L << accountLaneId(userId);
    }

    public long topologyHash() {
        long hash = offset();
        hash = mix(hash, routeVersion);
        hash = mix(hash, matchingEngineCount);
        hash = mix(hash, riskEngineCount);
        hash = mix(hash, matcherShardMask);
        hash = mix(hash, accountLaneCount);
        hash = mix(hash, accountLaneSeed);
        hash = mix(hash, matcherWindowSize);
        hash = mix(hash, matchingCompletionCapacity);
        return mix(hash, accountLaneQueueCapacity);
    }

    public long symbolRouteHash(Map<String, Integer> symbols) {
        long hash = mix(offset(), topologyHash());
        for (Map.Entry<String, Integer> entry : new TreeMap<>(symbols).entrySet()) {
            hash = mix(hash, entry.getKey());
            hash = mix(hash, entry.getValue());
            hash = mix(hash, matcherShardId(entry.getValue()));
        }
        return hash;
    }

    private static void requirePowerOfTwo(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum || (value & (value - 1)) != 0) {
            throw new IllegalArgumentException(name + " must be a power of two in [" + minimum + ',' + maximum + ']');
        }
    }

    private static long mix64(long value) {
        long mixed = value;
        mixed = (mixed ^ (mixed >>> 30)) * 0xbf58476d1ce4e5b9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL;
        return mixed ^ (mixed >>> 31);
    }

    private static long offset() {
        return 0xcbf29ce484222325L;
    }

    private static long mix(long hash, long value) {
        long mixed = hash;
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            mixed ^= (value >>> shift) & 0xff;
            mixed *= 0x100000001b3L;
        }
        return mixed;
    }

    private static long mix(long hash, String value) {
        long mixed = hash;
        for (int index = 0; index < value.length(); index++) {
            mixed ^= value.charAt(index);
            mixed *= 0x100000001b3L;
        }
        return mixed;
    }
}
