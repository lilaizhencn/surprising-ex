package com.surprising.gateway.c2c;

import com.surprising.gateway.c2c.model.C2cAd;
import com.surprising.gateway.c2c.model.C2cMarket;
import com.surprising.gateway.c2c.model.C2cOrder;
import com.surprising.gateway.c2c.model.C2cPaymentAccount;
import com.surprising.gateway.c2c.model.C2cQuote;
import com.surprising.gateway.c2c.model.C2cPaymentMethod;
import com.surprising.gateway.c2c.model.C2cReconciliation;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** 三端共用的 HTTP 字段：int64 全部为字符串，密文和内部托管身份永不直接序列化。 */
public final class C2cViews {
    private C2cViews() { }

    public static Map<String,Object> market(C2cMarket market) {
        var view = new LinkedHashMap<String,Object>();
        view.put("marketId", market.id().toString());
        view.put("asset", market.asset());
        view.put("assetScale", Long.toString(market.assetScale()));
        view.put("fiat", market.fiat());
        view.put("fiatDecimals", market.fiatDecimals());
        view.put("enabled", market.enabled());
        view.put("minFiat", market.minFiat().toPlainString());
        view.put("maxFiat", market.maxFiat().toPlainString());
        view.put("minPrice", market.minPrice().toPlainString());
        view.put("maxPrice", market.maxPrice().toPlainString());
        view.put("buyerFeePpm", market.buyerFeePpm());
        view.put("paymentWindowSeconds", market.paymentWindowSeconds());
        view.put("maxOpenOrders", market.maxOpenOrders());
        view.put("requireKyc", market.requireKyc());
        view.put("vipMinCompleted", market.vipMinCompleted());
        view.put("revision", Long.toString(market.revision()));
        return view;
    }

    public static Map<String,Object> ad(C2cAd ad, C2cMarket market, boolean own) {
        var view = new LinkedHashMap<String,Object>();
        view.put("adId", ad.id().toString());
        view.put("makerUserId", Long.toString(ad.makerUserId()));
        view.put("market", market(market));
        view.put("side", ad.side());
        view.put("price", ad.price().toPlainString());
        view.put("availableUnits", Long.toString(ad.availableUnits()));
        view.put("availableQuantity", quantity(ad.availableUnits(), market.assetScale()));
        view.put("minFiat", ad.minFiat().toPlainString());
        view.put("maxFiat", ad.maxFiat().toPlainString());
        view.put("methodCodes", ad.methods());
        view.put("paymentWindowSeconds", ad.paymentWindowSeconds());
        view.put("terms", ad.terms());
        view.put("minCompleted", ad.minCompleted());
        view.put("status", ad.status());
        view.put("revision", Long.toString(ad.revision()));
        if (own) {
            view.put("totalUnits", Long.toString(ad.totalUnits()));
            view.put("reservedUnits", Long.toString(ad.reservedUnits()));
            view.put("completedUnits", Long.toString(ad.completedUnits()));
            view.put("closedUnits", Long.toString(ad.closedUnits()));
            view.put("paymentAccountIds", ad.accounts().stream().map(Object::toString).toList());
        }
        return view;
    }

    public static Map<String,Object> quote(C2cQuote quote, C2cMarket market) {
        var view = new LinkedHashMap<String,Object>();
        view.put("quoteId", quote.id().toString());
        view.put("ownerUserId", Long.toString(quote.userId()));
        view.put("adId", quote.adId().toString());
        view.put("adRevision", Long.toString(quote.adRevision()));
        view.put("marketRevision", Long.toString(quote.marketRevision()));
        view.put("market", market(market));
        view.put("quantityUnits", Long.toString(quote.quantityUnits()));
        view.put("quantity", quantity(quote.quantityUnits(), market.assetScale()));
        view.put("buyerFeeUnits", Long.toString(quote.feeUnits()));
        view.put("buyerReceivesUnits", Long.toString(quote.quantityUnits() - quote.feeUnits()));
        view.put("fiatAmount", quote.fiatAmount().toPlainString());
        view.put("price", quote.price().toPlainString());
        view.put("paymentAccountId", quote.paymentAccountId().toString());
        view.put("paymentAccountRevision", Long.toString(quote.accountRevision()));
        view.put("expiresAt", quote.expiresAt().toString());
        view.put("serverTime", java.time.Instant.now().toString());
        return view;
    }

