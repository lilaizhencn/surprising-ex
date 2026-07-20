package com.surprising.account.provider.repository;

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

    enum Sequence {
        LEDGER_ENTRY("public.account_ledger_entry_seq"),
        PRODUCT_LEDGER_ENTRY("public.account_product_ledger_entry_seq"),
        PRODUCT_TRANSFER("public.account_product_transfer_seq"),
        SPOT_RESERVATION("public.account_spot_reservation_seq"),
        POSITION_EVENT("public.account_position_event_seq"),
        LIQUIDATION_FEE_EVENT("public.account_liquidation_fee_event_seq"),
        COMMAND_RESULT_EVENT("public.account_command_result_event_seq"),
        COMMAND_RETRY_EVENT("public.account_command_retry_event_seq"),
        USER_COMMAND_OUTBOX_EVENT("public.account_user_command_outbox_event_seq");

        private final String databaseSequence;

        Sequence(String databaseSequence) {
            this.databaseSequence = databaseSequence;
        }

        String databaseSequence() {
            return databaseSequence;
        }
    }

    private final JdbcTemplate jdbcTemplate;
    private final int hiLoBlockSize;
    private final ConcurrentMap<Sequence, IdRange> ranges = new ConcurrentHashMap<>();

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

    public long nextSequence(Sequence sequence) {
        return ranges.computeIfAbsent(sequence, ignored -> new IdRange())
                .next(this, sequence.databaseSequence());
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
