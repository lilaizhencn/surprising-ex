package com.surprising.gateway.provider.repository;

import com.surprising.gateway.provider.model.CustodyWithdrawalModels.WithdrawalRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** gateway_wallet_withdrawal_fees: immutable platform income recognized once on confirmation. */
@Repository
public class CustodyWithdrawalFeeRepository {
    private final JdbcTemplate jdbc;
    public CustodyWithdrawalFeeRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void recognize(WithdrawalRecord withdrawal) {
        int inserted = jdbc.update("""
                insert into gateway_wallet_withdrawal_fees(withdrawal_id,user_id,asset_symbol,fee_units)
                values (?,?,?,?) on conflict (withdrawal_id) do nothing
                """, withdrawal.withdrawalId(), withdrawal.userId(), withdrawal.assetSymbol(), withdrawal.fees().feeUnits());
        if (inserted == 0) {
            Boolean matches = jdbc.queryForObject("""
                    select user_id = ? and asset_symbol = ? and fee_units = ?
                    from gateway_wallet_withdrawal_fees where withdrawal_id = ?
                    """, Boolean.class, withdrawal.userId(), withdrawal.assetSymbol(),
                    withdrawal.fees().feeUnits(), withdrawal.withdrawalId());
            if (!Boolean.TRUE.equals(matches)) {
                throw new IllegalStateException("confirmed withdrawal platform fee conflicts with its income entry");
            }
        }
    }
}
