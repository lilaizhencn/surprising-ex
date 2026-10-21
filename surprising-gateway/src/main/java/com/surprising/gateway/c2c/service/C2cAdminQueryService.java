package com.surprising.gateway.c2c.service;

import com.surprising.gateway.c2c.C2cAdsRepository;
import com.surprising.gateway.c2c.C2cFundsRepository;
import com.surprising.gateway.c2c.C2cOrdersRepository;
import com.surprising.gateway.c2c.C2cViews;
import com.surprising.gateway.c2c.model.C2cAd;
import com.surprising.gateway.c2c.model.C2cAdminViews;
import com.surprising.gateway.c2c.model.C2cMarket;
import com.surprising.gateway.c2c.model.C2cOrder;
import com.surprising.gateway.c2c.model.C2cPage;
import com.surprising.gateway.c2c.repository.C2cAppealRepository;
import com.surprising.gateway.c2c.repository.C2cMarketRepository;
import com.surprising.gateway.c2c.repository.C2cMerchantRepository;
import com.surprising.gateway.c2c.repository.C2cPaymentAccountRepository;
import com.surprising.gateway.c2c.repository.C2cReportRepository;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 聚合后台当前页及市场信息；参数格式在Controller校验，Repository不再构造HTTP响应。 */
@Service
@Transactional(readOnly = true)
public class C2cAdminQueryService {
    private final C2cMerchantRepository merchants;
    private final C2cPaymentAccountRepository payments;
    private final C2cAdsRepository ads;
    private final C2cOrdersRepository orders;
    private final C2cFundsRepository funds;
    private final C2cAppealRepository appeals;
    private final C2cReportRepository reports;
    private final C2cMarketRepository markets;

    public C2cAdminQueryService(C2cMerchantRepository merchants, C2cPaymentAccountRepository payments,
                                C2cAdsRepository ads, C2cOrdersRepository orders, C2cFundsRepository funds,
                                C2cAppealRepository appeals, C2cReportRepository reports, C2cMarketRepository markets) {
        this.merchants = merchants;
        this.payments = payments;
        this.ads = ads;
        this.orders = orders;
        this.funds = funds;
        this.appeals = appeals;
        this.reports = reports;
        this.markets = markets;
    }

    public C2cPage<C2cAdminViews.Merchant> merchants(String status, Long userId, int limit, int offset) {
        var rows = merchants.adminPage(status, userId, limit + 1, offset);
        var items = rows.stream().limit(limit).map(row -> new C2cAdminViews.Merchant(
                Long.toString(row.userId()), row.nickname(), row.status(), row.tier(), row.application(), row.reviewReason(),
                Long.toString(row.revision()), row.kycStatus(), row.bondStatus(), row.bondAsset(),
                row.bondUnits() == null ? null : C2cViews.quantity(row.bondUnits(), row.bondScale()),
                text(row.kycExpiresAt()), text(row.createdAt()))).toList();
        return new C2cPage<>(items, rows.size() > limit, offset);
    }

    public C2cPage<C2cAdminViews.PaymentAccount> paymentAccounts(String status, Long userId, int limit, int offset) {
        var rows = payments.adminPage(status, userId, limit + 1, offset);
        var items = rows.stream().limit(limit).map(row -> new C2cAdminViews.PaymentAccount(text(row.accountId()),
                Long.toString(row.userId()), row.nickname(), row.fiat(), row.methodCode(), row.maskedLabel(), row.status(),
                Long.toString(row.revision()), row.reviewReason(), text(row.createdAt()))).toList();
        return new C2cPage<>(items, rows.size() > limit, offset);
    }

    public C2cPage<Map<String, Object>> ads(String status, Long userId, UUID market, int limit, int offset) {
        var rows = ads.adminPage(status, userId, market, limit + 1, offset);
        var pageMarkets = markets.byIds(rows.stream().limit(limit).map(C2cAd::marketId).toList())
                .stream().collect(Collectors.toMap(C2cMarket::id, Function.identity()));
        var items = rows.stream().limit(limit).map(ad -> C2cViews.ad(ad, pageMarkets.get(ad.marketId()), true)).toList();
        return new C2cPage<>(items, rows.size() > limit, offset);
    }

    public C2cPage<Map<String, Object>> orders(String status, Long userId, UUID orderId, UUID market, int limit, int offset) {
        var rows = orders.adminPage(status, userId, orderId, market, limit + 1, offset);
        var pageMarkets = markets.byIds(rows.stream().limit(limit).map(C2cOrder::marketId).toList())
                .stream().collect(Collectors.toMap(C2cMarket::id, Function.identity()));
        var items = rows.stream().limit(limit).map(order -> C2cViews.order(order, pageMarkets.get(order.marketId()))).toList();
        return new C2cPage<>(items, rows.size() > limit, offset);
    }

    public C2cPage<C2cAdminViews.FundIntent> funds(String status, UUID ownerId, int limit, int offset) {
        var rows = funds.adminPage(status, ownerId, limit + 1, offset);
        var items = rows.stream().limit(limit).map(row -> new C2cAdminViews.FundIntent(text(row.intentId()),
                Long.toString(row.transferId()), row.ownerType(), text(row.ownerId()), row.purpose(),
                Long.toString(row.sourceUserId()), Long.toString(row.targetUserId()), row.asset(),
                Long.toString(row.amountUnits()), row.status(), row.errorCode(), row.attempts(),
                text(row.retryAfter()), text(row.updatedAt()))).toList();
        return new C2cPage<>(items, rows.size() > limit, offset);
    }

    public C2cPage<C2cAdminViews.Appeal> appeals(String status, UUID orderId, int limit, int offset) {
        var rows = appeals.adminPage(status, orderId, limit + 1, offset);
        var items = rows.stream().limit(limit).map(row -> new C2cAdminViews.Appeal(text(row.appealId()),
                text(row.orderId()), Long.toString(row.openerUserId()), row.status(), row.reason(), row.decision(),
                row.decisionReason(), text(row.approvalId()), text(row.decidedBy()), text(row.createdAt()))).toList();
        return new C2cPage<>(items, rows.size() > limit, offset);
    }

    public C2cPage<C2cAdminViews.Report> reports(String status, Long userId, int limit, int offset) {
        var rows = reports.adminPage(status, userId, limit + 1, offset);
        var items = rows.stream().limit(limit).map(row -> new C2cAdminViews.Report(text(row.reportId()),
                Long.toString(row.userId()), Long.toString(row.targetUserId()), text(row.orderId()), row.reason(),
                row.status(), row.reviewReason(), text(row.createdAt()))).toList();
        return new C2cPage<>(items, rows.size() > limit, offset);
    }

    private static String text(Object value) { return value == null ? null : value.toString(); }
}
