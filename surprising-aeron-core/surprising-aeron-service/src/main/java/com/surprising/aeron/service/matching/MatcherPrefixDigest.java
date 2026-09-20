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
        return next(previous, command.coreSequence(), command.commandIdMostSignificantBits(),
                command.commandIdLeastSignificantBits(), command.orderId(),
                command.matcherSequence(), command.aeronTimestamp(), result);
    }

    static long next(long previous, long coreSequence, long commandIdMostSignificantBits,
                     long commandIdLeastSignificantBits, long orderId,
                     long matcherSequence, long aeronTimestamp, CoreMatchingResult result) {
        if (previous == 0 || result == null) {
            throw new IllegalArgumentException("invalid matcher prefix input");
        }
        long hash = mix(previous, DOMAIN);
        hash = mix(hash, coreSequence);
        hash = mix(hash, commandIdMostSignificantBits);
        hash = mix(hash, commandIdLeastSignificantBits);
        hash = mix(hash, orderId);
        hash = mix(hash, matcherSequence);
        hash = mix(hash, aeronTimestamp);
        hash = mix(hash, result.accepted());
        hash = mix(hash, result.resultCode());
        hash = mix(hash, result.successfulPrefixCount());
        hash = mix(hash, result.matcherStateChanged());
        hash = mix(hash, result.cancellations().size());
        var cancellations = result.cancellations();
        for (int index = 0; index < cancellations.size(); index++) {
            CoreCancellationResult cancellation = cancellations.get(index);
            hash = mix(hash, cancellation.orderId());
            hash = mix(hash, cancellation.accepted());
            hash = mix(hash, cancellation.resultCode());
        }
        hash = mix(hash, result.matcherEvents().size());
        var matcherEvents = result.matcherEvents();
        for (int index = 0; index < matcherEvents.size(); index++) {
            MatcherResult.MatcherEvent event = matcherEvents.get(index);
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
