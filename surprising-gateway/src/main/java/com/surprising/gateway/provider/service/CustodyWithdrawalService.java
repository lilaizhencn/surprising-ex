package com.surprising.gateway.provider.service;

import com.surprising.account.api.model.BalanceAdjustmentRequest;
import com.surprising.account.provider.exception.AccountCommandRejectedException;
import com.surprising.account.provider.service.AccountCommandGateway;
import com.surprising.gateway.provider.client.CustodyWalletClient;
import com.surprising.gateway.provider.client.WithdrawalValuationClient;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.product.api.ProductLine;

import com.surprising.gateway.provider.model.CustodyWithdrawalModels;
import lombok.extern.slf4j.Slf4j;

import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.repository.CustodyWithdrawalRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
@Slf4j
public class CustodyWithdrawalService {


    private final GatewayProperties properties;
    private final CustodyWithdrawalRepository repository;
    private final CustodyWithdrawalStateService states;
    private final CustodyWalletClient walletClient;
    private final GatewayProductServices products;
    private final WithdrawalValuationClient valuationClient;
    private final CustodyWithdrawalRefundService refundService;
    private final CustodyWithdrawalReconciliationService reconciliationService;
    private final ObjectMapper objectMapper;
    private final com.surprising.asset.service.AssetConfigurationService assets;
    private final com.surprising.gateway.provider.reconciliation.EmergencyWithdrawalCircuitBreaker circuitBreaker;
    private final CustodyWithdrawalWebhookHandler webhookHandler;

    public CustodyWithdrawalService(GatewayProperties properties,
                                    CustodyWithdrawalRepository repository,
                                    CustodyWithdrawalStateService states,
                                    CustodyWalletClient walletClient,
                                    GatewayProductServices products,
                                    WithdrawalValuationClient valuationClient,
                                    CustodyWithdrawalRefundService refundService,
                                    CustodyWithdrawalReconciliationService reconciliationService,
                                    ObjectMapper objectMapper, com.surprising.asset.service.AssetConfigurationService assets) {
        this(properties, repository, states, walletClient, products, valuationClient,
                refundService, reconciliationService, objectMapper, assets, null);
    }

