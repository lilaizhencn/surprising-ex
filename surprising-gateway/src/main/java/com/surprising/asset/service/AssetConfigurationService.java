package com.surprising.asset.service;

import com.surprising.asset.model.AssetConfiguration.*;
import com.surprising.asset.repository.AssetConfigurationChangeRepository;
import com.surprising.asset.repository.AssetNetworkRepository;
import com.surprising.asset.repository.AssetRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 按币种锁串行修改基础配置和网络配置；配置与审计同事务提交。 */
@Service
public class AssetConfigurationService {
    private final AssetRepository assets;
    private final AssetNetworkRepository networks;
    private final AssetConfigurationChangeRepository changes;
    private final ObjectMapper json;

    public AssetConfigurationService(AssetRepository assets, AssetNetworkRepository networks,
                                     AssetConfigurationChangeRepository changes, ObjectMapper json) {
        this.assets = assets;
        this.networks = networks;
        this.changes = changes;
        this.json = json;
    }

    public List<Asset> list(boolean listedOnly) { return assets.list(listedOnly); }

    public List<Network> networks(int assetId) {
        requireAsset(assetId);
        return networks.list(assetId);
    }

    @Transactional
    public Asset save(AssetRequest request, long operatorId) {
        if (request == null) throw new IllegalArgumentException("configuration is required");
        requireOperatorAndReason(operatorId, request.reason());
        requireText(request.asset(), "asset", 20);
        if (!request.asset().matches("[A-Z0-9]{2,20}")) throw new IllegalArgumentException("invalid asset code");
        requireText(request.displayName(), "displayName", 100);
        if (request.logoUrl() == null || request.logoUrl().length() > 500
                || !(request.logoUrl().isEmpty() || request.logoUrl().startsWith("/assets/")
                || request.logoUrl().startsWith("https://"))) {
            throw new IllegalArgumentException("logoUrl must be an asset path or HTTPS URL");
        }
        requireScale(request.scaleUnits());
        if (!request.listed() && request.tradingEnabled()) throw new IllegalArgumentException("unlisted asset cannot enable trading");
        Asset before = null;
        if (request.assetId() != null) {
            before = assets.lock(request.assetId()).orElseThrow(() -> new IllegalArgumentException("asset not found"));
            requireRevision(request.revision(), before.revision());
            if (!before.asset().equals(request.asset()) || before.scaleUnits() != request.scaleUnits()) {
                throw new IllegalArgumentException("accounting code and scale cannot change for an existing asset");
            }
            if (!request.listed() && networks.list(before.assetId()).stream()
                    .anyMatch(n -> n.depositEnabled() || n.withdrawalEnabled())) {
                throw new IllegalStateException("disable deposit and withdrawal networks before unlisting the asset");
            }
        } else if (request.revision() != 0) {
            throw new IllegalArgumentException("new asset revision must be zero");
        }
        Asset after = assets.save(request);
        changes.append(after.assetId(), null, operatorId, request.reason(), encode(before), encode(after));
        return after;
    }

    @Transactional
    public Network saveNetwork(int assetId, NetworkRequest request, long operatorId) {
        if (request == null) throw new IllegalArgumentException("configuration is required");
        requireOperatorAndReason(operatorId, request.reason());
        Asset asset = assets.lock(assetId).orElseThrow(() -> new IllegalArgumentException("asset not found"));
        requireText(request.networkCode(), "networkCode", 32);
        if (!request.networkCode().matches("[A-Z0-9][A-Z0-9_-]{0,31}")) throw new IllegalArgumentException("invalid network code");
        requireText(request.displayName(), "displayName", 100);
        if (request.contractAddress() == null || request.contractAddress().length() > 128
                || !request.contractAddress().equals(request.contractAddress().trim())
                || (request.nativeAsset() != request.contractAddress().isEmpty())) {
            throw new IllegalArgumentException("native asset requires empty contract; token requires a contract address");
        }
        if (request.chainDecimals() < 0 || request.chainDecimals() > 36 || request.confirmations() <= 0) {
            throw new IllegalArgumentException("invalid chain decimals or confirmations");
        }
        if (!asset.listed() && (request.depositEnabled() || request.withdrawalEnabled())) {
            throw new IllegalStateException("unlisted asset cannot enable deposit or withdrawal");
        }
        requireAmount(request.minDeposit(), asset.scaleUnits(), request.chainDecimals(), false);
        requireAmount(request.minWithdrawal(), asset.scaleUnits(), request.chainDecimals(), false);
        requireAmount(request.withdrawalFee(), asset.scaleUnits(), request.chainDecimals(), true);
        if (request.withdrawalFee().compareTo(request.minWithdrawal()) >= 0) {
            throw new IllegalArgumentException("withdrawal fee must be below minimum withdrawal");
        }
        Network before = null;
        if (request.networkId() != null) {
            before = networks.lock(assetId, request.networkId())
                    .orElseThrow(() -> new IllegalArgumentException("network not found for asset"));
            requireRevision(request.revision(), before.revision());
            if (!before.networkCode().equals(request.networkCode())
                    || !Objects.equals(before.contractAddress(), request.contractAddress())
                    || before.nativeAsset() != request.nativeAsset() || before.chainDecimals() != request.chainDecimals()) {
                throw new IllegalArgumentException("network identity, contract and chain units cannot change");
            }
        } else if (request.revision() != 0) {
            throw new IllegalArgumentException("new network revision must be zero");
        }
        Network after = networks.save(assetId, request);
        changes.append(assetId, after.networkId(), operatorId, request.reason(), encode(before), encode(after));
        return after;
    }

    public long amountUnits(String accountingCode, String amount) {
        Asset asset = assets.byAccountingCode(accountingCode)
                .orElseThrow(() -> new IllegalArgumentException("asset not found"));
        try {
            BigDecimal value = new BigDecimal(amount);
            if (value.signum() <= 0) throw new IllegalArgumentException("amount must be positive");
            return value.multiply(BigDecimal.valueOf(asset.scaleUnits())).longValueExact();
        } catch (ArithmeticException | NumberFormatException | NullPointerException ex) {
            throw new IllegalArgumentException("amount is not an exact accounting unit amount", ex);
        }
    }

    private Asset requireAsset(int assetId) {
        return assets.find(assetId).orElseThrow(() -> new IllegalArgumentException("asset not found"));
    }

    private static void requireScale(long scale) {
        if (scale <= 0) throw new IllegalArgumentException("invalid accounting scale");
        while (scale > 1 && scale % 10 == 0) scale /= 10;
        if (scale != 1) throw new IllegalArgumentException("accounting scale must be a power of ten");
    }

    private static void requireAmount(BigDecimal value, long scale, int decimals, boolean zeroAllowed) {
        if (value == null || value.signum() < 0 || (!zeroAllowed && value.signum() == 0)) {
            throw new IllegalArgumentException("invalid network amount");
        }
        try {
            value.multiply(BigDecimal.valueOf(scale)).longValueExact();
            value.setScale(decimals, java.math.RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("network amount exceeds accounting range or asset/network precision", ex);
        }
    }

    private static void requireRevision(long expected, long current) {
        if (expected != current) throw new IllegalStateException("configuration changed; reload before saving");
    }

    private static void requireOperatorAndReason(long operatorId, String reason) {
        if (operatorId <= 0) throw new IllegalArgumentException("authenticated administrator is required");
        requireText(reason, "reason", 500);
    }

    private static void requireText(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max || !value.equals(value.trim())) {
            throw new IllegalArgumentException("invalid " + field);
        }
    }

    private String encode(Object value) { return value == null ? null : json.writeValueAsString(value); }
}
