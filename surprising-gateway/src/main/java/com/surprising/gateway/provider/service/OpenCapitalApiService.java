package com.surprising.gateway.provider.service;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.ProductTransferRequest;
import com.surprising.account.api.model.ProductTransferResponse;
import com.surprising.account.provider.service.AccountService;
import com.surprising.asset.model.AssetConfiguration.Asset;
import com.surprising.asset.model.AssetConfiguration.Network;
import com.surprising.asset.repository.AssetNetworkRepository;
import com.surprising.asset.repository.AssetRepository;
import com.surprising.gateway.provider.auth.model.ComplianceModels.KycProfile;
import com.surprising.gateway.provider.auth.service.ComplianceService;
import com.surprising.gateway.provider.auth.service.SensitiveActionVerificationService;
import com.surprising.gateway.provider.client.CustodyWalletClient;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.repository.UserDepositAddressRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/**
 * 公开 Open API 资金与充提端点处理（transfer、deposit、capital/config、withdraw）。
 */
@org.springframework.stereotype.Service
public class OpenCapitalApiService {

    private final GatewayProperties properties;
    private final AccountService accountService;
    private final SensitiveActionVerificationService verificationService;
    private final ComplianceService complianceService;
    private final CustodyWalletClient custodyWalletClient;
    private final CustodyWithdrawalService withdrawalService;
    private final UserDepositAddressRepository depositAddressRepository;
    private final AssetRepository assetRepository;
    private final AssetNetworkRepository networkRepository;
    private final OpenApiSupport support;

    public OpenCapitalApiService(GatewayProperties properties,
                                 AccountService accountService,
                                 SensitiveActionVerificationService verificationService,
                                 ComplianceService complianceService,
                                 CustodyWalletClient custodyWalletClient,
                                 CustodyWithdrawalService withdrawalService,
                                 @Autowired(required = false) UserDepositAddressRepository depositAddressRepository,
                                 OpenApiSupport support) {
        this(properties, accountService, verificationService, complianceService, custodyWalletClient,
                withdrawalService, depositAddressRepository, null, null, support);
    }

    @Autowired
    public OpenCapitalApiService(GatewayProperties properties,
                                 @Autowired(required = false) AccountService accountService,
                                 SensitiveActionVerificationService verificationService,
                                 ComplianceService complianceService,
                                 CustodyWalletClient custodyWalletClient,
                                 CustodyWithdrawalService withdrawalService,
                                 @Autowired(required = false) UserDepositAddressRepository depositAddressRepository,
                                 @Autowired(required = false) AssetRepository assetRepository,
                                 @Autowired(required = false) AssetNetworkRepository networkRepository,
                                 OpenApiSupport support) {
        this.properties = properties;
        this.accountService = accountService;
        this.verificationService = verificationService;
        this.complianceService = complianceService;
        this.custodyWalletClient = custodyWalletClient;
        this.withdrawalService = withdrawalService;
        this.depositAddressRepository = depositAddressRepository;
        this.assetRepository = assetRepository;
        this.networkRepository = networkRepository;
        this.support = support;
    }

    public ResponseEntity<byte[]> transfer(HttpServletRequest request, byte[] body) {
        Map<String, Object> params = support.parameters(request, body);
        long userId = support.authenticate(request, "TRADE", body);
        String type = support.required(params, "type").toUpperCase(Locale.ROOT);
        String asset = support.required(params, "asset");
        GatewayProperties.SymbolScale scale = properties.getBinanceApi().scale(asset);
        String idempotencyKey = support.first(params, "clientTranId", "clientOrderId");
        if (idempotencyKey == null) {
            idempotencyKey = request.getHeader("Idempotency-Key");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("clientTranId or Idempotency-Key is required");
        }
        ProductTransferRequest transferRequest = new ProductTransferRequest(
                userId,
                AccountType.valueOf(transferAccount(type, true)),
                AccountType.valueOf(transferAccount(type, false)),
                asset.toUpperCase(Locale.ROOT),
                support.decimalUnits(support.required(params, "amount"), scale.getQuantityScale()),
                idempotencyKey.trim(),
                "binance asset transfer"
        );
        ProductTransferResponse result = accountService.transfer(transferRequest);
        return support.json(HttpStatus.OK, Map.of("tranId", result.transferId()));
    }

    public ResponseEntity<byte[]> depositAddress(HttpServletRequest request) {
        long userId = support.authenticate(request, "READ");
        String chain = support.requiredParameter(request, "network");
        String coin = support.requiredParameter(request, "coin");
        Map<String, Object> result = WalletFundingService.resolveOrCreateChainAddress(
                userId, chain, 1L, null, depositAddressRepository, custodyWalletClient);
        return support.json(HttpStatus.OK, Map.of(
                "coin", coin,
                "address", result == null ? "" : support.stringValue(result.get("address")),
                "tag", result == null ? "" : support.stringValue(result.get("memo")),
                "url", ""
        ));
    }

