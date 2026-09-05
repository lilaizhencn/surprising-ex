package com.surprising.aeron.service.matching;

import exchange.core2.core.common.MatcherResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;

public final class CoreMatchingResult {

    private static final MatcherResult.MarketData EMPTY_MARKET_DATA =
            new MatcherResult.MarketData(List.of(), List.of(), 0, 0);

    private final boolean accepted;
    private final String resultCode;
    private final List<CoreCancellationResult> cancellations;
    private final int successfulPrefixCount;
    private final boolean matcherStateChanged;
    private final Outcome outcome;
    private final NativeCommand nativeCommand;
    private final MatcherPrefix matcherPrefix;
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
                new NativeCommand(0, 0, 0, 0, 0, 0, 0, 0, -1), new MatcherPrefix(0, 0), null,
                List.of(), EMPTY_MARKET_DATA);
    }

    public CoreMatchingResult(boolean accepted, String resultCode,
                              List<CoreCancellationResult> cancellations, int successfulPrefixCount,
                              boolean matcherStateChanged, NativeCommand nativeCommand,
                              MatcherPrefix matcherPrefix, MatcherResult nativeMatcherResult,
                              List<MatcherResult.MatcherEvent> matcherEvents,
                              MatcherResult.MarketData marketData) {
        if (resultCode == null || resultCode.isBlank() || cancellations == null
                || successfulPrefixCount < 0 || successfulPrefixCount > cancellations.size()
                || nativeCommand == null || matcherPrefix == null || matcherEvents == null || marketData == null) {
            throw new IllegalArgumentException("invalid matching result");
        }
        this.accepted = accepted;
        this.resultCode = resultCode;
        this.cancellations = List.copyOf(cancellations);
        this.successfulPrefixCount = successfulPrefixCount;
        this.matcherStateChanged = matcherStateChanged;
        this.outcome = classify(accepted, resultCode, matcherStateChanged);
        this.nativeCommand = nativeCommand;
        this.matcherPrefix = matcherPrefix;
        this.nativeMatcherResult = nativeMatcherResult;
        this.matcherEvents = matcherEvents;
        this.marketData = marketData;
    }

    static CoreMatchingResult fromNative(MatcherResult result) {
        Objects.requireNonNull(result, "matcher result");
        boolean accepted = result.resultCode() == CommandResultCode.SUCCESS
                || result.resultCode() == CommandResultCode.ACCEPTED;
        return new CoreMatchingResult(accepted, result.resultCode().name(), List.of(), 0, false,
                new NativeCommand(0, 0, 0, 0, 0, result.sequence(), 0, 0, -1),
                new MatcherPrefix(0, 0), result, result.events(), result.marketData());
    }

    CoreMatchingResult withEvidence(NativeCommand command, MatcherPrefix prefix) {
        return new CoreMatchingResult(accepted, resultCode, cancellations, successfulPrefixCount,
                matcherStateChanged, command, prefix, nativeMatcherResult, matcherEvents, marketData);
    }

    public CoreMatchingResult withCoreSequence(long coreSequence) {
        if (coreSequence <= 0) throw new IllegalArgumentException("coreSequence must be positive");
        if (nativeCommand.coreSequence() == coreSequence) return this;
        if (nativeCommand.coreSequence() != 0) throw new IllegalStateException("matching result sequence mismatch");
        NativeCommand command = new NativeCommand(coreSequence,
                nativeCommand.commandIdMostSignificantBits(), nativeCommand.commandIdLeastSignificantBits(),
                nativeCommand.orderId(),
                nativeCommand.instrumentVersion(), nativeCommand.nativeSequence(), nativeCommand.matcherSequence(),
                nativeCommand.aeronTimestamp(), nativeCommand.matcherShardId());
        return new CoreMatchingResult(accepted, resultCode, cancellations, successfulPrefixCount,
                matcherStateChanged, command, matcherPrefix, nativeMatcherResult, matcherEvents, marketData);
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
    public NativeCommand nativeCommand() { return nativeCommand; }
    public MatcherPrefix matcherPrefix() { return matcherPrefix; }
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

    public enum Outcome {
        REJECTED_UNCHANGED,
        KNOWN_PREFIX_APPLIED,
        APPLIED,
        FATAL_DIVERGENCE
    }

    public record NativeCommand(long coreSequence,
                                long commandIdMostSignificantBits,
                                long commandIdLeastSignificantBits,
                                long orderId, long instrumentVersion,
                                long nativeSequence, long matcherSequence, long aeronTimestamp,
                                int matcherShardId) {
        public NativeCommand {
            if (coreSequence < 0 || orderId < 0 || instrumentVersion < 0
                    || nativeSequence < 0 || matcherSequence < 0 || aeronTimestamp < 0 || matcherShardId < -1) {
                throw new IllegalArgumentException("invalid native command identity");
            }
        }

        public NativeCommand(long coreSequence, java.util.UUID commandId, long orderId, long instrumentVersion,
                             long nativeSequence, long matcherSequence, long aeronTimestamp) {
            this(coreSequence, commandId == null ? 0 : commandId.getMostSignificantBits(),
                    commandId == null ? 0 : commandId.getLeastSignificantBits(), orderId, instrumentVersion,
                    nativeSequence, matcherSequence,
                    aeronTimestamp, -1);
        }

        public boolean matches(java.util.UUID commandId) {
            return commandId != null
                    && commandIdMostSignificantBits == commandId.getMostSignificantBits()
                    && commandIdLeastSignificantBits == commandId.getLeastSignificantBits();
        }
    }

    public record MatcherPrefix(long before, long after) {
        public static long initialDigest() { return MatcherPrefixDigest.initial(); }
        public boolean bound() { return before != 0 && after != 0; }
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
