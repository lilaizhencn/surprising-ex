package com.surprising.aeron.service.matching;

import java.util.List;

public record CoreMatchingResult(boolean accepted, String resultCode, List<CoreMatch> matches,
                                 List<CoreCancellationResult> cancellations, int successfulPrefixCount,
                                 boolean matcherStateChanged, NativeCommand nativeCommand,
                                 MatcherPrefix matcherPrefix, List<MatcherEvent> matcherEvents,
                                 MarketData marketData) {

    public record NativeCommand(long coreSequence, String commandId, long orderId, long instrumentVersion,
                                long nativeSequence, long matcherSequence, long aeronTimestamp) {

        public NativeCommand {
            if (coreSequence < 0 || commandId == null || orderId < 0 || instrumentVersion < 0
                    || nativeSequence < 0 || matcherSequence < 0 || aeronTimestamp < 0) {
                throw new IllegalArgumentException("invalid native command identity");
            }
        }
    }

    public record MatcherPrefix(long before, long after) {

        public static long initialDigest() {
            return MatcherPrefixDigest.initial();
        }

        public boolean bound() {
            return before != 0 && after != 0;
        }
    }

    public record MatcherEvent(String type, int section, boolean activeOrderCompleted, long matchedOrderId,
                               long matchedOrderUid, boolean matchedOrderCompleted, long price, long size,
                               long bidderHoldPrice) {

        public MatcherEvent {
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("invalid matcher event");
            }
        }
    }

    public record MarketData(List<Level> asks, List<Level> bids) {

        public record Level(long price, long volume, long orders) {
        }

        public MarketData {
            if (asks == null || bids == null) {
                throw new IllegalArgumentException("invalid market data");
            }
            asks = List.copyOf(asks);
            bids = List.copyOf(bids);
        }
    }

    public CoreMatchingResult(boolean accepted, String resultCode, List<CoreMatch> matches,
                              List<CoreCancellationResult> cancellations, int successfulPrefixCount) {
        this(accepted, resultCode, matches, cancellations, successfulPrefixCount, false);
    }

    public CoreMatchingResult(boolean accepted, String resultCode, List<CoreMatch> matches) {
        this(accepted, resultCode, matches, List.of(), 0, false);
    }

    public CoreMatchingResult(boolean accepted, String resultCode, List<CoreMatch> matches,
                              List<CoreCancellationResult> cancellations, int successfulPrefixCount,
                              boolean matcherStateChanged) {
        this(accepted, resultCode, matches, cancellations, successfulPrefixCount, matcherStateChanged,
                new NativeCommand(0, "", 0, 0, 0, 0, 0), new MatcherPrefix(0, 0), List.of(),
                new MarketData(List.of(), List.of()));
    }

    public CoreMatchingResult {
        if (resultCode == null || resultCode.isBlank() || matches == null || cancellations == null
                || successfulPrefixCount < 0 || successfulPrefixCount > cancellations.size()) {
            throw new IllegalArgumentException("invalid matching result");
        }
        matches = List.copyOf(matches);
        cancellations = List.copyOf(cancellations);
        if (nativeCommand == null || matcherPrefix == null || matcherEvents == null || marketData == null) {
            throw new IllegalArgumentException("missing immutable matcher callback data");
        }
        matcherEvents = List.copyOf(matcherEvents);
    }
}
