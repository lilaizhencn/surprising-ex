package com.surprising.aeron.service.matching;

import exchange.core2.core.common.MatcherResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;

public final class CoreMatchingResult implements MatchingResult {

    private static final MatcherResult.MarketData EMPTY_MARKET_DATA =
            new MatcherResult.MarketData(List.of(), List.of(), 0, 0);
    private final boolean accepted;
    private final String resultCode;
    private final List<CoreCancellationResult> cancellations;
    private final int successfulPrefixCount;
    private final boolean matcherStateChanged;
    private final Outcome outcome;
    /*
     * A result is created on the Matcher worker and is not published until its evidence is
     * bound.  Keep these two fields mutable during that construction window so binding does not
     * allocate a second CoreMatchingResult for every command.
     */
    private long nativeCoreSequence;
    private long nativeCommandIdMostSignificantBits;
    private long nativeCommandIdLeastSignificantBits;
    private long nativeOrderId;
    private long nativeSequenceValue;
    private long nativeMatcherSequence;
    private long nativeAeronTimestamp;
    private int nativeMatcherShardId;
    private final MatcherResult nativeMatcherResult;
    private final List<MatcherResult.MatcherEvent> matcherEvents;
    private final MatcherResult.MarketData marketData;

    public CoreMatchingResult(boolean accepted, String resultCode) {
        this(accepted, resultCode, List.of(), 0, false);
    }

    public CoreMatchingResult(boolean accepted, String resultCode,
                              List<CoreCancellationResult> cancellations, int successfulPrefixCount) {
        this(accepted, resultCode, cancellations, successfulPrefixCount, false);
    }

    public CoreMatchingResult(boolean accepted, String resultCode,
                              List<CoreCancellationResult> cancellations, int successfulPrefixCount,
                              boolean matcherStateChanged) {
        this(accepted, resultCode, cancellations, successfulPrefixCount, matcherStateChanged,
                0, 0, 0, 0, 0, 0, 0, -1, null,
                List.of(), EMPTY_MARKET_DATA);
    }

    public CoreMatchingResult(boolean accepted, String resultCode,
                              List<CoreCancellationResult> cancellations, int successfulPrefixCount,
                              boolean matcherStateChanged,
                              long nativeCoreSequence,
                              long nativeCommandIdMostSignificantBits,
                              long nativeCommandIdLeastSignificantBits,
                              long nativeOrderId,
                              long nativeSequence,
                              long nativeMatcherSequence,
                              long nativeAeronTimestamp,
                              int nativeMatcherShardId,
                              MatcherResult nativeMatcherResult,
                              List<MatcherResult.MatcherEvent> matcherEvents,
                              MatcherResult.MarketData marketData) {
        if (resultCode == null || resultCode.isBlank() || cancellations == null
                || successfulPrefixCount < 0 || successfulPrefixCount > cancellations.size()
                || nativeCoreSequence < 0 || nativeOrderId < 0 || nativeSequence < 0
                || nativeMatcherSequence < 0 || nativeAeronTimestamp < 0 || nativeMatcherShardId < -1
                || matcherEvents == null || marketData == null) {
            throw new IllegalArgumentException("invalid matching result");
        }
        this.accepted = accepted;
        this.resultCode = resultCode;
        this.cancellations = immutable(cancellations);
        this.successfulPrefixCount = successfulPrefixCount;
        this.matcherStateChanged = matcherStateChanged;
        this.outcome = classify(accepted, resultCode, matcherStateChanged);
        this.nativeCoreSequence = nativeCoreSequence;
        this.nativeCommandIdMostSignificantBits = nativeCommandIdMostSignificantBits;
        this.nativeCommandIdLeastSignificantBits = nativeCommandIdLeastSignificantBits;
        this.nativeOrderId = nativeOrderId;
        this.nativeSequenceValue = nativeSequence;
        this.nativeMatcherSequence = nativeMatcherSequence;
        this.nativeAeronTimestamp = nativeAeronTimestamp;
        this.nativeMatcherShardId = nativeMatcherShardId;
        this.nativeMatcherResult = nativeMatcherResult;
        this.matcherEvents = matcherEvents;
        this.marketData = marketData;
    }