    public ResponseEntity<byte[]> depositHistory(HttpServletRequest request) {
        long userId = support.authenticate(request, "READ");
        String chain = request.getParameter("network");
        String asset = request.getParameter("coin");
        return support.json(HttpStatus.OK, custodyWalletClient.deposits(userId, chain, asset,
                support.capped(request.getParameter("limit"))));
    }

    public ResponseEntity<byte[]> capitalConfig(HttpServletRequest request) {
        support.authenticate(request, "READ");
        GatewayProperties.CustodyWallet wallet = properties.getCustodyWallet();
        if (!wallet.isEnabled()) {
            return support.json(HttpStatus.OK, List.of());
        }
        List<Map<String, Object>> custodyChains = custodyWalletClient.chains();
        if (assetRepository != null && networkRepository != null) {
            List<Asset> dbAssets = assetRepository.list(false);
            if (!dbAssets.isEmpty()) {
                List<Network> enabledRoutes = networkRepository.enabled();
                List<Map<String, Object>> items = new ArrayList<>();
                for (Asset asset : dbAssets) {
                    if (!asset.listed() || !asset.visible()) continue;
                    List<Map<String, Object>> networkList = new ArrayList<>();
                    for (Network route : enabledRoutes) {
                        if (route.assetId() != asset.assetId()) continue;
                        Map<String, Object> chain = custodyChains.stream()
                                .filter(c -> route.networkCode().equals(c.get("chain"))
                                        && Boolean.TRUE.equals(c.get("enabled"))
                                        && c.get("assetSymbols") instanceof Collection<?> symbols
                                        && symbols.contains(asset.asset()))
                                .findFirst().orElse(null);
                        if (chain == null) continue;
                        Map<String, Object> net = new LinkedHashMap<>();
                        net.put("coin", asset.asset());
                        net.put("network", route.networkCode());
                        net.put("name", route.displayName());
                        net.put("tokenStandard", route.tokenStandard());
                        net.put("isDefault", route.isDefault());
                        net.put("depositEnable", asset.depositEnabled() && route.depositEnabled()
                                && Boolean.TRUE.equals(chain.get("scanEnabled")) && !route.maintenanceMode());
                        net.put("withdrawEnable", asset.withdrawalEnabled() && route.withdrawalEnabled()
                                && Boolean.TRUE.equals(chain.get("withdrawalEnabled")) && !route.maintenanceMode());
                        net.put("busy", route.maintenanceMode());
                        net.put("withdrawFee", route.withdrawalFee().stripTrailingZeros().toPlainString());
                        net.put("withdrawMin", route.minWithdrawal().stripTrailingZeros().toPlainString());
                        net.put("withdrawMax", route.maxWithdrawal() == null ? "0" : route.maxWithdrawal().stripTrailingZeros().toPlainString());
                        net.put("depositMin", route.minDeposit().stripTrailingZeros().toPlainString());
                        net.put("minConfirm", route.confirmations());
                        net.put("unLockConfirm", route.unlockConfirmations());
                        net.put("sameAddress", route.memoRequired());
                        net.put("addressRegex", route.addressRegex());
                        net.put("memoRegex", route.memoRegex());
                        net.put("contractAddress", route.contractAddress());
                        net.put("estimatedArrivalTime", route.estimatedArrivalMinutes());
                        net.put("specialTips", route.specialTips());
                        networkList.add(net);
                    }
                    if (!networkList.isEmpty()) {
                        Map<String, Object> coin = new LinkedHashMap<>();
                        coin.put("coin", asset.asset());
                        coin.put("name", asset.displayName());
                        coin.put("trading", asset.tradingEnabled());
                        coin.put("depositAllEnable", asset.depositEnabled());
                        coin.put("withdrawAllEnable", asset.withdrawalEnabled());
                        coin.put("networkList", networkList);
                        items.add(coin);
                    }
                }
                return support.json(HttpStatus.OK, items);
            }
        }
        Map<String, List<Map<String, Object>>> networksByAsset = new LinkedHashMap<>();
        for (Map<String, Object> chain : custodyChains) {
            if (!Boolean.TRUE.equals(chain.get("enabled"))) {
                continue;
            }
            String network = support.stringValue(chain.get("chain"));
            if (network.isBlank()) {
                continue;
            }
            boolean withdrawEnabled = Boolean.TRUE.equals(chain.get("withdrawalEnabled"));
            Object assets = chain.get("assetSymbols");
            if (!(assets instanceof List<?> assetList)) {
                continue;
            }
            for (Object asset : assetList) {
                String normalizedAsset = String.valueOf(asset).trim().toUpperCase(Locale.ROOT);
                if (normalizedAsset.isBlank()) {
                    continue;
                }
                List<Map<String, Object>> networks = networksByAsset.computeIfAbsent(
                        normalizedAsset, ignored -> new ArrayList<>());
                networks.add(Map.of(
                        "coin", normalizedAsset,
                        "network", network.toUpperCase(Locale.ROOT),
                        "depositEnable", true,
                        "withdrawEnable", withdrawEnabled,
                        "isDefault", networks.isEmpty()));
            }
        }
        List<Map<String, Object>> result = networksByAsset.entrySet().stream()
                .map(entry -> Map.<String, Object>of(
                        "coin", entry.getKey(),
                        "name", entry.getKey(),
                        "networkList", entry.getValue()))
                .toList();
        return support.json(HttpStatus.OK, result);
    }

