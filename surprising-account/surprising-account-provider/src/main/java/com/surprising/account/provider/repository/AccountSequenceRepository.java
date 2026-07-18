package com.surprising.account.provider.repository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountSequenceRepository {

    /**
     * This is deliberately a code constant, not a runtime setting. Every account-service
     * instance and the SQL stress seed must use the same stride, otherwise two Hi/Lo ranges
     * could overlap.
     */
    static final int HI_LO_BLOCK_SIZE = 10_000;
    private static final Map<String, String> DATABASE_SEQUENCES = Map.of(
            "ledger-entry", "public.account_ledger_entry_seq",
            "product-ledger-entry", "public.account_product_ledger_entry_seq",
            "product-transfer", "public.account_product_transfer_seq",
            "position-event", "public.account_position_event_seq",
            "liquidation-fee-event", "public.account_liquidation_fee_event_seq");

    private final JdbcTemplate jdbcTemplate;
    private final int hiLoBlockSize;
    private final ConcurrentMap<String, IdRange> ranges = new ConcurrentHashMap<>();

    @Autowired
    public AccountSequenceRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, HI_LO_BLOCK_SIZE);
    }

    AccountSequenceRepository(JdbcTemplate jdbcTemplate, int hiLoBlockSize) {
        if (hiLoBlockSize <= 0) {
            throw new IllegalArgumentException("hiLoBlockSize must be positive");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.hiLoBlockSize = hiLoBlockSize;
    }

    public long nextSequence(String sequenceName) {
        String databaseSequence = DATABASE_SEQUENCES.get(sequenceName);
        if (databaseSequence == null) {
            throw new IllegalArgumentException("unsupported account sequence " + sequenceName);
        }
        return ranges.computeIfAbsent(sequenceName, ignored -> new IdRange())
                .next(this, databaseSequence);
    }

    private long allocateRangeStart(String databaseSequence) {
        Long value = jdbcTemplate.queryForObject("""
                SELECT nextval(CAST(? AS regclass))
                """, Long.class, databaseSequence);
        if (value == null) {
            throw new IllegalStateException("failed to allocate account sequence " + databaseSequence);
        }
        try {
            return Math.addExact(Math.multiplyExact(value - 1L, hiLoBlockSize), 1L);
        } catch (ArithmeticException ex) {
            throw new IllegalStateException("account sequence exhausted " + databaseSequence, ex);
        }
    }

    private static final class IdRange {
        private long next;
        private long end;

        synchronized long next(AccountSequenceRepository repository, String databaseSequence) {
            if (next == 0L || next > end) {
                next = repository.allocateRangeStart(databaseSequence);
                try {
                    end = Math.addExact(next, repository.hiLoBlockSize - 1L);
                } catch (ArithmeticException ex) {
                    throw new IllegalStateException("account sequence exhausted " + databaseSequence, ex);
                }
            }
            return next++;
        }
    }
}
