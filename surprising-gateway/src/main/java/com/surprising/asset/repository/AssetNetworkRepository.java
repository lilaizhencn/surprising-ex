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

    public List<Network> list(int assetId) {
        return jdbc.query("SELECT * FROM asset_networks WHERE asset_id=? ORDER BY network_id", this::map, assetId);
    }

    public Optional<Network> lock(int assetId, int networkId) {
        return jdbc.query("SELECT * FROM asset_networks WHERE asset_id=? AND network_id=? FOR UPDATE",
                this::map, assetId, networkId).stream().findFirst();
    }

    public Network save(int assetId, NetworkRequest value) {
        if (value.networkId() == null) {
            return jdbc.queryForObject("""
                    INSERT INTO asset_networks(asset_id,network_code,display_name,contract_address,native_asset,
                        chain_decimals,deposit_enabled,withdrawal_enabled,min_deposit,min_withdrawal,
                        withdrawal_fee,confirmations) VALUES (?,?,?,?,?,?,?,?,?,?,?,?) RETURNING *
                    """, this::map, assetId, value.networkCode(), value.displayName(), value.contractAddress(),
                    value.nativeAsset(), value.chainDecimals(), value.depositEnabled(), value.withdrawalEnabled(),
                    value.minDeposit(), value.minWithdrawal(), value.withdrawalFee(), value.confirmations());
        }
        return jdbc.queryForObject("""
                UPDATE asset_networks SET display_name=?,deposit_enabled=?,withdrawal_enabled=?,min_deposit=?,
                    min_withdrawal=?,withdrawal_fee=?,confirmations=?,revision=revision+1,updated_at=now()
                    WHERE asset_id=? AND network_id=? RETURNING *
                """, this::map, value.displayName(), value.depositEnabled(), value.withdrawalEnabled(),
                value.minDeposit(), value.minWithdrawal(), value.withdrawalFee(), value.confirmations(), assetId, value.networkId());
    }

    private Network map(ResultSet rs, int row) throws SQLException {
        return new Network(rs.getInt("network_id"), rs.getInt("asset_id"), rs.getString("network_code"),
                rs.getString("display_name"), rs.getString("contract_address"), rs.getBoolean("native_asset"),
                rs.getInt("chain_decimals"), rs.getBoolean("deposit_enabled"), rs.getBoolean("withdrawal_enabled"),
                rs.getBigDecimal("min_deposit"), rs.getBigDecimal("min_withdrawal"), rs.getBigDecimal("withdrawal_fee"),
                rs.getInt("confirmations"), rs.getLong("revision"), rs.getTimestamp("updated_at").toInstant());
    }
}
