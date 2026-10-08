package com.surprising.gateway.provider.service;

import com.surprising.account.api.model.BalanceAdjustmentRequest;
import com.surprising.account.provider.service.AccountCommandGateway;
import com.surprising.account.provider.service.AccountCommandRejectedException;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.product.api.ProductLine;
import java.util.Locale;
import org.springframework.stereotype.Service;

/** 托管资金始终记入 SPOT Core，不依赖现货币对或额外 Gateway 进程。 */
@Service
public class SpotAccountClient {
    private final GatewayProductServices products;
    public SpotAccountClient(GatewayProductServices products) { this.products = products; }
    public void adjustBalance(long userId, String asset, long amountUnits, String referenceId, String reason) {
        if (userId <= 0L || asset == null || asset.isBlank() || referenceId == null || referenceId.isBlank())
            throw new IllegalArgumentException("spot balance adjustment request is invalid");
        var commands = products.service(ProductLine.SPOT, AccountCommandGateway.class);
        try {
            commands.adjustBalance(new BalanceAdjustmentRequest(userId, asset.trim().toUpperCase(Locale.ROOT),
                    amountUnits, referenceId, reason == null ? "" : reason), null, null);
        } catch (AccountCommandRejectedException ex) {
            throw new SpotAccountRejectedException(ex.getMessage(), 409);
        } catch (IllegalArgumentException ex) {
            throw new SpotAccountRejectedException(ex.getMessage(), 400);
        } catch (RuntimeException ex) {
            throw new SpotAccountUnknownException("spot adjustment outcome is unknown", 503, ex);
        }
    }

    public static class SpotAccountRejectedException extends IllegalStateException {

        private final int status;

        public SpotAccountRejectedException(String message, int status) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    public static class SpotAccountUnknownException extends IllegalStateException {

        private final int status;

        public SpotAccountUnknownException(String message, int status) {
            super(message);
            this.status = status;
        }

        public SpotAccountUnknownException(String message, int status, Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
