package com.surprising.gateway.provider.service;

import com.surprising.account.api.AccountApiPaths;
import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.PendingProductTransfersRequest;
import com.surprising.account.api.model.PendingProductTransfersResponse;
import com.surprising.account.api.model.ProductTransferOperationRequest;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.product.api.ProductLine;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Service
public final class ProductAccountAccess implements ProductAccountClient {

    private final com.surprising.account.provider.service.AccountCommandGateway commands;

    private final com.surprising.account.provider.config.AccountProperties accountProperties;

    private final GatewayProperties properties;

    private final RestTemplate restTemplate;

    public ProductAccountAccess(GatewayProperties properties, RestTemplate restTemplate, com.surprising.account.provider.service.AccountCommandGateway commands, com.surprising.account.provider.config.AccountProperties accountProperties) {
        this.commands = commands;
        this.accountProperties = accountProperties;
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    @Override
    public ProductAccountAdjustment transferOut(String accountType, ProductTransferOperationRequest request) {
        return operation(accountType, AccountApiPaths.TRANSFER_OUT_PATH, request);
    }

    @Override
    public ProductAccountAdjustment transferIn(String accountType, ProductTransferOperationRequest request) {
        return operation(accountType, AccountApiPaths.TRANSFER_IN_PATH, request);
    }

    @Override
    public ProductAccountAdjustment completeTransfer(String accountType, ProductTransferOperationRequest request) {
        return operation(accountType, AccountApiPaths.TRANSFER_COMPLETE_PATH, request);
    }

    @Override
    public List<ProductTransferOperationRequest> pendingTransfers(ProductLine productLine, int limit) {
        if (productLine == accountProperties.getKafka().getProductLine()) {
            return commands.pendingTransfers(limit);
        }
        PendingProductTransfersRequest request = new PendingProductTransfersRequest(productLine, limit);
        String audience = AccountApiPaths.TRANSFER_PENDING_PATH;
        try {
            ResponseEntity<PendingProductTransfersResponse> response = restTemplate.exchange(target(productLine, audience), HttpMethod.POST, new HttpEntity<>(request, headers()), PendingProductTransfersResponse.class);
            PendingProductTransfersResponse body = response.getBody();
            return response.getStatusCode().is2xxSuccessful() && body != null ? body.transfers() : List.of();
        } catch (RestClientException exception) {
            throw new IllegalStateException("pending transfer runtime query failed for " + productLine, exception);
        }
    }

    private ProductAccountAdjustment operation(String accountType, String audience, ProductTransferOperationRequest request) {
        ProductLine productLine = ProductTransferCoordinator.productLine(AccountType.valueOf(accountType));
        if (productLine == accountProperties.getKafka().getProductLine()) {
            try {
                switch(audience) {
                    case AccountApiPaths.TRANSFER_OUT_PATH ->
                        commands.transferOut(request);
                    case AccountApiPaths.TRANSFER_IN_PATH ->
                        commands.transferIn(request);
                    case AccountApiPaths.TRANSFER_COMPLETE_PATH ->
                        commands.completeTransfer(request);
                    default ->
                        throw new IllegalArgumentException("unsupported transfer operation");
                }
                return ProductAccountAdjustment.applied(null);
            } catch (com.surprising.account.provider.service.AccountCommandRejectedException exception) {
                return ProductAccountAdjustment.rejected(exception.errorCode());
            } catch (IllegalArgumentException exception) {
                return ProductAccountAdjustment.rejected(exception.getMessage());
            } catch (RuntimeException exception) {
                // 超时/失联之后 Core 可能已执行，保留对账重试，不能误判为拒绝并退款。
                return ProductAccountAdjustment.unknown("local account transfer outcome is unknown");
            }
        }
        try {
            ResponseEntity<String> response = restTemplate.exchange(target(productLine, audience), HttpMethod.POST, new HttpEntity<>(request, headers()), String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                return ProductAccountAdjustment.applied(response.getBody());
            }
            return status(response.getStatusCode().value());
        } catch (HttpStatusCodeException exception) {
            return status(exception.getStatusCode().value());
        } catch (RestClientException exception) {
            return ProductAccountAdjustment.unknown("account provider transfer outcome is unknown");
        }
    }

    private ProductAccountAdjustment status(int status) {
        if (status == 400 || status == 409 || status == 422) {
            return ProductAccountAdjustment.rejected("account provider rejected transfer HTTP " + status);
        }
        return ProductAccountAdjustment.unknown("account provider transfer outcome is unknown HTTP " + status);
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private URI target(ProductLine productLine, String audience) {
        GatewayProperties.BackendRoute account = properties.getRoutes().get("account");
        GatewayProperties.ProductRoute configured = account == null ? null : account.getProductRoutes().get(productLine);
        GatewayProperties.BackendRoute route = configured == null || configured.getBaseUrl() == null || configured.getBaseUrl().isBlank() ? null : account.resolve(productLine);
        if (route == null || route.getBaseUrl() == null || route.getBaseUrl().isBlank()) {
            throw new IllegalStateException("account route is not configured for " + productLine);
        }
        return URI.create(trimTrailingSlash(route.getBaseUrl()) + audience);
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
