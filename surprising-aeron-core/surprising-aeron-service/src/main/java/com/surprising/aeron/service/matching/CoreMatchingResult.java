package com.surprising.aeron.service.matching;

import java.util.List;

public record CoreMatchingResult(boolean accepted, String resultCode, List<CoreMatch> matches,
                                 List<CoreCancellationResult> cancellations, int successfulPrefixCount) {

    public CoreMatchingResult(boolean accepted, String resultCode, List<CoreMatch> matches) {
        this(accepted, resultCode, matches, List.of(), 0);
    }

    public CoreMatchingResult {
        if (resultCode == null || resultCode.isBlank() || matches == null || cancellations == null
                || successfulPrefixCount < 0 || successfulPrefixCount > cancellations.size()) {
            throw new IllegalArgumentException("invalid matching result");
        }
        matches = List.copyOf(matches);
        cancellations = List.copyOf(cancellations);
    }
}