    public static Map<String,Object> order(C2cOrder order, C2cMarket market) {
        var view = new LinkedHashMap<String,Object>();
        view.put("orderId", order.id().toString());
        view.put("quoteId", order.quoteId().toString());
        view.put("adId", order.adId().toString());
        view.put("market", market(market));
        view.put("creatorUserId", Long.toString(order.creatorUserId()));
        view.put("buyerUserId", Long.toString(order.buyerUserId()));
        view.put("sellerUserId", Long.toString(order.sellerUserId()));
        view.put("quantityUnits", Long.toString(order.quantityUnits()));
        view.put("quantity", quantity(order.quantityUnits(), market.assetScale()));
        view.put("buyerFeeUnits", Long.toString(order.feeUnits()));
        view.put("buyerReceivesUnits", Long.toString(order.quantityUnits() - order.feeUnits()));
        view.put("fiatAmount", order.fiatAmount().toPlainString());
        view.put("price", order.price().toPlainString());
        view.put("paymentAccountId", order.paymentAccountId().toString());
        view.put("paymentWindowSeconds", order.paymentWindowSeconds());
        view.put("terms", order.terms());
        view.put("status", order.status().name());
        view.put("requestedTerminal", order.requestedTerminal());
        view.put("paymentExpiresAt", text(order.paymentExpiresAt()));
        view.put("paidAt", text(order.paidAt()));
        view.put("completedAt", text(order.completedAt()));
        view.put("canceledAt", text(order.canceledAt()));
        view.put("cancelReason", order.cancelReason());
        view.put("revision", Long.toString(order.revision()));
        view.put("createdAt", text(order.createdAt()));
        return view;
    }

    static Map<String,Object> paymentAccount(C2cPaymentAccount account) {
        return Map.of("accountId", account.id().toString(), "ownerUserId", Long.toString(account.userId()),
                "fiat", account.fiat(), "methodCode", account.method(), "maskedLabel", account.maskedLabel(),
                "status", account.status(), "revision", Long.toString(account.revision()));
    }

    public static Map<String,Object> submission(long userId, String key, String kind, String hash, C2cSubmissions.Submission receipt) {
        var view = new LinkedHashMap<String,Object>();
        view.put("ownerUserId", Long.toString(userId));
        view.put("requestKey", key);
        view.put("kind", kind);
        view.put("requestHash", hash);
        view.put("status", receipt == null ? "UNKNOWN" : receipt.status());
        view.put("resultId", receipt == null ? null : text(receipt.resultId()));
        view.put("errorCode", receipt == null ? null : receipt.errorCode());
        view.put("message", receipt == null ? "原请求尚未确认，请继续查询或明确停止；勿更换标识重新提交" : receipt.errorMessage());
        return view;
    }
    public static Map<String,Object> paymentMethod(C2cPaymentMethod method) {
        return Map.of("code",method.code(),"label",method.label(),"enabled",method.enabled(),"currencies",method.currencies(),
                "requiresBankName",method.requiresBankName(),"supportsQr",method.supportsQr(),"revision",Long.toString(method.revision()));
    }
    public static Map<String,Object> reconciliation(C2cReconciliation result) {
        var view=new LinkedHashMap<String,Object>();view.put("status",result.status().name());
        view.put("assets",result.assets().stream().map(row -> Map.<String,Object>of("asset",row.asset(),
                "expectedEscrowUnits",row.expectedEscrow().toBigIntegerExact().toString(),"actualEscrowUnits",Long.toString(row.actualEscrow()),
                "escrowLockedUnits",Long.toString(row.escrowLocked()),"expectedFeeUnits",row.expectedFees().toBigIntegerExact().toString(),
                "actualFeeUnits",Long.toString(row.actualFees()),"feeLockedUnits",Long.toString(row.feeLocked()),"matches",row.matches())).toList());
        switch(result.status()) {
            case REVIEW_REQUIRED -> view.put("failedSettlementCount",Long.toString(result.failed()));
            case UNAVAILABLE -> view.put("reason",result.reason());
            default -> view.put("pendingIntentCount",Long.toString(result.pending()));
        }
        return view;
    }
    public static String quantity(long units, long scale) {
        if (scale <= 0 || !Long.toString(scale).matches("10*")) throw new IllegalStateException("资产精度未就绪");
        return BigDecimal.valueOf(units).divide(BigDecimal.valueOf(scale)).stripTrailingZeros().toPlainString();
    }
    private static String text(Object value) { return value == null ? null : value.toString(); }
}
