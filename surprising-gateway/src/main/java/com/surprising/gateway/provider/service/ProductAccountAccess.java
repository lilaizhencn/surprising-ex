package com.surprising.gateway.provider.service;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.ProductTransferOperationRequest;
import com.surprising.account.provider.service.AccountCommandGateway;
import com.surprising.account.provider.service.AccountCommandRejectedException;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.product.api.ProductLine;
import java.util.List;
import org.springframework.stereotype.Service;

/** 跨产品线资金仍由两个独立 Core 记账；Gateway 内部只调用对应产品的账户方法。 */
@Service
public final class ProductAccountAccess implements ProductAccountClient {
    private final GatewayProductServices products;
    public ProductAccountAccess(GatewayProductServices products) { this.products = products; }

    @Override public ProductAccountAdjustment transferOut(String accountType, ProductTransferOperationRequest request) {
        return operation(accountType, request, "OUT");
    }
    @Override public ProductAccountAdjustment transferIn(String accountType, ProductTransferOperationRequest request) {
        return operation(accountType, request, "IN");
    }
    @Override public ProductAccountAdjustment completeTransfer(String accountType, ProductTransferOperationRequest request) {
        return operation(accountType, request, "COMPLETE");
    }
    @Override public List<ProductTransferOperationRequest> pendingTransfers(ProductLine product, int limit) {
        if (!products.enabled().contains(product)) return List.of();
        return products.service(product, AccountCommandGateway.class).pendingTransfers(limit);
    }
    private ProductAccountAdjustment operation(String accountType, ProductTransferOperationRequest request, String phase) {
        products.requireEnabled(request.sourceProductLine());
        products.requireEnabled(request.targetProductLine());
        ProductLine product = ProductTransferCoordinator.productLine(AccountType.valueOf(accountType));
        // 配置错误在提交前暴露；Core 超时则必须维持 UNKNOWN，不能退款或换一个命令标识。
        var commands = products.service(product, AccountCommandGateway.class);
        try {
            switch (phase) {
                case "OUT" -> commands.transferOut(request);
                case "IN" -> commands.transferIn(request);
                case "COMPLETE" -> commands.completeTransfer(request);
                default -> throw new IllegalArgumentException("unsupported transfer phase");
            }
            return ProductAccountAdjustment.applied(null);
        } catch (AccountCommandRejectedException ex) {
            return ProductAccountAdjustment.rejected(ex.errorCode());
        } catch (IllegalArgumentException ex) {
            return ProductAccountAdjustment.rejected(ex.getMessage());
        } catch (RuntimeException ex) {
            return ProductAccountAdjustment.unknown("account transfer outcome is unknown");
        }
    }
}
