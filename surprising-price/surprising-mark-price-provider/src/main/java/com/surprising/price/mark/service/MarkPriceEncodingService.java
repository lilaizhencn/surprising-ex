package com.surprising.price.mark.service;

import com.surprising.price.mark.config.MarkPriceProperties;
import com.surprising.price.mark.model.MarkPriceEncoding;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 读取标记价格定点编码参数。
 *
 * <p>该查询必须把当前合约版本、合约价格精度和报价资产精度作为同一快照读取；
 * 若拆成三次 Repository 查询，版本切换时可能组合出不存在的编码，因此保留跨表 JOIN，
 * 并明确放在 Service 而不是 Repository。</p>
 */
@Service
public class MarkPriceEncodingService {

    private final JdbcTemplate jdbcTemplate;
    private final MarkPriceProperties properties;

    public MarkPriceEncodingService(JdbcTemplate jdbcTemplate, MarkPriceProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties == null ? new MarkPriceProperties() : properties;
    }

    public MarkPriceEncoding encoding(String symbol) {
        List<Object> args = new ArrayList<>(List.of(symbol));
        String productCondition = productCondition(args, "i");
        return jdbcTemplate.query("""
                SELECT i.version, qs.scale_units, i.price_tick_units
                  FROM instruments i
                  JOIN instrument_current_versions c
                    ON c.symbol = i.symbol AND c.version = i.version
                  JOIN account_asset_scales qs
                    ON qs.asset = i.quote_asset
                 WHERE i.symbol = ?
                %s
                """.formatted(productCondition), (rs, rowNum) -> new MarkPriceEncoding(
                rs.getLong("version"), rs.getLong("scale_units"), rs.getLong("price_tick_units")), args.toArray())
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("mark price encoding not found for " + symbol));
    }

    private String productCondition(List<Object> args, String alias) {
        if (!properties.getKafka().isProductTopicsEnabled()) {
            return "";
        }
        args.add(properties.getKafka().getProductLine().contractTypeCode());
        return "   AND " + alias + ".contract_type = ?";
    }
}
