package com.surprising.aeron.protocol;

public record CoreMatcherTransition(
        int routeVersion,
        int matcherShardId,
        long sequenceBefore,
        long sequenceAfter,
        long prefixBefore,
        long prefixAfter) {

    public CoreMatcherTransition {
        if (routeVersion <= 0 || matcherShardId < -1 || sequenceBefore < 0 || sequenceAfter < sequenceBefore
                || (sequenceAfter == sequenceBefore && prefixAfter != prefixBefore)
                || (sequenceAfter > sequenceBefore && (prefixBefore == 0 || prefixAfter == 0))) {
            throw new IllegalArgumentException("invalid matcher transition");
        }
    }

    public CoreMatcherTransition(long sequenceBefore, long sequenceAfter, long prefixBefore, long prefixAfter) {
        this(CoreRoute.DEFAULT.version(), -1, sequenceBefore, sequenceAfter, prefixBefore, prefixAfter);
    }

    public static CoreMatcherTransition unchanged(long sequence, long prefix) {
        return new CoreMatcherTransition(CoreRoute.DEFAULT.version(), -1, sequence, sequence, prefix, prefix);
    }
}
