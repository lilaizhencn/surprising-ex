package com.surprising.asset.repository;

import com.surprising.asset.model.AssetConfiguration.Network;
import com.surprising.asset.model.AssetConfiguration.NetworkRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AssetNetworkRepository {
    private final JdbcTemplate jdbc;
    public AssetNetworkRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Network> enabled() {
        return jdbc.query("SELECT * FROM asset_networks WHERE enabled AND (deposit_enabled OR withdrawal_enabled) ORDER BY is_default DESC, network_id ASC", this::map);
    }

    public List<Network> list(int assetId) {
        return jdbc.query("SELECT * FROM asset_networks WHERE asset_id=? ORDER BY is_default DESC, network_id ASC", this::map, assetId);
    }

    public Optional<Network> lock(int assetId, int networkId) {
        return jdbc.query("SELECT * FROM asset_networks WHERE asset_id=? AND network_id=? FOR UPDATE",
                this::map, assetId, networkId).stream().findFirst();
    }

    public Network save(int assetId, NetworkRequest value) {
        if (Boolean.TRUE.equals(value.isDefault())) {
            jdbc.update("""
                    UPDATE asset_networks SET is_default=FALSE, revision=revision+1, updated_at=now()
                    WHERE asset_id=? AND is_default AND (? IS NULL OR network_id <> ?)
                    """, assetId, value.networkId(), value.networkId());
        }
        if (value.networkId() == null) {
            return jdbc.queryForObject("""
                    INSERT INTO asset_networks(
                        asset_id,network_code,display_name,token_standard,contract_address,native_asset,
                        chain_decimals,enabled,is_default,deposit_enabled,withdrawal_enabled,memo_required,
                        internal_withdraw_enabled,auto_withdraw_enabled,maintenance_mode,
                        min_deposit,min_withdrawal,max_withdrawal,daily_max_withdrawal,withdrawal_fee,
                        auto_audit_max_amount,withdraw_precision,confirmations,unlock_confirmations,
                        estimated_arrival_minutes,address_regex,memo_regex,explorer_tx_url_template,special_tips
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING *
                    """, this::map,
                    assetId, value.networkCode(), value.displayName(), value.tokenStandard(), value.contractAddress(),
                    value.nativeAsset(), value.chainDecimals(), Boolean.TRUE.equals(value.enabled()),
                    Boolean.TRUE.equals(value.isDefault()), value.depositEnabled(), value.withdrawalEnabled(),
                    Boolean.TRUE.equals(value.memoRequired()), Boolean.TRUE.equals(value.internalWithdrawEnabled()),
                    Boolean.TRUE.equals(value.autoWithdrawEnabled()), Boolean.TRUE.equals(value.maintenanceMode()),
                    value.minDeposit(), value.minWithdrawal(), value.maxWithdrawal(), value.dailyMaxWithdrawal(),
                    value.withdrawalFee(), value.autoAuditMaxAmount(), value.withdrawPrecision(),
                    value.confirmations(), value.unlockConfirmations(), value.estimatedArrivalMinutes(),
                    value.addressRegex(), value.memoRegex(), value.explorerTxUrlTemplate(), value.specialTips());
        }
        return jdbc.queryForObject("""
                UPDATE asset_networks SET
                    display_name=?,token_standard=?,enabled=?,is_default=?,
                    deposit_enabled=?,withdrawal_enabled=?,memo_required=?,
                    internal_withdraw_enabled=?,auto_withdraw_enabled=?,maintenance_mode=?,
                    min_deposit=?,min_withdrawal=?,max_withdrawal=?,daily_max_withdrawal=?,
                    withdrawal_fee=?,auto_audit_max_amount=?,withdraw_precision=?,
                    confirmations=?,unlock_confirmations=?,estimated_arrival_minutes=?,
                    address_regex=?,memo_regex=?,explorer_tx_url_template=?,special_tips=?,
                    revision=revision+1,updated_at=now()
                    WHERE asset_id=? AND network_id=? RETURNING *
                """, this::map,
                value.displayName(), value.tokenStandard(), Boolean.TRUE.equals(value.enabled()),
                Boolean.TRUE.equals(value.isDefault()), value.depositEnabled(), value.withdrawalEnabled(),
                Boolean.TRUE.equals(value.memoRequired()), Boolean.TRUE.equals(value.internalWithdrawEnabled()),
                Boolean.TRUE.equals(value.autoWithdrawEnabled()), Boolean.TRUE.equals(value.maintenanceMode()),
                value.minDeposit(), value.minWithdrawal(), value.maxWithdrawal(), value.dailyMaxWithdrawal(),
                value.withdrawalFee(), value.autoAuditMaxAmount(), value.withdrawPrecision(),
                value.confirmations(), value.unlockConfirmations(), value.estimatedArrivalMinutes(),
                value.addressRegex(), value.memoRegex(), value.explorerTxUrlTemplate(), value.specialTips(),
                assetId, value.networkId());
    }

    /** 关闭币种充值总开关时级联关闭其所有网络的充值开关。 */
    public int disableDepositByAsset(int assetId) {
        return jdbc.update("""
                UPDATE asset_networks SET deposit_enabled=FALSE, revision=revision+1, updated_at=now()
                WHERE asset_id=? AND deposit_enabled
                """, assetId);
    }

    /** 关闭币种提现总开关时级联关闭其所有网络的提现开关。 */
    public int disableWithdrawalByAsset(int assetId) {
        return jdbc.update("""
                UPDATE asset_networks SET withdrawal_enabled=FALSE, revision=revision+1, updated_at=now()
                WHERE asset_id=? AND withdrawal_enabled
                """, assetId);
    }

    private Network map(ResultSet rs, int row) throws SQLException {
        return new Network(
                rs.getInt("network_id"),
                rs.getInt("asset_id"),
                rs.getString("network_code"),
                rs.getString("display_name"),
                rs.getString("token_standard"),
                rs.getString("contract_address"),
                rs.getBoolean("native_asset"),
                rs.getInt("chain_decimals"),
                rs.getBoolean("enabled"),
                rs.getBoolean("is_default"),
                rs.getBoolean("deposit_enabled"),
                rs.getBoolean("withdrawal_enabled"),
                rs.getBoolean("memo_required"),
                rs.getBoolean("internal_withdraw_enabled"),
                rs.getBoolean("auto_withdraw_enabled"),
                rs.getBoolean("maintenance_mode"),
                rs.getBigDecimal("min_deposit"),
                rs.getBigDecimal("min_withdrawal"),
                rs.getBigDecimal("max_withdrawal"),
                rs.getBigDecimal("daily_max_withdrawal"),
                rs.getBigDecimal("withdrawal_fee"),
                rs.getBigDecimal("auto_audit_max_amount"),
                rs.getInt("withdraw_precision"),
                rs.getInt("confirmations"),
                rs.getInt("unlock_confirmations"),
                rs.getInt("estimated_arrival_minutes"),
                rs.getString("address_regex"),
                rs.getString("memo_regex"),
                rs.getString("explorer_tx_url_template"),
                rs.getString("special_tips"),
                rs.getLong("revision"),
                rs.getTimestamp("updated_at").toInstant());
    }
}
