package com.surprising.gateway.c2c.service;

import com.surprising.gateway.c2c.repository.C2cFundsRepository;
import com.surprising.gateway.c2c.model.C2cConfiguration;
import com.surprising.gateway.c2c.model.C2cMarket;
import com.surprising.gateway.c2c.repository.C2cConfigurationRepository;
import com.surprising.gateway.c2c.repository.C2cMarketRepository;
import com.surprising.gateway.c2c.repository.C2cMerchantBondRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 聚合市场开放、质押准入及资金意图身份校验；配置每次读取已提交事实，不另设缓存。 */
@Service
public class C2cMarketService {
    private final C2cConfigurationRepository configuration;
    private final C2cMarketRepository markets;
    private final C2cMerchantBondRepository bonds;

    public C2cMarketService(C2cConfigurationRepository configuration, C2cMarketRepository markets,
                            C2cMerchantBondRepository bonds) {
        this.configuration = configuration;
        this.markets = markets;
        this.bonds = bonds;
    }

    public C2cConfiguration configuration() { return configuration(false); }
    public C2cConfiguration configuration(boolean lock) { return configuration.get(lock); }
    public List<C2cMarket> markets(boolean activeOnly) { return markets.list(activeOnly); }

    public C2cMarket market(UUID id, boolean requireActive) {
        return markets.find(id, requireActive)
                .orElseThrow(() -> new IllegalArgumentException("C2C 市场不存在或尚未开放"));
    }

    public boolean validMerchantBondIntent(C2cFundsRepository.Intent intent) {
        boolean reserve = intent.purpose().equals("MERCHANT_BOND");
        long owner = reserve ? intent.sourceUserId() : intent.targetUserId();
        long escrow = reserve ? intent.targetUserId() : intent.sourceUserId();
        return escrow == configuration().escrowUserId()
                && bonds.matches(intent.ownerId(), intent.asset(), intent.amountUnits(), owner);
    }

    public void requireMerchantBond(long userId) {
        if (!bonds.coversCurrentPolicy(userId)) throw new IllegalStateException("商家质押尚未确认，请先完成质押");
    }

    public void requireOpen() {
        if (!configuration().enabled()) throw new IllegalStateException("C2C 暂未开放");
    }
}
