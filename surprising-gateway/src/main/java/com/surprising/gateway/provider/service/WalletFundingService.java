package com.surprising.gateway.provider.service;

import com.surprising.gateway.provider.client.CustodyWalletClient;
import com.surprising.gateway.provider.repository.UserDepositAddressRepository;

import com.surprising.asset.model.AssetConfiguration.*;
import com.surprising.asset.repository.AssetRepository;
import com.surprising.asset.repository.AssetNetworkRepository;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Admin configuration controls visibility; local persistence caches addresses; custody owns supported chains and user addresses. */
@Service
public class WalletFundingService {
    private static final Logger log = LoggerFactory.getLogger(WalletFundingService.class);
    private final AssetRepository assets;
    private final AssetNetworkRepository networks;
    private final CustodyWalletClient custody;
    private final UserDepositAddressRepository addressRepository;
    private final java.util.function.LongSupplier nanoTime;
    private record CatalogCache(Map<String, List<FundingAsset>> byOperation, long loadedAt) {}
    private volatile CatalogCache catalogCache;
    private static final long CATALOG_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(5);

    public WalletFundingService(AssetRepository assets, AssetNetworkRepository networks, CustodyWalletClient custody) {
        this(assets, networks, custody, null);
    }

    @Autowired
    public WalletFundingService(AssetRepository assets, AssetNetworkRepository networks,
                                CustodyWalletClient custody,
                                @Autowired(required = false) UserDepositAddressRepository addressRepository) {
        this(assets, networks, custody, addressRepository, System::nanoTime);
    }

    WalletFundingService(AssetRepository assets, AssetNetworkRepository networks, CustodyWalletClient custody,
                         UserDepositAddressRepository addressRepository, java.util.function.LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
        this.assets = assets;
        this.networks = networks;
        this.custody = custody;
        this.addressRepository = addressRepository;
    }

    public List<Map<String, Object>> custodyChains() {
        return custody.chains();
    }

    public Map<String, Object> createChainAddress(long userId, String chain, Long requestedVersion) {
        long version = requestedVersion == null ? 1L : requestedVersion;
        return resolveOrCreateChainAddress(userId, chain, version, version, addressRepository, custody);
    }

    public static Map<String, Object> resolveOrCreateChainAddress(
            long userId,
            String chain,
            long cacheVersion,
            Long custodyVersion,
            UserDepositAddressRepository addressRepository,
            CustodyWalletClient walletClient) {
        if (addressRepository != null) {
            var cached = addressRepository.find(userId, chain, cacheVersion);
            if (cached.isPresent()) {
                var r = cached.get();
                return Map.of(
                        "address", r.address(),
                        "chain", r.networkCode(),
                        "subject", "user:" + userId,
                        "status", "ACTIVE",
                        "addressVersion", r.addressVersion(),
                        "memo", r.memo()
                );
            }
        }
        Map<String, Object> response = walletClient.createAddress(userId, chain, custodyVersion);
        if (response != null && addressRepository != null && response.get("address") instanceof String address && !address.isBlank()) {
            String memo = Objects.toString(response.get("memo"), "");
            String family = resolveChainFamily(walletClient, chain);
            addressRepository.save(userId, chain, family, address, memo, cacheVersion);
        }
        return response;
    }

