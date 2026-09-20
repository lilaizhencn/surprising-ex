package com.surprising.aeron.service.matching;

import exchange.core2.core.common.MatcherResult;
import java.util.List;

/** Read-only matching fact. Direct commands implement this on their pooled settlement event. */
public interface MatchingResult {
    enum Outcome { REJECTED_UNCHANGED, KNOWN_PREFIX_APPLIED, APPLIED, FATAL_DIVERGENCE }

    boolean accepted();
    String resultCode();
    List<CoreCancellationResult> cancellations();
    int successfulPrefixCount();
    boolean matcherStateChanged();
    Outcome outcome();
    long nativeCoreSequence();
    long nativeOrderId();
    long nativeMatcherSequence();
    int nativeMatcherShardId();
    boolean nativeMatches(java.util.UUID commandId);
    MatcherResult nativeMatcherResult();
    List<MatcherResult.MatcherEvent> matcherEvents();
    MatcherResult.MarketData marketData();
}
