package com.surprising.aeron.service.matching;

public record MatcherShardProgress(int matcherShardId, long matcherSequence, long prefixDigest) {

    public MatcherShardProgress {
        if (matcherShardId < -1 || matcherSequence < 0 || prefixDigest == 0) {
            throw new IllegalArgumentException("invalid matcher shard progress");
        }
    }
}