    private static String resolveChainFamily(CustodyWalletClient walletClient, String chain) {
        try {
            List<Map<String, Object>> chains = walletClient.chains();
            if (chains != null) {
                for (var c : chains) {
                    if (chain.equals(c.get("chain")) && c.get("family") instanceof String f) {
                        return f.toLowerCase(Locale.ROOT);
                    }
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Failed to resolve custody chain family for {}, defaulting to generic", chain, ex);
        }
        return "generic";
    }

    private final java.util.concurrent.locks.ReentrantLock catalogLock = new java.util.concurrent.locks.ReentrantLock();

    public List<FundingAsset> catalog(String operation) {
        if (!Set.of("deposit", "withdraw").contains(operation)) throw new IllegalArgumentException("invalid funding operation");
        long now = nanoTime.getAsLong();
        var current = catalogCache;
        if (current != null && now - current.loadedAt() < CATALOG_NANOS) return current.byOperation().get(operation);
        catalogLock.lock();
        try {
            now = nanoTime.getAsLong();
            current = catalogCache;
            if (current != null && now - current.loadedAt() < CATALOG_NANOS) return current.byOperation().get(operation);
            var chains = Objects.requireNonNull(custody.chains(), "custody chains unavailable");
            var routes = networks.enabled();
            var configuredAssets = assets.list(false);
            var refreshed = Map.of("deposit", buildCatalog("deposit", configuredAssets, routes, chains),
                    "withdraw", buildCatalog("withdraw", configuredAssets, routes, chains));
            catalogCache = new CatalogCache(refreshed, now);
            return refreshed.get(operation);
        } finally {
            catalogLock.unlock();
        }
    }

    private static List<FundingAsset> buildCatalog(String operation, List<Asset> assets, List<Network> routes,
                                                   List<Map<String, Object>> chains) {
        return assets.stream()
                .filter(asset -> asset.listed() && asset.visible()
                        && ("deposit".equals(operation) ? asset.depositEnabled() : asset.withdrawalEnabled()))
                .map(asset -> {
            var supported = new ArrayList<FundingNetwork>();
            for (var route : routes) {
                if (route.assetId() != asset.assetId()
                        || !route.enabled()
                        || !("deposit".equals(operation) ? route.depositEnabled() : route.withdrawalEnabled())) continue;
                var chain = chains.stream().filter(c -> supports(c, asset.asset(), route.networkCode(), operation))
                        .findFirst().orElse(null);
                if (chain == null) continue;
                if (!(chain.get("family") instanceof String family) || family.isBlank()) {
                    throw new IllegalStateException("custody network family is unavailable");
                }
                supported.add(new FundingNetwork(route, family));
            }
            return new FundingAsset(asset.asset(), asset.displayName(), asset.aliasName(), asset.assetType(),
                    asset.logoUrl(), asset.displayDecimals(), asset.sortOrder(), List.copyOf(supported));
        }).filter(a -> !a.networks().isEmpty()).toList();
    }

    public DepositAddress depositAddress(long userId, String assetCode, String networkCode) {
        if (userId <= 0) throw new IllegalArgumentException("authenticated user required");
        var asset = assets.byAccountingCode(assetCode).filter(a -> a.listed() && a.depositEnabled())
                .orElseThrow(() -> new IllegalArgumentException("asset is not supported for deposits"));
        var route = networks.list(asset.assetId()).stream()
                .filter(n -> n.networkCode().equals(networkCode) && n.enabled() && n.depositEnabled()).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("deposit network is disabled or does not belong to this asset"));
        if (route.maintenanceMode()) {
            throw new IllegalArgumentException("deposit network is currently under maintenance");
        }

        // 1. 本地持久化查询（0 穿透）
        if (addressRepository != null) {
            var cached = addressRepository.find(userId, route.networkCode(), 1L);
            if (cached.isPresent()) {
                var record = cached.get();
                return new DepositAddress(asset.asset(), route.networkCode(), record.address(), record.memo());
            }
        }

        // 2. 本地未命中时，校验 Custody 链支持并在 Custody 端注册/分配地址
        var chains = custody.chains();
        var chainConfig = chains.stream().filter(c -> supports(c, asset.asset(), route.networkCode(), "deposit"))
                .findFirst().orElseThrow(() -> new IllegalStateException("custody does not support this deposit network and asset"));
        String family = chainConfig.get("family") instanceof String f ? f.toLowerCase(Locale.ROOT) : "generic";

        // Custody atomically returns the existing address or allocates one for this stable tuple.
        var response = custody.createAddress(userId, route.networkCode(), 1L);
        if (response == null || !(response.get("address") instanceof String address) || address.isBlank()
                || !route.networkCode().equals(response.get("chain"))
                || !custody.subject(userId).equals(response.get("subject"))
                || !"ACTIVE".equals(response.get("status"))) {
            throw new IllegalStateException("custody returned an invalid or inactive deposit address");
        }
        String memo = Objects.toString(response.get("memo"), "");

        // 3. 落库本地持久化，后续直接走本地查询
        if (addressRepository != null) {
            addressRepository.save(userId, route.networkCode(), family, address, memo, 1L);
        }

        return new DepositAddress(asset.asset(), route.networkCode(), address, memo);
    }

    public void validateNetwork(int assetId, NetworkRequest request) {
        if (request == null) throw new IllegalArgumentException("network configuration is required");
        if (!Boolean.TRUE.equals(request.enabled()) || (!request.depositEnabled() && !request.withdrawalEnabled())) return;
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

    public record FundingAsset(String asset, String displayName, String aliasName, String assetType,
                               String logoUrl, int displayDecimals, int sortOrder,
                               List<FundingNetwork> networks) {
        public FundingAsset(String asset, String displayName, String logoUrl, List<FundingNetwork> networks) {
            this(asset, displayName, "", "CRYPTO", logoUrl, 4, 100, networks);
        }
    }
    /** Preserve the configured network JSON fields and add custody's canonical address family. */
    public record FundingNetwork(@com.fasterxml.jackson.annotation.JsonUnwrapped Network configuration, String family) {}
    public record DepositAddress(String asset, String network, String address, String memo) {}
}