    static CoreMatchingResult fromNative(MatcherResult result) {
        return new CoreMatchingResult(result, 0, 0, 0, 0, 0, 0, 0, -1);
    }

    static CoreMatchingResult fromNativeWithEvidence(
            MatcherResult result, long coreSequence, long commandIdMostSignificantBits,
            long commandIdLeastSignificantBits, long orderId,
            long nativeSequence, long matcherSequence, long aeronTimestamp, int matcherShardId) {
        return new CoreMatchingResult(result, coreSequence, commandIdMostSignificantBits,
                commandIdLeastSignificantBits, orderId, nativeSequence,
                matcherSequence, aeronTimestamp, matcherShardId);
    }

    private CoreMatchingResult(MatcherResult result, long coreSequence,
                                long commandIdMostSignificantBits, long commandIdLeastSignificantBits,
                                long orderId, long nativeSequence,
                                long matcherSequence, long aeronTimestamp, int matcherShardId) {
        nativeMatcherResult = Objects.requireNonNull(result, "matcher result");
        accepted = result.resultCode() == CommandResultCode.SUCCESS
                || result.resultCode() == CommandResultCode.ACCEPTED;
        resultCode = result.resultCode().name();
        cancellations = List.of();
        successfulPrefixCount = 0;
        matcherStateChanged = false;
        outcome = classify(accepted, resultCode, false);
        if (coreSequence < 0 || orderId < 0 || nativeSequence < 0
                || matcherSequence < 0 || aeronTimestamp < 0 || matcherShardId < -1) {
            throw new IllegalArgumentException("invalid native command identity");
        }
        nativeCoreSequence = coreSequence;
        nativeCommandIdMostSignificantBits = commandIdMostSignificantBits;
        nativeCommandIdLeastSignificantBits = commandIdLeastSignificantBits;
        nativeOrderId = orderId;
        nativeSequenceValue = nativeSequence;
        nativeMatcherSequence = matcherSequence;
        nativeAeronTimestamp = aeronTimestamp;
        nativeMatcherShardId = matcherShardId;
        matcherEvents = Objects.requireNonNull(result.events(), "matcher events");
        marketData = Objects.requireNonNull(result.marketData(), "matcher market data");
    }

    /** Matcher-only binding path; called before publication into the completion ring. */
    /** Matcher-only binding path that keeps native command identity in primitive fields. */
    CoreMatchingResult bindEvidenceInPlace(
            long coreSequence, long commandIdMostSignificantBits, long commandIdLeastSignificantBits,
            long orderId, long nativeSequence, long matcherSequence,
            long aeronTimestamp, int matcherShardId) {
        nativeCoreSequence = coreSequence;
        nativeCommandIdMostSignificantBits = commandIdMostSignificantBits;
        nativeCommandIdLeastSignificantBits = commandIdLeastSignificantBits;
        nativeOrderId = orderId;
        nativeSequenceValue = nativeSequence;
        nativeMatcherSequence = matcherSequence;
        nativeAeronTimestamp = aeronTimestamp;
        nativeMatcherShardId = matcherShardId;
        return this;
    }

    /** Matcher worker variant that avoids a second result object before publication. */
    public CoreMatchingResult withCoreSequenceInPlace(long coreSequence) {
        if (coreSequence <= 0) throw new IllegalArgumentException("coreSequence must be positive");
        if (nativeCoreSequence == coreSequence) return this;
        if (nativeCoreSequence != 0) throw new IllegalStateException("matching result sequence mismatch");
        nativeCoreSequence = coreSequence;
        return this;
    }

    static List<MatcherResult.MatcherEvent> concatenateEvents(
            List<MatcherResult.MatcherEvent> first,
            List<MatcherResult.MatcherEvent> second) {
        if (first.isEmpty()) return second;
        if (second.isEmpty()) return first;
        ArrayList<List<MatcherResult.MatcherEvent>> segments = new ArrayList<>(4);
        appendSegments(segments, first);
        appendSegments(segments, second);
        return new SegmentedEvents(segments);
    }

