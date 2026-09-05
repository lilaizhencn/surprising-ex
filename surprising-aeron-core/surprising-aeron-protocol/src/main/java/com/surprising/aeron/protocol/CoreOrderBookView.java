package com.surprising.aeron.protocol;

import java.util.List;

public record CoreOrderBookView(long exportSequence, List<CoreBookLevelView> levels) {
    public CoreOrderBookView {
        if (exportSequence < 0 || levels == null) {
            throw new IllegalArgumentException("invalid Core order-book projection view");
        }
        levels = List.copyOf(levels);
    }
}
