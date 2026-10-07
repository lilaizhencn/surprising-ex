package com.surprising.gateway.provider.service;

import com.surprising.asset.model.AssetConfiguration.*;
import com.surprising.asset.repository.AssetRepository;
import com.surprising.asset.repository.AssetNetworkRepository;
import java.util.*;
import org.springframework.stereotype.Service;

/** Admin configuration controls visibility; custody owns supported chains and user addresses. */
@Service
public class WalletFundingService {
    private final AssetRepository assets;
    private final AssetNetworkRepository networks;
    private final CustodyWalletClient custody;

    public WalletFundingService(AssetRepository assets, AssetNetworkRepository networks, CustodyWalletClient custody) {
        this.assets = assets;
        this.networks = networks;
        this.custody = custody;
    }

    public List<FundingAsset> catalog(String operation) {
        if (!Set.of("deposit", "withdraw").contains(operation)) throw new IllegalArgumentException("invalid funding operation");
        var chains = custody.chains();
        var routes = networks.enabled();
        return assets.list(true).stream().map(asset -> new FundingAsset(asset.asset(), asset.displayName(), asset.logoUrl(),
                routes.stream().filter(n -> n.assetId() == asset.assetId())
                        .filter(n -> "deposit".equals(operation) ? n.depositEnabled() : n.withdrawalEnabled())
                        .filter(n -> chains.stream().anyMatch(c -> supports(c, asset.asset(), n.networkCode(), operation)))
                        .toList())).filter(a -> !a.networks().isEmpty()).toList();
    }

    public DepositAddress depositAddress(long userId, String assetCode, String networkCode) {
        if (userId <= 0) throw new IllegalArgumentException("authenticated user required");
        var asset = assets.byAccountingCode(assetCode).filter(Asset::listed)
                .orElseThrow(() -> new IllegalArgumentException("asset is not supported for deposits"));
        var route = networks.list(asset.assetId()).stream()
                .filter(n -> n.networkCode().equals(networkCode) && n.depositEnabled()).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("deposit network is disabled or does not belong to this asset"));
        if (custody.chains().stream().noneMatch(c -> supports(c, asset.asset(), route.networkCode(), "deposit"))) {
            throw new IllegalStateException("custody does not support this deposit network and asset");
        }
        // Custody atomically returns the existing address or allocates one for this stable tuple.
        var response = custody.createAddress(userId, route.networkCode(), 1L);
        if (response == null || !(response.get("address") instanceof String address) || address.isBlank()
                || !route.networkCode().equals(response.get("chain"))
                || !custody.subject(userId).equals(response.get("subject"))
                || !"ACTIVE".equals(response.get("status"))) {
            throw new IllegalStateException("custody returned an invalid or inactive deposit address");
        }
        return new DepositAddress(asset.asset(), route.networkCode(), address,
                Objects.toString(response.get("memo"), ""));
    }

    public void validateNetwork(int assetId, NetworkRequest request) {
        if (request == null) throw new IllegalArgumentException("network configuration is required");
        if (!request.depositEnabled() && !request.withdrawalEnabled()) return;
        var asset = assets.find(assetId).orElseThrow(() -> new IllegalArgumentException("asset not found"));
        var chains = custody.chains();
        for (String operation : List.of("deposit", "withdraw")) {
            if ((operation.equals("deposit") && request.depositEnabled()) || (operation.equals("withdraw") && request.withdrawalEnabled())) {
                if (chains.stream().noneMatch(c -> supports(c, asset.asset(), request.networkCode(), operation)))
                    throw new IllegalArgumentException("network or asset is not enabled in custody for " + operation);
            }
        }
    }

    private static boolean supports(Map<String, Object> chain, String asset, String network, String operation) {
        if (!network.equals(chain.get("chain")) || !Boolean.TRUE.equals(chain.get("enabled"))
                || !"ACTIVE".equals(chain.get("status"))) return false;
        if (!Boolean.TRUE.equals(chain.get(operation.equals("deposit") ? "scanEnabled" : "withdrawalEnabled"))) return false;
        return chain.get("assetSymbols") instanceof Collection<?> symbols && symbols.contains(asset);
    }

    public record FundingAsset(String asset, String displayName, String logoUrl, List<Network> networks) {}
    public record DepositAddress(String asset, String network, String address, String memo) {}
}
