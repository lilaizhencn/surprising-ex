package com.surprising.aeron.service.matching;

import exchange.core2.core.common.MatcherResult;

final class MatcherPrefixDigest {

    private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;
    private static final long DOMAIN = 0x4d41544348455232L;

    private MatcherPrefixDigest() {
    }

    static long initial() {
        return OFFSET_BASIS;
    }

    static long next(long previous, CoreMatchingResult.NativeCommand command, CoreMatchingResult result) {
        if (previous == 0 || command == null || result == null) {
            throw new IllegalArgumentException("invalid matcher prefix input");
        }
        long hash = mix(previous, DOMAIN);
        hash = mix(hash, command.coreSequence());
        hash = mix(hash, command.commandIdMostSignificantBits());
        hash = mix(hash, command.commandIdLeastSignificantBits());
        hash = mix(hash, command.orderId());
        hash = mix(hash, command.instrumentVersion());
        hash = mix(hash, command.matcherSequence());
        hash = mix(hash, command.aeronTimestamp());
        hash = mix(hash, result.accepted());
        hash = mix(hash, result.resultCode());
        hash = mix(hash, result.successfulPrefixCount());
        hash = mix(hash, result.matcherStateChanged());
        hash = mix(hash, result.cancellations().size());
        for (CoreCancellationResult cancellation : result.cancellations()) {
            hash = mix(hash, cancellation.orderId());
            hash = mix(hash, cancellation.accepted());
            hash = mix(hash, cancellation.resultCode());
        }
        hash = mix(hash, result.matcherEvents().size());
        for (MatcherResult.MatcherEvent event : result.matcherEvents()) {
            hash = mix(hash, event.eventType().name());
            hash = mix(hash, event.section());
            hash = mix(hash, event.activeOrderCompleted());
            hash = mix(hash, event.matchedOrderId());
            hash = mix(hash, event.matchedOrderUid());
            hash = mix(hash, event.matchedOrderCompleted());
            hash = mix(hash, event.price());
            hash = mix(hash, event.size());
            hash = mix(hash, event.bidderHoldPrice());
        }
        return hash == 0 ? mix(hash, DOMAIN) : hash;
    }

    private static long mix(long hash, boolean value) {
        return mix(hash, value ? 1 : 0);
    }

    private static long mix(long hash, String value) {
        long mixed = mix(hash, value.length());
        for (int index = 0; index < value.length(); index++) {
            mixed = mix(mixed, value.charAt(index));
        }
        return mixed;
    }

    private static long mix(long hash, long value) {
        long mixed = hash;
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            mixed ^= value >>> shift & 0xff;
            mixed *= PRIME;
        }
        return mixed;
    }
}
