package com.surprising.aeron.service.lane;

import com.surprising.aeron.service.state.AccountLaneState;

import java.util.Objects;

/**
 * Dedicated thread type for Account Lane workers to enable O(1) scope resolution
 * without linear scanning across worker threads.
 */
public final class LaneThread extends Thread {
    private final AccountLaneState lane;

    public LaneThread(String name, Runnable target, AccountLaneState lane) {
        super(null, target, Objects.requireNonNull(name, "name"), 0, false);
        this.lane = Objects.requireNonNull(lane, "lane");
        setDaemon(true);
    }

    public AccountLaneState lane() {
        return lane;
    }
}
