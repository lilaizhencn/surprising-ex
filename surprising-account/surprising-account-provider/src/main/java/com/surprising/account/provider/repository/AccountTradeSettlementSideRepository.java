package com.surprising.account.provider.repository;

import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.TradeSideSettlementCommand;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MatchTradeEvent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountTradeSettlementSideRepository {

    private final JdbcTemplate jdbcTemplate;

    public AccountTradeSettlementSideRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void project(AccountUserCommand command,
                        TradeSideSettlementCommand sideCommand,
                        long consumedUnits,
                        long releasedUnits,
                        Instant appliedAt) {
        if (command == null || command.commandType() != com.surprising.account.api.model.AccountUserCommandType.TRADE_SIDE_SETTLE
                || sideCommand == null || consumedUnits < 0L || releasedUnits < 0L) {
            throw new IllegalArgumentException("成交结算侧投影参数无效");
        }
        MatchTradeEvent trade = sideCommand.trade();
        ProductLine productLine = command.productLine();
        Instant now = appliedAt == null ? Instant.now() : appliedAt;
        int inserted = jdbcTemplate.update("""
                INSERT INTO account_trade_settlement_sides (
                    product_line, symbol, trade_id, participant_role, taker_user_id, maker_user_id,
                    command_id, order_id, order_margin_consumed_units, order_margin_released_units, applied_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (product_line, symbol, trade_id, participant_role) DO NOTHING
                """,
                productLine.name(), trade.symbol(), trade.tradeId(), sideCommand.participantRole().name(),
                trade.takerUserId(), trade.makerUserId(), command.commandId(), sideCommand.orderId(),
                consumedUnits, releasedUnits, Timestamp.from(now));
        if (inserted == 1) {
            return;
        }
        ExistingSide existing = jdbcTemplate.query("""
                SELECT participant_role, taker_user_id, maker_user_id, command_id, order_id,
                       order_margin_consumed_units, order_margin_released_units
                  FROM account_trade_settlement_sides
                 WHERE product_line = ? AND symbol = ? AND trade_id = ? AND participant_role = ?
                """, (rs, rowNum) -> new ExistingSide(
                rs.getString("participant_role"), rs.getLong("taker_user_id"), rs.getLong("maker_user_id"),
                rs.getString("command_id"), rs.getLong("order_id"),
                rs.getLong("order_margin_consumed_units"), rs.getLong("order_margin_released_units")),
                productLine.name(), trade.symbol(), trade.tradeId(), sideCommand.participantRole().name())
                .stream().findFirst().orElseThrow(
                        () -> new IllegalStateException("成交结算侧幂等记录不存在 tradeId=" + trade.tradeId()));
        if (!existing.matches(sideCommand, command.commandId(), consumedUnits, releasedUnits,
                trade.takerUserId(), trade.makerUserId())) {
            throw new IllegalStateException("成交结算侧幂等冲突 tradeId=" + trade.tradeId()
                    + " role=" + sideCommand.participantRole());
        }
    }

    private record ExistingSide(String participantRole,
                                long takerUserId,
                                long makerUserId,
                                String commandId,
                                long orderId,
                                long consumedUnits,
                                long releasedUnits) {

        private boolean matches(TradeSideSettlementCommand sideCommand,
                                String expectedCommandId,
                                long expectedConsumedUnits,
                                long expectedReleasedUnits,
                                long expectedTakerUserId,
                                long expectedMakerUserId) {
            return participantRole.equals(sideCommand.participantRole().name())
                    && takerUserId == expectedTakerUserId
                    && makerUserId == expectedMakerUserId
                    && Objects.equals(commandId, expectedCommandId)
                    && orderId == sideCommand.orderId()
                    && consumedUnits == expectedConsumedUnits
                    && releasedUnits == expectedReleasedUnits;
        }
    }
}
