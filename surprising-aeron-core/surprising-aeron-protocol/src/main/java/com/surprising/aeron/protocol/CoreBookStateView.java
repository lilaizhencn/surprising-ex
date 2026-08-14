package com.surprising.aeron.protocol;

import java.util.List;

public record CoreBookStateView(long exportSequence, List<CoreBookLevelView> levels) {
    public CoreBookStateView {
        if (exportSequence < 0 || levels == null) {
            throw new IllegalArgumentException("invalid Core book state view");
        }
        levels = List.copyOf(levels);
    }
}
