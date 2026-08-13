package com.surprising.aeron.service.matching;

import java.util.List;

public record CoreMatchingResult(boolean accepted, String resultCode, List<CoreMatch> matches) {

    public CoreMatchingResult {
        if (resultCode == null || resultCode.isBlank() || matches == null) {
            throw new IllegalArgumentException("invalid matching result");
        }
        matches = List.copyOf(matches);
    }
}