    public ResponseEntity<byte[]> withdrawQuote(HttpServletRequest request, byte[] body) {
        support.authenticate(request, "WITHDRAW", body);
        var params = support.parameters(request, body);
        return support.json(HttpStatus.OK, withdrawalService.quote(
                support.required(params, "coin"),
                support.required(params, "network"),
                support.required(params, "amount")));
    }

    public ResponseEntity<byte[]> withdraw(HttpServletRequest request, byte[] body) {
        Map<String, Object> params = support.parameters(request, body);
        boolean apiKeyRequest = request.getHeader("X-MBX-APIKEY") != null
                && !request.getHeader("X-MBX-APIKEY").isBlank();
        long userId = support.authenticate(request, "WITHDRAW", body);
        String asset = support.required(params, "coin");
        String chain = support.required(params, "network");
        String idempotencyKey = support.first(params, "withdrawOrderId", "clientOrderId");
        if (idempotencyKey == null) {
            idempotencyKey = request.getHeader("Idempotency-Key");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("withdrawOrderId or Idempotency-Key is required");
        }
        String amount = support.required(params, "amount");
        String revision = support.required(params, "networkRevision");
        var intent = new CustodyWithdrawalService.WithdrawalRequest(null, chain, asset, support.required(params, "address"), amount, null, Long.parseLong(revision));
        var result = withdrawalService.replay(userId, idempotencyKey, intent);
        if (result == null) {
            requireWithdrawalSecurity(request, userId, apiKeyRequest ? "API_WITHDRAWAL" : "WITHDRAWAL");
            requireKyc(userId);
            complianceService.requireWithdrawalEligibility(userId);
            result = withdrawalService.submit(userId, idempotencyKey, intent);
        }
        return support.json(HttpStatus.OK, Map.of("id", result.walletWithdrawalId() == null
                        ? result.withdrawalId().toString() : result.walletWithdrawalId(),
                "withdrawalId", result.withdrawalId().toString(), "status", result.status(),
                "msg", "success", "success", true, "amount", result.amount(), "platformFee", result.fee(), "receivedAmount", result.receivedAmount()));
    }

    public ResponseEntity<byte[]> withdrawHistory(HttpServletRequest request) {
        long userId = support.authenticate(request, "READ");
        return support.json(HttpStatus.OK, withdrawalService.history(userId, request.getParameter("network"),
                request.getParameter("coin"), support.capped(request.getParameter("limit"))));
    }

    private void requireKyc(long userId) {
        KycProfile profile = complianceService.kyc(userId);
        if (!isKycVerified(profile)) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "verified KYC is required for withdrawals");
        }
    }

    private boolean isKycVerified(KycProfile profile) {
        return profile != null && "VERIFIED".equalsIgnoreCase(profile.status())
                && (profile.expiresAt() == null || !profile.expiresAt().isBefore(Instant.now()));
    }

    private void requireWithdrawalSecurity(HttpServletRequest request, long userId, String scene) {
        if (!verificationService.verify(userId, scene,
                request.getHeader("X-Security-Email-Code"),
                request.getHeader("X-Security-TOTP-Code"), Instant.now())) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "security verification is required or invalid");
        }
    }

    private String transferAccount(String type, boolean source) {
        return switch (type) {
            case "MAIN_UMFUTURE" -> source ? "FUNDING" : "USDT_PERPETUAL";
            case "UMFUTURE_MAIN" -> source ? "USDT_PERPETUAL" : "FUNDING";
            case "MAIN_CM" -> source ? "FUNDING" : "COIN_PERPETUAL";
            case "CM_MAIN" -> source ? "COIN_PERPETUAL" : "FUNDING";
            case "MAIN_CMFUTURE" -> source ? "FUNDING" : "COIN_PERPETUAL";
            case "CMFUTURE_MAIN" -> source ? "COIN_PERPETUAL" : "FUNDING";
            case "MAIN_SPOT" -> source ? "FUNDING" : "SPOT";
            case "SPOT_MAIN" -> source ? "SPOT" : "FUNDING";
            default -> throw new IllegalArgumentException("unsupported transfer type");
        };
    }
}
