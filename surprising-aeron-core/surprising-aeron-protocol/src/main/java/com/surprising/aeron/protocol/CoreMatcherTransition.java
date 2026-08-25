package com.surprising.aeron.protocol;

public record CoreMatcherTransition(
        long sequenceBefore,
        long sequenceAfter,
        long prefixBefore,
        long prefixAfter) {

    public CoreMatcherTransition {
        if (sequenceBefore < 0 || sequenceAfter < sequenceBefore
                || (sequenceAfter == sequenceBefore && prefixAfter != prefixBefore)
                || (sequenceAfter > sequenceBefore && (prefixBefore == 0 || prefixAfter == 0))) {
            throw new IllegalArgumentException("invalid matcher transition");
        }
    }

    public static CoreMatcherTransition unchanged(long sequence, long prefix) {
        return new CoreMatcherTransition(sequence, sequence, prefix, prefix);
    }
}
