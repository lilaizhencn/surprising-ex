package com.surprising.aeron.service.matcher;

/**
 * Dedicated thread carrier for matcher pipeline workers.
 * Holds the owning matcher shard id to allow instant O(1) lookup in matching adapters.
 */
public final class MatcherThread extends Thread {
    private final int shardId;

    public MatcherThread(Runnable target, String name, int shardId) {
        super(null, target, name, 0);
        this.shardId = shardId;
        setDaemon(true);
    }

    public int shardId() {
        return shardId;
    }
}
