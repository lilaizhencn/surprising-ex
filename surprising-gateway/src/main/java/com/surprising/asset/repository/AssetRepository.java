package com.surprising.asset.repository;

import com.surprising.asset.model.AssetConfiguration.Asset;
import com.surprising.asset.model.AssetConfiguration.AssetRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** assets 是币种身份、账务精度和上线状态的唯一来源。 */
@Repository
public class AssetRepository {
    private final JdbcTemplate jdbc;

    public AssetRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Asset> list(boolean listedOnly) {
        return jdbc.query("SELECT * FROM assets" + (listedOnly ? " WHERE listed AND trading_enabled" : "")
                + " ORDER BY asset_id", this::map);
    }

    public java.util.Map<String, Long> findAll() {
        return list(false).stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Asset::asset, Asset::scaleUnits));
    }

    public Optional<Asset> find(int assetId) {
        return jdbc.query("SELECT * FROM assets WHERE asset_id=?", this::map, assetId).stream().findFirst();
    }

    public Optional<Asset> lock(int assetId) {
        return jdbc.query("SELECT * FROM assets WHERE asset_id=? FOR UPDATE", this::map, assetId).stream().findFirst();
    }

    public Optional<Asset> byAccountingCode(String asset) {
        return jdbc.query("SELECT * FROM assets WHERE asset=?", this::map, asset).stream().findFirst();
    }

    public Asset save(AssetRequest value) {
        if (value.assetId() == null) {
            return jdbc.queryForObject("""
                    INSERT INTO assets(asset,display_name,logo_url,scale_units,listed,trading_enabled)
                    VALUES (?,?,?,?,?,?) RETURNING *
                    """, this::map, value.asset(), value.displayName(), value.logoUrl(), value.scaleUnits(),
                    value.listed(), value.tradingEnabled());
        }
        return jdbc.queryForObject("""
                UPDATE assets SET display_name=?,logo_url=?,listed=?,trading_enabled=?,
                    revision=revision+1,updated_at=now() WHERE asset_id=? RETURNING *
                """, this::map, value.displayName(), value.logoUrl(), value.listed(), value.tradingEnabled(), value.assetId());
    }

    private Asset map(ResultSet rs, int row) throws SQLException {
        return new Asset(rs.getInt("asset_id"), rs.getString("asset"), rs.getString("display_name"),
                rs.getString("logo_url"), rs.getLong("scale_units"), rs.getBoolean("listed"),
                rs.getBoolean("trading_enabled"), rs.getLong("revision"), rs.getTimestamp("updated_at").toInstant());
    }
}