    static List<MatcherResult.MatcherEvent> concatenateEvents(
            Iterable<CoreMatchingResult> results) {
        ArrayList<List<MatcherResult.MatcherEvent>> segments = new ArrayList<>();
        for (CoreMatchingResult result : results) {
            appendSegments(segments, result.matcherEvents());
        }
        if (segments.isEmpty()) return List.of();
        if (segments.size() == 1) return segments.getFirst();
        return new SegmentedEvents(segments);
    }

    private static void appendSegments(
            List<List<MatcherResult.MatcherEvent>> destination,
            List<MatcherResult.MatcherEvent> source) {
        if (source.isEmpty()) return;
        if (source instanceof SegmentedEvents segmented) {
            destination.addAll(segmented.segments);
        } else {
            destination.add(source);
        }
    }

    public boolean accepted() { return accepted; }
    public String resultCode() { return resultCode; }
    public List<CoreCancellationResult> cancellations() { return cancellations; }
    public int successfulPrefixCount() { return successfulPrefixCount; }
    public boolean matcherStateChanged() { return matcherStateChanged; }
    public Outcome outcome() { return outcome; }
    /** 原生结果绑定证据前只需序号，直接读取而不物化占位身份。 */
    long nativeSequence() {
        return nativeSequenceValue == 0 && nativeMatcherResult != null
                ? nativeMatcherResult.sequence() : nativeSequenceValue;
    }
    public long nativeCoreSequence() { return nativeCoreSequence; }
    long nativeCommandIdMostSignificantBits() { return nativeCommandIdMostSignificantBits; }
    long nativeCommandIdLeastSignificantBits() { return nativeCommandIdLeastSignificantBits; }
    public long nativeOrderId() { return nativeOrderId; }
    public long nativeMatcherSequence() { return nativeMatcherSequence; }
    long nativeAeronTimestamp() { return nativeAeronTimestamp; }
    public int nativeMatcherShardId() { return nativeMatcherShardId; }
    public boolean nativeMatches(java.util.UUID commandId) {
        return commandId != null
                && nativeCommandIdMostSignificantBits == commandId.getMostSignificantBits()
                && nativeCommandIdLeastSignificantBits == commandId.getLeastSignificantBits();
    }
    public MatcherResult nativeMatcherResult() { return nativeMatcherResult; }
    public List<MatcherResult.MatcherEvent> matcherEvents() { return matcherEvents; }
    public MatcherResult.MarketData marketData() { return marketData; }

    private static Outcome classify(boolean accepted, String resultCode, boolean matcherStateChanged) {
        if ("EXCHANGE_CORE_FAILURE".equals(resultCode) || "MATCHING_TIMEOUT".equals(resultCode)) {
            return Outcome.FATAL_DIVERGENCE;
        }
        if (accepted) return Outcome.APPLIED;
        return matcherStateChanged ? Outcome.KNOWN_PREFIX_APPLIED : Outcome.REJECTED_UNCHANGED;
    }

    /** Keep the JDK immutable lists already produced by the matcher; copy external mutable lists. */
    private static <T> List<T> immutable(List<T> values) {
        if (values.isEmpty() || values.getClass().getName().startsWith("java.util.ImmutableCollections$")) {
            return values;
        }
        return List.copyOf(values);
    }

    private static final class SegmentedEvents extends AbstractList<MatcherResult.MatcherEvent>
            implements RandomAccess {
        private final List<List<MatcherResult.MatcherEvent>> segments;
        private final int[] ends;
        private final int size;

        private SegmentedEvents(List<List<MatcherResult.MatcherEvent>> segments) {
            this.segments = List.copyOf(segments);
            this.ends = new int[segments.size()];
            int total = 0;
            for (int index = 0; index < segments.size(); index++) {
                total = Math.addExact(total, segments.get(index).size());
                ends[index] = total;
            }
            this.size = total;
        }

        @Override
        public MatcherResult.MatcherEvent get(int index) {
            Objects.checkIndex(index, size);
            int low = 0;
            int high = ends.length - 1;
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (index < ends[middle]) high = middle;
                else low = middle + 1;
            }
            int offset = low == 0 ? 0 : ends[low - 1];
            return segments.get(low).get(index - offset);
        }

        @Override
        public int size() { return size; }
    }
}
