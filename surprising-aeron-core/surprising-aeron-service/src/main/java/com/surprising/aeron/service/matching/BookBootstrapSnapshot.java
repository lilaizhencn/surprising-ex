package com.surprising.aeron.service.matching;

import com.surprising.aeron.protocol.CoreBookLevelView;
import java.util.List;

public record BookBootstrapSnapshot(List<String> symbols, List<CoreBookLevelView> levels) {

    public BookBootstrapSnapshot {
        if (symbols == null || levels == null) {
            throw new IllegalArgumentException("invalid book bootstrap snapshot");
        }
        symbols = List.copyOf(symbols);
        levels = List.copyOf(levels);
    }
}
