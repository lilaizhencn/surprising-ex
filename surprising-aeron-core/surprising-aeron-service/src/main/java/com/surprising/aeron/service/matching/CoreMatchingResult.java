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
    /** 尚未绑定恢复证据时共享的不可变空值，不为每次拒单创建占位对象。 */
    private static final NativeCommand EMPTY_COMMAND = new NativeCommand(0, 0, 0, 0, 0, 0, 0, -1);
    private static final MatcherPrefix EMPTY_PREFIX = new MatcherPrefix(0, 0);

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
    /** Compatibility view only; hot paths use the primitive identity fields above. */
    private NativeCommand nativeCommandView;
    /** Prefix values are kept as primitives on the hot path; the record view is lazy for API compatibility. */
    private long matcherPrefixBefore;
    private long matcherPrefixAfter;
    private MatcherPrefix matcherPrefixView;
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
                EMPTY_COMMAND, EMPTY_PREFIX, null,
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
        this.cancellations = immutable(cancellations);
        this.successfulPrefixCount = successfulPrefixCount;
        this.matcherStateChanged = matcherStateChanged;
        this.outcome = classify(accepted, resultCode, matcherStateChanged);
        copyNativeCommand(nativeCommand);
        this.matcherPrefixBefore = matcherPrefix.before();
        this.matcherPrefixAfter = matcherPrefix.after();
        this.matcherPrefixView = matcherPrefix;
        this.nativeMatcherResult = nativeMatcherResult;
        this.matcherEvents = matcherEvents;
        this.marketData = marketData;
    }

    static CoreMatchingResult fromNative(MatcherResult result) {
        return new CoreMatchingResult(result, EMPTY_COMMAND, 0);
    }

    static CoreMatchingResult fromNativeWithEvidence(
            MatcherResult result, NativeCommand command, long previousPrefix) {
        if (previousPrefix == 0) throw new IllegalArgumentException("matcher prefix is required");
        Objects.requireNonNull(command, "native command");
        return fromNativeWithEvidence(result,
                command.coreSequence(), command.commandIdMostSignificantBits(),
                command.commandIdLeastSignificantBits(), command.orderId(),
                command.nativeSequence(), command.matcherSequence(), command.aeronTimestamp(),
                command.matcherShardId(), previousPrefix);
    }

    static CoreMatchingResult fromNativeWithEvidence(
            MatcherResult result, long coreSequence, long commandIdMostSignificantBits,
            long commandIdLeastSignificantBits, long orderId,
            long nativeSequence, long matcherSequence, long aeronTimestamp, int matcherShardId,
            long previousPrefix) {
        if (previousPrefix == 0) throw new IllegalArgumentException("matcher prefix is required");
        return new CoreMatchingResult(result, coreSequence, commandIdMostSignificantBits,
                commandIdLeastSignificantBits, orderId, nativeSequence,
                matcherSequence, aeronTimestamp, matcherShardId, previousPrefix);
    }

    /** Native events are already immutable; build the result and its evidence once. */
    private CoreMatchingResult(MatcherResult result, NativeCommand command, long previousPrefix) {
        this(result, command.coreSequence(), command.commandIdMostSignificantBits(),
                command.commandIdLeastSignificantBits(), command.orderId(),
                command.nativeSequence(), command.matcherSequence(), command.aeronTimestamp(),
                command.matcherShardId(), previousPrefix);
        nativeCommandView = command;
    }

    private CoreMatchingResult(MatcherResult result, long coreSequence,
                                long commandIdMostSignificantBits, long commandIdLeastSignificantBits,
                                long orderId, long nativeSequence,
                                long matcherSequence, long aeronTimestamp, int matcherShardId,
                                long previousPrefix) {
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
        // The digest reads only the business fields initialized above; it never retains this.
        matcherPrefixBefore = previousPrefix;
        matcherPrefixAfter = previousPrefix == 0 ? 0 : MatcherPrefixDigest.next(previousPrefix,
                coreSequence, commandIdMostSignificantBits, commandIdLeastSignificantBits,
                orderId, matcherSequence, aeronTimestamp, this);
        matcherPrefixView = previousPrefix == 0 ? EMPTY_PREFIX : null;
    }

    CoreMatchingResult withEvidence(NativeCommand command, MatcherPrefix prefix) {
        return new CoreMatchingResult(this, command, prefix.before(), prefix.after(), prefix);
    }

    /** Matcher-only binding path; called before publication into the completion ring. */
    CoreMatchingResult bindEvidenceInPlace(NativeCommand command, MatcherPrefix prefix) {
        Objects.requireNonNull(command, "native command");
        copyNativeCommand(command);
        Objects.requireNonNull(prefix, "matcher prefix");
        matcherPrefixBefore = prefix.before();
        matcherPrefixAfter = prefix.after();
        matcherPrefixView = prefix;
        return this;
    }

    /** Matcher-only binding path that avoids constructing a prefix record for every result. */
    CoreMatchingResult bindEvidenceInPlace(NativeCommand command, long before, long after) {
        Objects.requireNonNull(command, "native command");
        copyNativeCommand(command);
        matcherPrefixBefore = before;
        matcherPrefixAfter = after;
        matcherPrefixView = null;
        return this;
    }

    /** Matcher-only binding path that keeps native command identity in primitive fields. */
    CoreMatchingResult bindEvidenceInPlace(
            long coreSequence, long commandIdMostSignificantBits, long commandIdLeastSignificantBits,
            long orderId, long nativeSequence, long matcherSequence,
            long aeronTimestamp, int matcherShardId, long before, long after) {
        nativeCoreSequence = coreSequence;
        nativeCommandIdMostSignificantBits = commandIdMostSignificantBits;
        nativeCommandIdLeastSignificantBits = commandIdLeastSignificantBits;
        nativeOrderId = orderId;
        nativeSequenceValue = nativeSequence;
        nativeMatcherSequence = matcherSequence;
        nativeAeronTimestamp = aeronTimestamp;
        nativeMatcherShardId = matcherShardId;
        nativeCommandView = null;
        matcherPrefixBefore = before;
        matcherPrefixAfter = after;
        matcherPrefixView = null;
        return this;
    }

    public CoreMatchingResult withCoreSequence(long coreSequence) {
        if (coreSequence <= 0) throw new IllegalArgumentException("coreSequence must be positive");
        if (nativeCoreSequence == coreSequence) return this;
        if (nativeCoreSequence != 0) throw new IllegalStateException("matching result sequence mismatch");
        return new CoreMatchingResult(this, coreSequence, nativeCommandIdMostSignificantBits,
                nativeCommandIdLeastSignificantBits, nativeOrderId,
                nativeSequenceValue, nativeMatcherSequence, nativeAeronTimestamp, nativeMatcherShardId,
                matcherPrefixBefore, matcherPrefixAfter, matcherPrefixView);
    }

    /** Matcher worker variant that avoids a second result object before publication. */
    public CoreMatchingResult withCoreSequenceInPlace(long coreSequence) {
        if (coreSequence <= 0) throw new IllegalArgumentException("coreSequence must be positive");
        if (nativeCoreSequence == coreSequence) return this;
        if (nativeCoreSequence != 0) throw new IllegalStateException("matching result sequence mismatch");
        nativeCoreSequence = coreSequence;
        nativeCommandView = null;
        return this;
    }

    private CoreMatchingResult(CoreMatchingResult source, NativeCommand command,
                               long prefixBefore, long prefixAfter, MatcherPrefix prefixView) {
        this(source, command.coreSequence(), command.commandIdMostSignificantBits(),
                command.commandIdLeastSignificantBits(), command.orderId(),
                command.nativeSequence(), command.matcherSequence(), command.aeronTimestamp(),
                command.matcherShardId(), prefixBefore, prefixAfter, prefixView);
        nativeCommandView = command;
    }

    private CoreMatchingResult(CoreMatchingResult source, long coreSequence,
                               long commandIdMostSignificantBits, long commandIdLeastSignificantBits,
                               long orderId, long nativeSequence,
                               long matcherSequence, long aeronTimestamp, int matcherShardId,
                               long prefixBefore, long prefixAfter, MatcherPrefix prefixView) {
        accepted = source.accepted;
        resultCode = source.resultCode;
        cancellations = source.cancellations;
        successfulPrefixCount = source.successfulPrefixCount;
        matcherStateChanged = source.matcherStateChanged;
        outcome = source.outcome;
        if (prefixBefore < 0 || prefixAfter < 0) throw new IllegalArgumentException("invalid matcher prefix");
        nativeCoreSequence = coreSequence;
        nativeCommandIdMostSignificantBits = commandIdMostSignificantBits;
        nativeCommandIdLeastSignificantBits = commandIdLeastSignificantBits;
        nativeOrderId = orderId;
        nativeSequenceValue = nativeSequence;
        nativeMatcherSequence = matcherSequence;
        nativeAeronTimestamp = aeronTimestamp;
        nativeMatcherShardId = matcherShardId;
        matcherPrefixBefore = prefixBefore;
        matcherPrefixAfter = prefixAfter;
        matcherPrefixView = prefixView;
        nativeMatcherResult = source.nativeMatcherResult;
        matcherEvents = source.matcherEvents;
        marketData = source.marketData;
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
    public NativeCommand nativeCommand() {
        NativeCommand view = nativeCommandView;
        if (view != null && view != EMPTY_COMMAND) return view;
        if (nativeMatcherResult == null && nativeCoreSequence == 0 && nativeOrderId == 0 && nativeSequenceValue == 0
                && nativeMatcherSequence == 0 && nativeAeronTimestamp == 0
                && nativeMatcherShardId == -1 && nativeCommandIdMostSignificantBits == 0
                && nativeCommandIdLeastSignificantBits == 0) return EMPTY_COMMAND;
        // Compatibility API: hot paths use primitive accessors and never materialize this view.
        long sequence = nativeSequenceValue;
        if (sequence == 0 && nativeMatcherResult != null) sequence = nativeMatcherResult.sequence();
        view = new NativeCommand(nativeCoreSequence, nativeCommandIdMostSignificantBits,
                nativeCommandIdLeastSignificantBits, nativeOrderId,
                sequence, nativeMatcherSequence, nativeAeronTimestamp, nativeMatcherShardId);
        nativeCommandView = view;
        return view;
    }
    /** 原生结果绑定证据前只需序号，直接读取而不物化占位身份。 */
    long nativeSequence() {
        return nativeSequenceValue == 0 && nativeMatcherResult != null
                ? nativeMatcherResult.sequence() : nativeSequenceValue;
    }
    public long nativeCoreSequence() { return nativeCoreSequence; }
    public long nativeCommandIdMostSignificantBits() { return nativeCommandIdMostSignificantBits; }
    public long nativeCommandIdLeastSignificantBits() { return nativeCommandIdLeastSignificantBits; }
    public long nativeOrderId() { return nativeOrderId; }
    public long nativeSequenceValue() { return nativeSequence(); }
    public long nativeMatcherSequence() { return nativeMatcherSequence; }
    public long nativeAeronTimestamp() { return nativeAeronTimestamp; }
    public int nativeMatcherShardId() { return nativeMatcherShardId; }
    public boolean nativeMatches(java.util.UUID commandId) {
        return commandId != null
                && nativeCommandIdMostSignificantBits == commandId.getMostSignificantBits()
                && nativeCommandIdLeastSignificantBits == commandId.getLeastSignificantBits();
    }
    public MatcherPrefix matcherPrefix() {
        MatcherPrefix view = matcherPrefixView;
        if (view != null) return view;
        if (matcherPrefixBefore == 0 && matcherPrefixAfter == 0) return EMPTY_PREFIX;
        view = new MatcherPrefix(matcherPrefixBefore, matcherPrefixAfter);
        matcherPrefixView = view;
        return view;
    }
    public long matcherPrefixBefore() { return matcherPrefixBefore; }
    public long matcherPrefixAfter() { return matcherPrefixAfter; }
    public boolean matcherPrefixBound() { return matcherPrefixBefore != 0 && matcherPrefixAfter != 0; }
    public MatcherResult nativeMatcherResult() { return nativeMatcherResult; }
    public List<MatcherResult.MatcherEvent> matcherEvents() { return matcherEvents; }
    public MatcherResult.MarketData marketData() { return marketData; }

    private void copyNativeCommand(NativeCommand command) {
        nativeCoreSequence = command.coreSequence();
        nativeCommandIdMostSignificantBits = command.commandIdMostSignificantBits();
        nativeCommandIdLeastSignificantBits = command.commandIdLeastSignificantBits();
        nativeOrderId = command.orderId();
        nativeSequenceValue = command.nativeSequence();
        nativeMatcherSequence = command.matcherSequence();
        nativeAeronTimestamp = command.aeronTimestamp();
        nativeMatcherShardId = command.matcherShardId();
        nativeCommandView = command;
    }

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

    public enum Outcome {
        REJECTED_UNCHANGED,
        KNOWN_PREFIX_APPLIED,
        APPLIED,
        FATAL_DIVERGENCE
    }

    public record NativeCommand(long coreSequence,
                                long commandIdMostSignificantBits,
                                long commandIdLeastSignificantBits,
                                long orderId,
                                long nativeSequence, long matcherSequence, long aeronTimestamp,
                                int matcherShardId) {
        public NativeCommand {
            if (coreSequence < 0 || orderId < 0
                    || nativeSequence < 0 || matcherSequence < 0 || aeronTimestamp < 0 || matcherShardId < -1) {
                throw new IllegalArgumentException("invalid native command identity");
            }
        }

        public NativeCommand(long coreSequence, java.util.UUID commandId, long orderId,
                             long nativeSequence, long matcherSequence, long aeronTimestamp) {
            this(coreSequence, commandId == null ? 0 : commandId.getMostSignificantBits(),
                    commandId == null ? 0 : commandId.getLeastSignificantBits(), orderId,
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
