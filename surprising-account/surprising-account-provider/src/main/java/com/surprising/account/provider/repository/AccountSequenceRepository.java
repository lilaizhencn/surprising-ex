package com.surprising.account.provider.repository;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountSequenceRepository {

    /**
     * 该值必须保持为代码常量，不能改成运行时配置。所有账户服务实例和 SQL 压测种子必须使用相同步长，
     * 否则不同实例分配的 Hi/Lo 区间可能重叠。
     */
    static final int HI_LO_BLOCK_SIZE = 10_000;

    enum Sequence {
        LEDGER_ENTRY("public.account_ledger_entry_seq"),
        PRODUCT_LEDGER_ENTRY("public.account_product_ledger_entry_seq"),
        PRODUCT_TRANSFER("public.account_product_transfer_seq"),
        SPOT_RESERVATION("public.account_spot_reservation_seq"),
        POSITION_EVENT("public.account_position_event_seq"),
        OPEN_INTEREST_EVENT("public.account_open_interest_event_seq"),
        LIQUIDATION_FEE_EVENT("public.account_liquidation_fee_event_seq"),
        COMMAND_RESULT_EVENT("public.account_command_result_event_seq"),
        COMMAND_RETRY_EVENT("public.account_command_retry_event_seq"),
        USER_COMMAND_OUTBOX_EVENT("public.account_user_command_outbox_event_seq"),
        ACCOUNT_RISK_WALLET_EVENT("public.account_risk_wallet_event_seq");

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

    public long nextLedgerEntryId() {
        return nextSequence(Sequence.LEDGER_ENTRY);
    }

    public long nextProductLedgerEntryId() {
        return nextSequence(Sequence.PRODUCT_LEDGER_ENTRY);
    }

    public long nextProductTransferId() {
        return nextSequence(Sequence.PRODUCT_TRANSFER);
    }

    public long nextSpotReservationId() {
        return nextSequence(Sequence.SPOT_RESERVATION);
    }

    public long nextPositionEventId() {
        return nextSequence(Sequence.POSITION_EVENT);
    }

    public long nextOpenInterestEventId() {
        return nextSequence(Sequence.OPEN_INTEREST_EVENT);
    }

    public long nextLiquidationFeeEventId() {
        return nextSequence(Sequence.LIQUIDATION_FEE_EVENT);
    }

    public long nextCommandResultEventId() {
        return nextSequence(Sequence.COMMAND_RESULT_EVENT);
    }

    public long nextCommandRetryEventId() {
        return nextSequence(Sequence.COMMAND_RETRY_EVENT);
    }

    public long nextUserCommandOutboxEventId() {
        return nextSequence(Sequence.USER_COMMAND_OUTBOX_EVENT);
    }

    public long nextRiskWalletEventId() {
        return nextSequence(Sequence.ACCOUNT_RISK_WALLET_EVENT);
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
