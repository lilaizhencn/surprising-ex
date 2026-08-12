package com.surprising.liquidation.provider.repository;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LiquidationSequenceRepository {

    private static final int RESERVATION_SIZE = 512;

    private final JdbcTemplate jdbcTemplate;
    private final Map<String, SequenceBlock> blocks = new ConcurrentHashMap<>();

    public LiquidationSequenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long nextTradingSequence(String sequenceName) {
        return nextValue(tradingSequenceIdentifier(sequenceName));
    }

    public long nextLiquidationSequence(String sequenceName) {
        if (!"liquidation-order".equals(sequenceName)) {
            throw new IllegalArgumentException("invalid liquidation sequence name: " + sequenceName);
        }
        return nextValue("public.liquidation_order_seq");
    }

    private long nextValue(String sequenceIdentifier) {
        return blocks.computeIfAbsent(sequenceIdentifier, ignored -> new SequenceBlock())
                .next(() -> reserve(sequenceIdentifier));
    }

    private List<Long> reserve(String sequenceIdentifier) {
        List<Long> values = jdbcTemplate.queryForList(
                "SELECT nextval(CAST(? AS regclass)) FROM generate_series(1, ?)", Long.class,
                sequenceIdentifier, RESERVATION_SIZE);
        if (values.size() != RESERVATION_SIZE) {
            throw new IllegalStateException("Failed to reserve sequence block for " + sequenceIdentifier);
        }
        return values;
    }

    private String tradingSequenceIdentifier(String sequenceName) {
        if (sequenceName == null || !sequenceName.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("invalid trading sequence name: " + sequenceName);
        }
        return "public.trading_" + sequenceName.toLowerCase().replace('-', '_') + "_seq";
    }

    private static final class SequenceBlock {
        private final ArrayDeque<Long> values = new ArrayDeque<>();

        private synchronized long next(java.util.function.Supplier<List<Long>> reservation) {
            if (values.isEmpty()) {
                values.addAll(reservation.get());
            }
            return values.removeFirst();
        }
    }
}