    public CustodyWithdrawalService(GatewayProperties properties,
                                    CustodyWithdrawalRepository repository,
                                    CustodyWithdrawalStateService states,
                                    CustodyWalletClient walletClient,
                                    GatewayProductServices products,
                                    WithdrawalValuationClient valuationClient,
                                    CustodyWithdrawalRefundService refundService,
                                    CustodyWithdrawalReconciliationService reconciliationService,
                                    ObjectMapper objectMapper,
                                    com.surprising.asset.service.AssetConfigurationService assets,
                                    com.surprising.gateway.provider.reconciliation.EmergencyWithdrawalCircuitBreaker circuitBreaker) {
        this(properties, repository, states, walletClient, products, valuationClient,
                refundService, reconciliationService, objectMapper, assets, circuitBreaker, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CustodyWithdrawalService(GatewayProperties properties,
                                    CustodyWithdrawalRepository repository,
                                    CustodyWithdrawalStateService states,
                                    CustodyWalletClient walletClient,
                                    GatewayProductServices products,
                                    WithdrawalValuationClient valuationClient,
                                    CustodyWithdrawalRefundService refundService,
                                    CustodyWithdrawalReconciliationService reconciliationService,
                                    ObjectMapper objectMapper,
                                    com.surprising.asset.service.AssetConfigurationService assets,
                                    com.surprising.gateway.provider.reconciliation.EmergencyWithdrawalCircuitBreaker circuitBreaker,
                                    CustodyWithdrawalWebhookHandler webhookHandler) {
        this.properties = properties;
        this.assets = assets;
        this.repository = repository;
        this.states = states;
        this.walletClient = walletClient;
        this.products = products;
        this.valuationClient = valuationClient;
        this.refundService = refundService;
        this.reconciliationService = reconciliationService;
        this.objectMapper = com.surprising.gateway.provider.support.JsonMappers.orDefault(objectMapper);
        this.circuitBreaker = circuitBreaker;
        this.webhookHandler = webhookHandler != null
                ? webhookHandler
                : new CustodyWithdrawalWebhookHandler(states, this.objectMapper);
    }

    public com.surprising.asset.service.AssetConfigurationService.WithdrawalQuote quote(String asset, String chain, String amount) {
        if (asset == null || chain == null || amount == null) throw new IllegalArgumentException("withdrawal quote fields are required");
        return assets.withdrawalQuote(asset.trim().toUpperCase(Locale.ROOT),chain.trim().toUpperCase(Locale.ROOT),amount.trim());
    }

    public WithdrawalResponse submit(long userId, String idempotencyKey, WithdrawalRequest request) {
        if (circuitBreaker != null && !circuitBreaker.isWithdrawalAllowed()) {
            throw new IllegalStateException("🚨 平台资金风控熔断：提现通道已紧急暂停！原因: " + circuitBreaker.getStopReason());
        }
        validateInput(userId, idempotencyKey, request);
        String normalizedKey = idempotencyKey.trim();
        String requestHash = sha256(canonical(request));
        CustodyWithdrawalModels.WithdrawalRecord existing = repository.findByUserAndIdempotency(userId, normalizedKey);
        if (existing != null) {
            requireSameRequest(existing, requestHash);
            return response(existing);
        }
        var quote = assets.withdrawalQuote(request.assetSymbol().trim().toUpperCase(Locale.ROOT),
                request.chain().trim().toUpperCase(Locale.ROOT), request.amount().trim());
        if (request.networkRevision() == null || request.networkRevision() != quote.networkRevision()) {
            throw new IllegalArgumentException("withdrawal fees or limits changed; request a new quote before confirming");
        }
        var routeOpt = assets.findRoute(quote.asset(), quote.chain());
        if (routeOpt.isPresent()) {
            var route = routeOpt.get();
            if (!route.addressRegex().isBlank()) {
                try {
                    if (!java.util.regex.Pattern.compile(route.addressRegex())
                            .matcher(request.toAddress().trim()).matches()) {
                        throw new IllegalArgumentException("invalid withdrawal destination address format for " + quote.chain());
                    }
                } catch (java.util.regex.PatternSyntaxException ignored) {
                    // Validated at asset configuration save time.
                }
            }
        }
        BigDecimal usdtValue = valuationClient.toUsdt(quote.asset(), new BigDecimal(quote.amount()));
        boolean requiresApproval = usdtValue.compareTo(properties.getWithdrawal().getSingleApprovalThresholdUsdt()) >= 0;
        BigDecimal dailyLimitUsdt = properties.getWithdrawal().getDailyLimitUsdt();
        if (routeOpt.isPresent()) {
            var route = routeOpt.get();
            if (!route.autoWithdrawEnabled()) {
                requiresApproval = true;
            }
            if (route.autoAuditMaxAmount() != null
                    && new BigDecimal(quote.amount()).compareTo(route.autoAuditMaxAmount()) >= 0) {
                requiresApproval = true;
            }
            if (route.dailyMaxWithdrawal() != null && route.dailyMaxWithdrawal().signum() > 0) {
                BigDecimal networkDailyUsdt = valuationClient.toUsdt(quote.asset(), route.dailyMaxWithdrawal());
                if (networkDailyUsdt != null && networkDailyUsdt.signum() > 0) {
                    dailyLimitUsdt = dailyLimitUsdt.min(networkDailyUsdt);
                }
            }
        }
        String externalReference = "custody-wallet-withdrawal:" + sha256(userId + ":" + normalizedKey).substring(0, 32);
        // Core authorizes the user's entitlement; custody reserves tenant principal independently.
        var normalized = new WithdrawalRequest(null, quote.chain(), quote.asset(),
                request.toAddress().trim(), quote.amount(), request.externalReference(), request.networkRevision());
        CustodyWithdrawalModels.CreateResult result = states.createOrGet(
                new CustodyWithdrawalModels.CreateRequest(userId, normalizedKey, requestHash, quote.chain(), quote.asset(),
                        null, normalized.toAddress(), quote.amount(), quote.amountUnits(), usdtValue,
                        externalReference, externalReference, payload(normalized, externalReference),
                        requiresApproval,
                        dailyLimitUsdt,
                        new CustodyWithdrawalModels.FeeSnapshot(quote.fee(), quote.feeUnits(), quote.receivedAmount(),
                                quote.receivedUnits(), quote.networkId(), quote.networkRevision()), "TENANT_POOL"));
        CustodyWithdrawalModels.WithdrawalRecord record = result.record();
        if ("PENDING_APPROVAL".equals(record.status()) || terminal(record.status())) {
            return response(record);
        }

        return continueSubmission(record);
    }

    /** Authenticated replay never consumes another OTP, revalues risk or selects a different source. */
    public WithdrawalResponse replay(long userId, String key, WithdrawalRequest request) {
        validateInput(userId, key, request);
        var existing = repository.findByUserAndIdempotency(userId, key.trim());
        if (existing == null) return null;
        requireSameRequest(existing, sha256(canonical(request)));
        return response(existing);
    }

    private void requireSameRequest(CustodyWithdrawalModels.WithdrawalRecord existing, String hash) {
        if (!existing.requestHash().equals(hash)) {
            throw new IllegalArgumentException("idempotency key was reused with a different withdrawal request");
        }
    }

    public WithdrawalResponse submission(long userId, String reference) {
        if (userId <= 0 || reference == null || !reference.matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new IllegalArgumentException("withdrawal reference is invalid");
        }
        var record = repository.findByUserAndIdempotency(userId,reference);
        return record == null ? null : response(record);
    }

    public WithdrawalResponse approve(UUID withdrawalId, long adminUserId, String adminUsername, String reason) {
        CustodyWithdrawalModels.WithdrawalRecord record = states.approve(
                withdrawalId, adminUserId, adminUsername, reason);
        return continueSubmission(record);
    }

    public WithdrawalResponse reject(UUID withdrawalId, long adminUserId, String adminUsername, String reason) {
        return response(states.reject(withdrawalId, adminUserId, adminUsername, reason));
    }

    public WithdrawalResponse retry(UUID withdrawalId, long adminUserId, String adminUsername, String reason) {
        return continueSubmission(states.recordAdminRetry(withdrawalId, adminUserId, adminUsername, reason));
    }

    public List<Map<String, Object>> adminList(String status, int limit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CustodyWithdrawalModels.WithdrawalRecord record : repository.list(status, limit)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("withdrawalId", record.withdrawalId());
            row.put("userId", record.userId());
            row.put("idempotencyKey", record.idempotencyKey());
            row.put("chain", record.chain());
            row.put("assetSymbol", record.assetSymbol());
            row.put("custodyAddressId", record.custodyAddressId());
            row.put("fundingSource", record.fundingSource());
            row.put("subject", "user:" + record.userId());
            row.put("toAddress", record.toAddress());
            row.put("amount", record.amount());
            row.put("amountUnits", record.amountUnits());
            row.put("fee", record.fees().fee());
            row.put("receivedAmount", record.fees().receivedAmount());
            row.put("networkRevision", record.fees().networkRevision());
            row.put("usdtValue", record.usdtValue());
            row.put("externalReference", record.externalReference());
            row.put("status", record.status());
            row.put("walletResponse", record.walletResponse());
            row.put("walletWithdrawalId", record.walletWithdrawalId());
            row.put("errorCode", record.errorCode());
            row.put("errorMessage", record.errorMessage());
            row.put("createdAt", record.createdAt());
            row.put("updatedAt", record.updatedAt());
            row.put("submittedAt", record.submittedAt());
            row.put("completedAt", record.completedAt());
            row.put("adminUserId", record.adminUserId());
            row.put("adminUsername", record.adminUsername());
            row.put("adminReason", record.adminReason());
            rows.add(row);
        }
        return rows;
    }

    public java.util.List<Map<String, Object>> history(long userId, String chain, String asset, int limit) {
        java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
        boolean custodyUnavailable = false;
        try {
            for (Map<String, Object> item : walletClient.withdrawals(userId, chain, asset, limit)) {
                rows.add(new LinkedHashMap<>(item));
            }
        } catch (IllegalStateException ex) {
            custodyUnavailable = true;
        }
        for (CustodyWithdrawalModels.WithdrawalRecord record
                : repository.listForUser(userId, chain, asset, limit)) {
            Map<String, Object> matched = null;
            for (Map<String, Object> row : rows) {
                String walletId = stringValue(row.get("withdrawalId"), stringValue(row.get("id"), null));
                if ((record.walletWithdrawalId() != null && record.walletWithdrawalId().equals(walletId))
                        || (record.externalReference().equals(row.get("externalReference")) && matchesPayload(record,row))) {
                    matched = row;
                    break;
                }
            }
            if (matched == null) {
                matched = new LinkedHashMap<>();
                rows.add(matched);
            }
            matched.put("id", record.walletWithdrawalId() == null
                    ? record.withdrawalId().toString() : record.walletWithdrawalId());
            matched.put("gatewayWithdrawalId", record.withdrawalId().toString());
            matched.put("status", record.status());
            matched.put("chain", record.chain());
            matched.put("asset", record.assetSymbol());
            matched.put("amount", record.amount());
            matched.put("platformFee", record.fees().fee());
            matched.put("receivedAmount", record.fees().receivedAmount());
            matched.put("toAddress", record.toAddress());
            matched.put("externalReference", record.externalReference());
            matched.put("usdtValue", record.usdtValue());
            matched.put("errorMessage", record.errorMessage());
            matched.put("custodyWalletUnavailable", custodyUnavailable);
            matched.put("createdAt", record.createdAt());
            matched.put("updatedAt", record.updatedAt());
        }
        rows.sort(java.util.Comparator.comparing((Map<String,Object> row) -> historyTime(row.get("createdAt"))).reversed());
        return rows.stream().limit(Math.max(1,Math.min(limit,200))).toList();
    }

    private Instant historyTime(Object value) {
        if (value instanceof Instant instant) return instant;
        try { return Instant.parse(String.valueOf(value)); }
        catch (java.time.format.DateTimeParseException invalid) { return Instant.MIN; }
    }

    public void reconcileFailedWithdrawals() {
        for (CustodyWithdrawalModels.WithdrawalRecord record
                : repository.claimRecoverable(properties.getWithdrawal().getFailureReconciliationDelay(), properties.getCustodyWallet().getRequestTimeout().plusSeconds(30), 50)) {
            try {
                if (Set.of("SUBMITTED","BROADCAST_UNKNOWN","FAILED_PENDING").contains(record.status())) {
                    reconciliationService.reconcile(record);
                }
                var current = repository.find(record.withdrawalId());
                if (current != null && Set.of("PROCESSING","DEBIT_UNKNOWN","DEBITED","BROADCAST_UNKNOWN","REFUND_PENDING").contains(current.status())) {
                    continueSubmission(current);
                }
            } catch (RuntimeException ex) {
                log.warn("custody withdrawal failure reconciliation remains pending: {}", record.withdrawalId(), ex);
            }
        }
    }

    private WithdrawalResponse continueSubmission(CustodyWithdrawalModels.WithdrawalRecord record) {
        if (record == null) {
            throw new IllegalStateException("withdrawal intent does not exist");
        }
        if ("REFUND_PENDING".equals(record.status())) {
            refund(record, "custody wallet withdrawal refund retry", "custody wallet withdrawal refund retry");
            return response(repository.find(record.withdrawalId()));
        }
        if (terminal(record.status()) || "FAILED_PENDING".equals(record.status())) {
            return response(record);
        }

        if (!"DEBITED".equals(record.status()) && !"SUBMITTED".equals(record.status())
                && !"BROADCAST_UNKNOWN".equals(record.status())) {
            debit(record);
            record = states.markDebited(record.withdrawalId(), "custody wallet withdrawal");
        }
        if ("SUBMITTED".equals(record.status())) {
            return response(record);
        }
        Map<String, Object> walletResponse;
        try {
            walletResponse = walletClient.createWithdrawal(record.userId(),
                    withdrawalPayload(record), custodyIdempotencyKey(record));
        } catch (CustodyWalletClient.CustodyWalletRejectedException ex) {
            if (!ex.matches(custodyIdempotencyKey(record),withdrawalPayload(record))) {
                states.markBroadcastUnknown(record.withdrawalId(),"{}","custody refusal has no matching permanent receipt");
                throw new WithdrawalUnknownException("custody refusal identity is unverified",ex);
            }
            refund(record, "custody wallet withdrawal rejected refund", "custody wallet rejected withdrawal",
                    rejectionEvidence(ex));
            throw new WithdrawalRejectedException("custody wallet rejected withdrawal; funds were released", ex);
        } catch (RuntimeException ex) {
            try {
                states.markBroadcastUnknown(record.withdrawalId(), "{}", message(ex));
            } catch (IllegalStateException stateEx) {
                CustodyWithdrawalModels.WithdrawalRecord current = repository.find(record.withdrawalId());
                if (current != null && submissionOutcomeAdvanced(current.status())) {
                    return response(current);
                }
                throw stateEx;
            }
            throw new WithdrawalUnknownException("custody wallet withdrawal status is unknown", ex);
        }
        String walletWithdrawalId = walletWithdrawalId(walletResponse);
        String walletExternalReference = stringValue(walletResponse == null ? null
                        : walletResponse.get("externalReference"),
                stringValue(walletResponse == null ? null : walletResponse.get("external_reference"), null));
        if (walletWithdrawalId == null || walletExternalReference == null
                || !record.externalReference().equals(walletExternalReference) || !matchesPayload(record,walletResponse)) {
            try {
                states.markBroadcastUnknown(record.withdrawalId(), json(walletResponse),
                        "custody wallet response identity does not match local withdrawal", null);
            } catch (IllegalStateException stateEx) {
                CustodyWithdrawalModels.WithdrawalRecord current = repository.find(record.withdrawalId());
                if (current != null && submissionOutcomeAdvanced(current.status())) {
                    return response(current);
                }
                throw stateEx;
            }
            throw new WithdrawalUnknownException("custody wallet withdrawal identity is unknown", null);
        }
        try {
            record = states.markSubmitted(record.withdrawalId(), json(walletResponse), walletWithdrawalId);
            return response(record);
        } catch (IllegalStateException ex) {
            CustodyWithdrawalModels.WithdrawalRecord current = repository.find(record.withdrawalId());
            if (current != null && submissionOutcomeAdvanced(current.status())) {
                return response(current);
            }
            throw ex;
        }
    }

    private boolean submissionOutcomeAdvanced(String status) {
        return "SUBMITTED".equals(status) || "COMPLETED".equals(status)
                || "FAILED_PENDING".equals(status) || "REFUND_PENDING".equals(status)
                || "REFUNDED".equals(status);
    }

    private String walletWithdrawalId(Map<String, Object> walletResponse) {
        if (walletResponse == null) {
            return null;
        }
        return stringValue(walletResponse.get("withdrawalId"), stringValue(walletResponse.get("id"), null));
    }

    @Transactional
    public void handleWebhook(String eventType, Map<String, Object> event) {
        webhookHandler.handleWebhook(eventType, event);
    }

    @Transactional
    public void handleWebhook(String providerEventId, String eventType, Map<String, Object> event) {
        webhookHandler.handleWebhook(providerEventId, eventType, event);
    }

    private void debit(CustodyWithdrawalModels.WithdrawalRecord record) {
        try {
            adjustSpotBalance(products, record.userId(), record.assetSymbol(), -record.amountUnits(),
                    record.spotDebitReference(), "custody wallet withdrawal");
        } catch (AccountCommandRejectedException ex) {
            if ("IDEMPOTENCY_CONFLICT".equals(ex.errorCode())) {
                states.markDebitUnknown(record.withdrawalId(), "spot adjustment reference conflicts with an existing command");
                throw new WithdrawalUnknownException("spot adjustment outcome is unverified", ex);
            }
            states.markRejected(record.withdrawalId(), "SPOT_REJECTED", message(ex));
            throw new WithdrawalRejectedException("spot account rejected withdrawal", ex);
        } catch (RuntimeException ex) {
            states.markDebitUnknown(record.withdrawalId(), message(ex));
            throw new WithdrawalUnknownException("spot account debit status is unknown", ex);
        }
    }

    static void adjustSpotBalance(GatewayProductServices products, long userId, String asset,
                                  long amountUnits, String referenceId, String reason) {
        if (userId <= 0L || asset == null || asset.isBlank() || referenceId == null || referenceId.isBlank()) {
            throw new IllegalArgumentException("spot balance adjustment request is invalid");
        }
        products.service(ProductLine.SPOT, AccountCommandGateway.class).adjustBalance(
                new BalanceAdjustmentRequest(userId, asset.trim().toUpperCase(Locale.ROOT),
                        amountUnits, referenceId, reason == null ? "" : reason),
                null, null);
    }

    private void refund(CustodyWithdrawalModels.WithdrawalRecord record,
                        String spotReason,
                        String stateReason) {
        refund(record, spotReason, stateReason, null);
    }

    private void refund(CustodyWithdrawalModels.WithdrawalRecord record,
                        String spotReason,
                        String stateReason,
                        String walletResponse) {
        try {
            refundService.refund(record, spotReason, stateReason, walletResponse);
        } catch (RuntimeException ex) {
            states.markRefundPending(record.withdrawalId(), message(ex));
            throw new WithdrawalUnknownException("withdrawal refund status is unknown", ex);
        }
    }

    private String rejectionEvidence(CustodyWalletClient.CustodyWalletRejectedException ex) {
        return CustodyWithdrawalPayloadSupport.rejectionEvidence(objectMapper, ex);
    }

    private Map<String, Object> withdrawalPayload(CustodyWithdrawalModels.WithdrawalRecord record) {
        return CustodyWithdrawalPayloadSupport.withdrawalPayload(record);
    }

    private String payload(WithdrawalRequest request, String externalReference) {
        return CustodyWithdrawalPayloadSupport.payload(objectMapper, request, externalReference);
    }

    private String canonical(WithdrawalRequest request) {
        return CustodyWithdrawalPayloadSupport.canonical(objectMapper, request);
    }

    private String json(Object value) {
        return CustodyWithdrawalPayloadSupport.json(objectMapper, value);
    }

    private String stringValue(Object value, String fallback) {
        return CustodyWithdrawalPayloadSupport.stringValue(value, fallback);
    }

    private boolean matchesPayload(CustodyWithdrawalModels.WithdrawalRecord record, Map<String, Object> payload) {
        return CustodyWithdrawalPayloadSupport.matchesPayload(record, payload);
    }

    private boolean terminal(String status) {
        return CustodyWithdrawalPayloadSupport.terminal(status);
    }

    private void validateInput(long userId, String idempotencyKey, WithdrawalRequest request) {
        CustodyWithdrawalPayloadSupport.validateInput(userId, idempotencyKey, request);
    }

    private String sha256(String value) {
        return CustodyWithdrawalPayloadSupport.sha256(value);
    }

    private String message(Throwable throwable) {
        return CustodyWithdrawalPayloadSupport.message(throwable);
    }

    private String custodyIdempotencyKey(CustodyWithdrawalModels.WithdrawalRecord record) {
        return CustodyWithdrawalPayloadSupport.custodyIdempotencyKey(record);
    }

    private WithdrawalResponse response(CustodyWithdrawalModels.WithdrawalRecord record) {
        return CustodyWithdrawalPayloadSupport.response(record);
    }

    public record WithdrawalRequest(UUID custodyAddressId, String chain, String assetSymbol, String toAddress,
                                    String amount, String externalReference, Long networkRevision) {
    }

    public record WithdrawalResponse(UUID withdrawalId, String status, String walletWithdrawalId,
                                     BigDecimal usdtValue, Instant createdAt, Instant updatedAt, String amount, String fee, String receivedAmount, long networkRevision) {
    }

    public static class WithdrawalRejectedException extends IllegalStateException {
        public WithdrawalRejectedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class WithdrawalUnknownException extends IllegalStateException {
        public WithdrawalUnknownException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
