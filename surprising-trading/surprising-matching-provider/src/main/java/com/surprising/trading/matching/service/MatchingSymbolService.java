package com.surprising.trading.matching.service;

import com.surprising.trading.matching.config.MatchingProperties;
import com.surprising.trading.matching.model.InstrumentSymbol;
import com.surprising.trading.matching.model.MatchingSymbol;
import com.surprising.trading.matching.repository.MatchingAssetRepository;
import com.surprising.trading.matching.repository.MatchingSequenceRepository;
import com.surprising.trading.matching.repository.MatchingSymbolRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 聚合合约快照、撮合资产和撮合交易对。
 *
 * <p>不可拆原因：启用交易对必须让 {@code instrument_current_versions} 指针与
 * {@code instruments} 版本正文在同一 SQL 快照中校验状态和产品线。拆成两次查询会在版本切换时
 * 短暂加载已停用版本，因此该 JOIN 保留在 Service；两个撮合 Repository 仍各自只访问一张表。</p>
 */
@Service
public class MatchingSymbolService {

    private final JdbcTemplate jdbcTemplate;
    private final MatchingAssetRepository assetRepository;
    private final MatchingSymbolRepository symbolRepository;
    private final MatchingSequenceRepository sequenceRepository;
    private final MatchingProperties properties;

    public MatchingSymbolService(JdbcTemplate jdbcTemplate,
                                 MatchingAssetRepository assetRepository,
                                 MatchingSymbolRepository symbolRepository,
                                 MatchingSequenceRepository sequenceRepository,
                                 MatchingProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.assetRepository = assetRepository;
        this.symbolRepository = symbolRepository;
        this.sequenceRepository = sequenceRepository;
        this.properties = properties;
    }

    public List<InstrumentSymbol> currentTradingSymbols() {
        StringBuilder sql = new StringBuilder("""
                SELECT i.symbol, i.base_asset, i.quote_asset, i.settle_asset
                  FROM instruments i
                  JOIN instrument_current_versions c
                    ON c.symbol = i.symbol AND c.version = i.version
                 WHERE i.status IN ('TRADING', 'HALT')
                """);
        List<Object> args = new ArrayList<>();
        productContractTypeFilter().ifPresent(contractType -> {
            sql.append("   AND i.contract_type = ?\n");
            args.add(contractType);
        });
        sql.append(" ORDER BY i.symbol ASC");
        return jdbcTemplate.query(sql.toString(), instrumentMapper(), args.toArray());
    }

    public Optional<InstrumentSymbol> currentTradingSymbol(String symbol) {
        StringBuilder sql = new StringBuilder("""
                SELECT i.symbol, i.base_asset, i.quote_asset, i.settle_asset
                  FROM instruments i
                  JOIN instrument_current_versions c
                    ON c.symbol = i.symbol AND c.version = i.version
                 WHERE i.symbol = ? AND i.status IN ('TRADING', 'HALT')
                """);
        List<Object> args = new ArrayList<>();
        args.add(symbol);
        productContractTypeFilter().ifPresent(contractType -> {
            sql.append("   AND i.contract_type = ?\n");
            args.add(contractType);
        });
        return jdbcTemplate.query(sql.toString(), instrumentMapper(), args.toArray()).stream().findFirst();
    }

    @Transactional
    public MatchingSymbol ensureMatchingSymbol(InstrumentSymbol instrument) {
        int baseCurrencyId = ensureAsset(instrument.baseAsset());
        ensureAsset(instrument.quoteAsset());
        int quoteCurrencyId = ensureAsset(instrument.settleAsset());
        Optional<MatchingSymbol> existing = symbolRepository.find(instrument.symbol());
        if (existing.isPresent()) {
            return existing.get();
        }
        int symbolId = sequenceRepository.nextIntSequence("matching-symbol");
        symbolRepository.insert(instrument, symbolId, baseCurrencyId, quoteCurrencyId, Instant.now());
        return symbolRepository.find(instrument.symbol())
                .orElseThrow(() -> new IllegalStateException(
                        "failed to ensure matching symbol " + instrument.symbol()));
    }

    private int ensureAsset(String asset) {
        Optional<Integer> existing = assetRepository.findId(asset);
        if (existing.isPresent()) {
            return existing.get();
        }
        int assetId = sequenceRepository.nextIntSequence("matching-asset");
        assetRepository.insert(asset, assetId);
        return assetRepository.findId(asset)
                .orElseThrow(() -> new IllegalStateException("failed to ensure matching asset " + asset));
    }

    private Optional<String> productContractTypeFilter() {
        MatchingProperties.Kafka kafka = properties.getKafka();
        return kafka.isProductTopicsEnabled()
                ? Optional.of(kafka.getProductLine().contractTypeCode())
                : Optional.empty();
    }

    private RowMapper<InstrumentSymbol> instrumentMapper() {
        return (rs, rowNum) -> new InstrumentSymbol(
                rs.getString("symbol"),
                rs.getString("base_asset"),
                rs.getString("quote_asset"),
                rs.getString("settle_asset"));
    }
}
