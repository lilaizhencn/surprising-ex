package com.surprising.liquidation.provider.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductLineSql;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 合约默认费率仓储，只负责 {@code instruments} 表。 */
@Repository
public class LiquidationInstrumentFeeRepository {

    private final JdbcTemplate jdbcTemplate;

    public LiquidationInstrumentFeeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<Long, InstrumentFee> findAll(List<InstrumentFeeRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return Map.of();
        }
        String values = String.join(", ", Collections.nCopies(requests.size(), "(?, ?, ?)"));
        List<Object> args = new ArrayList<>(requests.size() * 3);
        for (InstrumentFeeRequest request : requests) {
            args.add(request.candidateId());
            args.add(request.symbol());
            args.add(request.instrumentVersion());
        }
        String sql = """
                WITH requested(candidate_id, symbol, instrument_version) AS (
                    VALUES %s
                )
                SELECT r.candidate_id,
                       i.maker_fee_rate_ppm,
                       i.taker_fee_rate_ppm,
                       %s AS product_line
                  FROM requested r
                  JOIN instruments i
                    ON i.symbol = r.symbol
                   AND i.version = r.instrument_version
                """.formatted(values, ProductLineSql.contractTypeProductLineCase("i.contract_type"));
        return jdbcTemplate.query(sql, (rs, rowNum) -> new InstrumentFee(
                        rs.getLong("candidate_id"),
                        ProductLine.valueOf(rs.getString("product_line")),
                        rs.getLong("maker_fee_rate_ppm"),
                        rs.getLong("taker_fee_rate_ppm")), args.toArray())
                .stream()
                .collect(Collectors.toMap(InstrumentFee::candidateId, fee -> fee));
    }

    public record InstrumentFeeRequest(long candidateId, String symbol, long instrumentVersion) {
    }

    public record InstrumentFee(long candidateId,
                                ProductLine productLine,
                                long makerFeeRatePpm,
                                long takerFeeRatePpm) {
    }
}
